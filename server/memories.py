"""Family growth selections and editable monthly storybooks.

Only feature metadata is persisted here. Source media and descriptions are never
rewritten, and reading a suggested storybook does not create a saved book.
"""
from __future__ import annotations

import calendar
from datetime import date
import re

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field


MAX_PAGES = 80
DRAFT_PAGES = 24


class GrowthChoice(BaseModel):
    media_id: str = Field(min_length=1, max_length=200)


class StoryPage(BaseModel):
    media_id: str = Field(min_length=1, max_length=200)
    text: str = Field(default='', max_length=5000)


class StoryEdit(BaseModel):
    title: str = Field(min_length=1, max_length=120)
    cover_media_id: str = Field(min_length=1, max_length=200)
    pages: list[StoryPage] = Field(min_length=1, max_length=MAX_PAGES)
    revision: int = Field(ge=0)


def initialize(connection):
    schema = """
        CREATE TABLE IF NOT EXISTS growth_selections (
            year INTEGER PRIMARY KEY, media_id TEXT NOT NULL REFERENCES media(id) ON DELETE CASCADE,
            updated_at TEXT NOT NULL, updated_by TEXT REFERENCES members(id) ON DELETE SET NULL
        );
        CREATE TABLE IF NOT EXISTS storybooks (
            month TEXT PRIMARY KEY, title TEXT NOT NULL,
            cover_media_id TEXT REFERENCES media(id) ON DELETE SET NULL,
            revision INTEGER NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
            updated_by TEXT REFERENCES members(id) ON DELETE SET NULL
        );
        CREATE TABLE IF NOT EXISTS storybook_pages (
            month TEXT NOT NULL REFERENCES storybooks(month) ON DELETE CASCADE,
            position INTEGER NOT NULL,
            media_id TEXT NOT NULL REFERENCES media(id) ON DELETE CASCADE,
            text TEXT NOT NULL DEFAULT '', author_name TEXT NOT NULL DEFAULT '',
            PRIMARY KEY(month, position), UNIQUE(month, media_id)
        );
    """
    for statement in schema.split(';'):
        if statement.strip():
            connection.execute(statement)
    # Keep the original records intact for a reversible migration. New records
    # use a child-and-period key so siblings cannot overwrite each other's book.
    connection.execute('''CREATE TABLE IF NOT EXISTS child_growth_selections (
        child_id TEXT NOT NULL REFERENCES children(id),year INTEGER NOT NULL,
        media_id TEXT NOT NULL REFERENCES media(id),updated_at TEXT NOT NULL,updated_by TEXT,
        PRIMARY KEY(child_id,year))''')
    connection.execute('''CREATE TABLE IF NOT EXISTS child_storybooks (
        child_id TEXT NOT NULL REFERENCES children(id),month TEXT NOT NULL,title TEXT NOT NULL,
        cover_media_id TEXT,revision INTEGER NOT NULL DEFAULT 1,created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL,updated_by TEXT,PRIMARY KEY(child_id,month))''')
    connection.execute('''CREATE TABLE IF NOT EXISTS child_storybook_pages (
        child_id TEXT NOT NULL,month TEXT NOT NULL,position INTEGER NOT NULL,
        media_id TEXT NOT NULL REFERENCES media(id),text TEXT NOT NULL DEFAULT '',author_name TEXT NOT NULL DEFAULT '',
        PRIMARY KEY(child_id,month,position),UNIQUE(child_id,month,media_id),
        FOREIGN KEY(child_id,month) REFERENCES child_storybooks(child_id,month) ON DELETE CASCADE)''')
    if not connection.execute("SELECT 1 FROM settings WHERE key='multi_child_memories_version'").fetchone():
        child = connection.execute("SELECT value FROM settings WHERE key='default_child_id'").fetchone()
        if child:
            for table, fields in (
                ('growth_selections', 'year,media_id,updated_at,updated_by'),
                ('storybooks', 'month,title,cover_media_id,revision,created_at,updated_at,updated_by'),
                ('storybook_pages', 'month,position,media_id,text,author_name'),
            ):
                connection.execute(f'INSERT OR IGNORE INTO child_{table}(child_id,{fields}) SELECT ?,{fields} FROM {table}', (child[0],))
            connection.execute("INSERT INTO settings VALUES ('multi_child_memories_version','1')")


def anniversary(birthday: date, year: int) -> date:
    target = birthday.year + year
    return date(target, birthday.month,
                min(birthday.day, calendar.monthrange(target, birthday.month)[1]))


def full_years(birthday: date, day: date) -> int:
    if day < birthday:
        return -1
    year = day.year - birthday.year
    return year - int(day < anniversary(birthday, year))


def month_value(value: str) -> str:
    try:
        if not re.fullmatch(r'\d{4}-\d{2}', value):
            raise ValueError()
        parsed = date.fromisoformat(value + '-01')
        if not 1900 <= parsed.year <= 2200:
            raise ValueError()
        return value
    except ValueError:
        raise HTTPException(422, '请选择有效的月份。') from None


def month_title(value: str) -> str:
    year, month = map(int, value.split('-'))
    return f'{year}年{month}月的故事'


def age_title(year: int) -> str:
    return '出生至1岁' if year == 0 else f'{year}岁'


def build_router(database, get_member, media_dto, encode_cursor, decode_cursor, now_text, children=None):
    router = APIRouter()

    def can_edit(member):
        return member['account_role'] in {'admin', 'contributor'} and not member.get('child_archived')

    def editor(member=Depends(get_member)):
        if children:
            children.require_active(member)
        if not can_edit(member):
            raise HTTPException(403, '你目前可以查看回忆，整理需要上传或管理权限，请联系管理员。')
        return member

    def child_id(member):
        return member['child_id']

    def scope(member):
        return ('EXISTS(SELECT 1 FROM media_children mc WHERE mc.media_id=media.id '
                'AND mc.child_id=? AND mc.deleted_at IS NULL)', [child_id(member)])

    def birthday_text(connection, member):
        row = connection.execute('SELECT birthday FROM children WHERE id=?', (child_id(member),)).fetchone()
        return row[0] if row else ''

    def birthday_date(connection, member, required=True):
        text = birthday_text(connection, member)
        try:
            return date.fromisoformat(text)
        except ValueError:
            if required:
                raise HTTPException(422, '还没有填写宝宝生日，请联系管理员补充后再按年龄整理照片。') from None
            return None

    def media_rows(connection, member, where, params, suffix=''):
        filtered, values = scope(member)
        return connection.execute("""SELECT media.*,
            (SELECT COUNT(*) FROM comments WHERE media_id=media.id AND child_id=?) AS comments_count,
            EXISTS(SELECT 1 FROM favorites WHERE media_id=media.id AND member_id=?) AS is_favorite
            FROM media WHERE """ + filtered + ' AND (' + where + ')' + suffix,
            [child_id(member), member['id'], *values, *params]).fetchall()

    def year_bounds(connection, year, member):
        if not 0 <= year <= 300:
            raise HTTPException(422, '请选择有效的年龄。')
        birthday = birthday_date(connection, member)
        return birthday, anniversary(birthday, year).isoformat(), anniversary(birthday, year + 1).isoformat()

    def year_entry(connection, member, birthday, year, count=None):
        start, end = anniversary(birthday, year).isoformat(), anniversary(birthday, year + 1).isoformat()
        where = "kind='photo' AND album_date>=? AND album_date<?"
        params = [start, end]
        if count is None:
            filtered, values = scope(member)
            count = connection.execute('SELECT COUNT(*) FROM media WHERE ' + filtered + ' AND ' + where, values + params).fetchone()[0]
        saved = connection.execute('SELECT media_id FROM child_growth_selections WHERE child_id=? AND year=?', (child_id(member), year)).fetchone()
        chosen = media_rows(connection, member, where + ' AND id=?', [*params, saved[0]]) if saved else []
        if not chosen:
            chosen = media_rows(connection, member, where, params,
                                ' ORDER BY album_date DESC,sort_time ASC,id ASC LIMIT 1')
        return {'year': year, 'label': age_title(year), 'count': count,
                'selected': media_dto(chosen[0], birthday.isoformat(), member) if chosen else None,
                'customized': bool(saved and chosen and chosen[0]['id'] == saved[0])}

    @router.get('/api/growth')
    def growth(member=Depends(get_member)):
        with database() as connection:
            connection.execute('BEGIN')
            born = birthday_date(connection, member, False)
            if born is None:
                return {'birthday': '', 'can_edit': can_edit(member), 'years': [],
                        'message': '还没有填写宝宝生日，请联系管理员补充后再按年龄回看成长。'}
            filtered, values = scope(member)
            days = connection.execute("SELECT album_date,COUNT(*) AS count FROM media WHERE " + filtered +
                " AND kind='photo' AND album_date>=? GROUP BY album_date ORDER BY album_date", values + [born.isoformat()]).fetchall()
            counts = {}
            for row in days:
                year = full_years(born, date.fromisoformat(row['album_date']))
                counts[year] = counts.get(year, 0) + row['count']
            return {'birthday': born.isoformat(), 'can_edit': can_edit(member),
                    'years': [year_entry(connection, member, born, year, counts[year])
                              for year in sorted(counts)],
                    'message': '' if counts else '上传宝宝出生后的照片，就能从小到大慢慢回看。'}

    @router.get('/api/growth/{year}/photos')
    def growth_photos(year: int, cursor: str | None = None, limit: int = 60, member=Depends(get_member)):
        limit = max(1, min(limit, 200))
        with database() as connection:
            connection.execute('BEGIN')
            birthday, start, end = year_bounds(connection, year, member)
            where = "kind='photo' AND album_date>=? AND album_date<?"
            params = [start, end]
            filtered, values = scope(member)
            total = connection.execute('SELECT COUNT(*) FROM media WHERE ' + filtered + ' AND ' + where, values + params).fetchone()[0]
            if cursor:
                day, stamp, identity = decode_cursor(cursor)
                where += ' AND (album_date<? OR (album_date=? AND (sort_time>? OR (sort_time=? AND id>?))))'
                params += [day, day, stamp, stamp, identity]
            rows = media_rows(connection, member, where, [*params, limit + 1],
                              ' ORDER BY album_date DESC,sort_time ASC,id ASC LIMIT ?')
            return {'year': year, 'label': age_title(year), 'total': total,
                    'items': [media_dto(row, birthday.isoformat(), member) for row in rows[:limit]],
                    'next_cursor': encode_cursor(rows[limit - 1]) if len(rows) > limit else None}

    @router.put('/api/growth/{year}')
    def save_growth(year: int, body: GrowthChoice, member=Depends(editor)):
        with database() as connection:
            connection.execute('BEGIN IMMEDIATE')
            birthday, start, end = year_bounds(connection, year, member)
            filtered, values = scope(member)
            valid = connection.execute("SELECT id FROM media WHERE " + filtered +
                " AND id=? AND kind='photo' AND album_date>=? AND album_date<?", values + [body.media_id, start, end]).fetchone()
            if valid is None:
                raise HTTPException(422, '请选择这个年龄段中仍在相册里的照片。')
            connection.execute("""INSERT INTO child_growth_selections(child_id,year,media_id,updated_at,updated_by)
                VALUES (?,?,?,?,?) ON CONFLICT(child_id,year) DO UPDATE SET media_id=excluded.media_id,
                updated_at=excluded.updated_at,updated_by=excluded.updated_by""",
                (child_id(member), year, body.media_id, now_text(), member['id']))
            return {**year_entry(connection, member, birthday, year), 'can_edit': True}

    def month_counts(connection, month, member):
        filtered, values = scope(member)
        row = connection.execute("""SELECT COUNT(*) AS total,COALESCE(SUM(kind='photo'),0) AS photos,
            COALESCE(SUM(kind='video'),0) AS videos FROM media WHERE """ + filtered +
            " AND substr(album_date,1,7)=?", values + [month]).fetchone()
        return dict(row)

    def suggested_rows(connection, member, month):
        # Keep one photo and one video per day, then sample evenly across the
        # month. This bounds the draft while retaining the beginning and end.
        filtered, values = scope(member)
        rows = media_rows(connection, member, """id IN (
            SELECT id FROM (SELECT id,ROW_NUMBER() OVER (
                PARTITION BY album_date,kind ORDER BY sort_time ASC,id ASC) AS choice
                FROM media WHERE """ + filtered + " AND substr(album_date,1,7)=?) WHERE choice=1)",
                values + [month], ' ORDER BY album_date ASC,sort_time ASC,id ASC')
        if len(rows) <= DRAFT_PAGES:
            return rows
        return [rows[round(i * (len(rows) - 1) / (DRAFT_PAGES - 1))] for i in range(DRAFT_PAGES)]

    def read_book(connection, member, month):
        birthday = birthday_text(connection, member)
        book = connection.execute('SELECT * FROM child_storybooks WHERE child_id=? AND month=?', (child_id(member), month)).fetchone()
        pages = []
        if book:
            records = connection.execute('SELECT * FROM child_storybook_pages WHERE child_id=? AND month=? ORDER BY position', (child_id(member), month)).fetchall()
            candidates = media_rows(connection, member, "substr(album_date,1,7)=? AND id IN "
                                    "(SELECT media_id FROM child_storybook_pages WHERE child_id=? AND month=?)", [month, child_id(member), month])
            candidates = {row['id']: row for row in candidates}
            for record in records:
                row = candidates.get(record['media_id'])
                if row is not None:
                    pages.append({'media': media_dto(row, birthday, member), 'text': record['text'],
                                  'author_name': record['author_name'] if record['text'] else ''})
        else:
            pages = [{'media': media_dto(row, birthday, member), 'text': row['description'],
                      'author_name': ''}
                     for row in suggested_rows(connection, member, month)]
        cover = next((page['media'] for page in pages if book and page['media']['id'] == book['cover_media_id']), None)
        if cover is None and pages:
            cover = next((page['media'] for page in pages if page['media']['kind'] == 'photo'), pages[0]['media'])
        return {'month': month, 'title': book['title'] if book else month_title(month),
                'cover': cover, 'pages': pages, 'revision': book['revision'] if book else 0,
                'can_edit': can_edit(member) and not member.get('child_archived'), 'saved': bool(book), **month_counts(connection, month, member)}

    @router.get('/api/storybooks')
    def storybooks(member=Depends(get_member)):
        with database() as connection:
            connection.execute('BEGIN')
            filtered, values = scope(member)
            months = [row[0] for row in connection.execute("SELECT substr(album_date,1,7) AS month FROM media WHERE " + filtered +
                " UNION SELECT month FROM child_storybooks WHERE child_id=? ORDER BY month DESC", values + [child_id(member)])]
            result = []
            for month in months:
                book = read_book(connection, member, month)
                result.append({key: book[key] for key in ('month', 'title', 'cover', 'photos', 'videos', 'total', 'saved', 'revision')} |
                              {'page_count': len(book['pages'])})
            return {'months': result, 'can_edit': can_edit(member)}

    @router.get('/api/storybooks/{month}')
    def storybook(month: str, member=Depends(get_member)):
        month = month_value(month)
        with database() as connection:
            connection.execute('BEGIN')
            return read_book(connection, member, month)

    @router.put('/api/storybooks/{month}')
    def save_storybook(month: str, body: StoryEdit, member=Depends(editor)):
        month = month_value(month)
        title = body.title.strip()
        if not title:
            raise HTTPException(422, '请给故事册起一个名字。')
        ids = [page.media_id for page in body.pages]
        if len(set(ids)) != len(ids):
            raise HTTPException(422, '同一份照片或视频只需加入一次。')
        if body.cover_media_id not in ids:
            raise HTTPException(422, '请选择故事册里的一张照片或视频作为封面。')
        with database() as connection:
            connection.execute('BEGIN IMMEDIATE')
            book = connection.execute('SELECT * FROM child_storybooks WHERE child_id=? AND month=?', (child_id(member), month)).fetchone()
            revision = book['revision'] if book else 0
            if revision != body.revision:
                raise HTTPException(409, '家人刚刚更新了这本故事册，请重新打开后再整理，以免覆盖他们的修改。')
            filtered, values = scope(member)
            valid = {row['id']: row for row in connection.execute(
                "SELECT * FROM media WHERE " + filtered + " AND substr(album_date,1,7)=? AND id IN (" +
                ','.join('?' for _ in ids) + ')', [*values, month, *ids])}
            if len(valid) != len(ids):
                raise HTTPException(422, '有照片已移出相册或不属于这个月，请重新选择后保存。')
            previous = {row['media_id']: row for row in connection.execute('SELECT * FROM child_storybook_pages WHERE child_id=? AND month=?', (child_id(member), month))}
            authors = []
            for page in body.pages:
                old = previous.get(page.media_id)
                if not page.text:
                    authors.append('')
                elif old and old['text'] == page.text:
                    authors.append(old['author_name'])
                elif old is None and page.text == valid[page.media_id]['description']:
                    # Older media descriptions have no reliable author field.
                    # Uploading a photo does not prove who wrote its story.
                    authors.append('')
                else:
                    authors.append(member['name'])
            now = now_text()
            connection.execute("""INSERT INTO child_storybooks(child_id,month,title,cover_media_id,revision,created_at,updated_at,updated_by)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(child_id,month) DO UPDATE SET title=excluded.title,
                cover_media_id=excluded.cover_media_id,revision=excluded.revision,
                updated_at=excluded.updated_at,updated_by=excluded.updated_by""",
                (child_id(member), month, title, body.cover_media_id, revision + 1, now, now, member['id']))
            connection.execute('DELETE FROM child_storybook_pages WHERE child_id=? AND month=?', (child_id(member), month))
            connection.executemany('''INSERT INTO child_storybook_pages(child_id,month,position,media_id,text,author_name)
                VALUES (?,?,?,?,?,?)''', [(child_id(member), month, index, page.media_id, page.text, authors[index])
                                    for index, page in enumerate(body.pages)])
            return read_book(connection, member, month)

    return router
