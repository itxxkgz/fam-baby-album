"""Grouped family activity, stable paging snapshots, and safe quick replies.

The original media/activity/comment rows remain the source of truth. The small
sequence index only gives imported and newly created records monotonic snapshot
positions; it does not create activity or rewrite historical records.
"""
from __future__ import annotations

import base64
from datetime import datetime, timedelta, timezone
import hashlib
import json
import math
import re
import uuid

from fastapi import APIRouter, Depends, HTTPException


ZONE = timezone(timedelta(hours=8))
GROUP_SECONDS = 30 * 60


def initialize(connection):
    schema = """
        CREATE TABLE IF NOT EXISTS activity_feed_entries (
            sequence INTEGER PRIMARY KEY AUTOINCREMENT,
            entry_type TEXT NOT NULL, source_id TEXT NOT NULL,
            UNIQUE(entry_type, source_id)
        );
        CREATE TABLE IF NOT EXISTS comment_replies (
            comment_id TEXT PRIMARY KEY REFERENCES comments(id) ON DELETE CASCADE,
            reply_to_id TEXT REFERENCES comments(id) ON DELETE SET NULL
        );
        CREATE TABLE IF NOT EXISTS comment_submissions (
            member_id TEXT NOT NULL REFERENCES members(id) ON DELETE CASCADE,
            request_id TEXT NOT NULL,
            comment_id TEXT REFERENCES comments(id) ON DELETE SET NULL,
            fingerprint TEXT NOT NULL, created_at TEXT NOT NULL,
            PRIMARY KEY(member_id, request_id)
        );
    """
    for statement in schema.split(';'):
        if statement.strip():
            connection.execute(statement)


def reply_for(connection, comment_id):
    row = connection.execute("""SELECT parent.id,parent.name,parent.text
        FROM comment_replies link JOIN comments parent ON parent.id=link.reply_to_id
        JOIN comments child ON child.id=link.comment_id AND child.media_id=parent.media_id AND child.child_id=parent.child_id
        JOIN media_children mc ON mc.media_id=child.media_id AND mc.child_id=child.child_id AND mc.deleted_at IS NULL
        WHERE link.comment_id=?""", (comment_id,)).fetchone()
    return dict(row) if row else None


def create_comment(database, get_media, comment_dto, now_text, media_id, body, member):
    text = body.text.strip()
    if not text:
        raise HTTPException(422, '请写下想说的话。')
    reply_to_id = body.reply_to_id or None
    request_id = body.request_id or None
    if request_id and not re.fullmatch(r'[A-Za-z0-9_-]{8,100}', request_id):
        raise HTTPException(422, '这次发送信息不完整，请重新打开回应窗口。')
    if member.get('child_archived'):
        raise HTTPException(409, '这个宝宝的相册已归档，恢复使用后才能写留言。')
    child_id = member['child_id']
    fingerprint = hashlib.sha256(json.dumps([child_id, media_id, reply_to_id, text],
                                            ensure_ascii=False).encode('utf-8')).hexdigest()
    with database() as connection:
        connection.execute('BEGIN IMMEDIATE')
        media = get_media(connection, media_id, member)
        if media['deleted_at']:
            raise HTTPException(409, '这份照片或视频已移入回收站，暂时不能回应。')
        if request_id:
            prior = connection.execute('SELECT * FROM comment_submissions WHERE member_id=? AND request_id=?',
                                       (member['id'], request_id)).fetchone()
            if prior:
                if prior['fingerprint'] != fingerprint:
                    raise HTTPException(409, '这次回应的内容已经变化，请重新发送。')
                comment = connection.execute('SELECT * FROM comments WHERE id=?', (prior['comment_id'],)).fetchone()
                if comment is None:
                    raise HTTPException(409, '这条留言已经被删除，不会重复发送。')
                return comment_dto(comment, member, connection)
        if reply_to_id:
            parent = connection.execute('SELECT media_id FROM comments WHERE id=? AND child_id=?', (reply_to_id, child_id)).fetchone()
            if parent is None or parent['media_id'] != media_id:
                raise HTTPException(409, '要回复的留言已不存在或不属于这份内容，请刷新后再试。')
        identity, created = uuid.uuid4().hex, now_text()
        connection.execute('INSERT INTO comments(id,media_id,member_id,name,text,created_at,child_id) VALUES (?,?,?,?,?,?,?)',
                           (identity, media_id, member['id'], member['name'], text, created, child_id))
        # Reuse the new comment identity for its legacy activity entry so deletion
        # is exact even when two family members have the same name and wording.
        connection.execute('INSERT INTO activity(id,media_id,type,member_name,text,created_at,child_id) VALUES (?,?,?,?,?,?,?)',
                           (identity, media_id, 'comment', member['name'], text, created, child_id))
        if reply_to_id:
            connection.execute('INSERT INTO comment_replies VALUES (?,?)', (identity, reply_to_id))
        if request_id:
            connection.execute('INSERT INTO comment_submissions VALUES (?,?,?,?,?)',
                               (member['id'], request_id, identity, fingerprint, created))
        return comment_dto(connection.execute('SELECT * FROM comments WHERE id=?', (identity,)).fetchone(),
                           member, connection)


def _encode(value):
    return base64.urlsafe_b64encode(json.dumps(value, separators=(',', ':')).encode()).decode().rstrip('=')


def _decode(value, purpose):
    try:
        if not isinstance(value, str) or not 1 <= len(value) <= 2048:
            raise ValueError()
        decoded = json.loads(base64.b64decode(value + '=' * (-len(value) % 4), altchars=b'-_', validate=True))
        if not isinstance(decoded, dict) or decoded.get('v') != 1 or decoded.get('p') != purpose:
            raise ValueError()
        if type(decoded.get('s')) is not int or decoded['s'] < 0:
            raise ValueError()
        return decoded
    except (ValueError, TypeError, UnicodeError):
        raise HTTPException(422, '这段浏览位置已失效，请刷新家人动态。') from None


def _clock(value):
    try:
        parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=ZONE)
        parsed = parsed.astimezone(ZONE)
        return parsed.timestamp(), parsed.isoformat(), parsed.date().isoformat()
    except (TypeError, ValueError, AttributeError, OverflowError):
        return 0.0, '', ''


def _synchronize(connection):
    # AUTOINCREMENT prevents a just-deleted newest comment's position from being
    # reused by a new comment while another device is paging an older snapshot.
    connection.execute("""INSERT INTO activity_feed_entries(entry_type,source_id)
        SELECT 'upload',activity.id FROM activity
        WHERE activity.type='upload' AND NOT EXISTS(
            SELECT 1 FROM activity_feed_entries WHERE entry_type='upload' AND source_id=activity.id)
        ORDER BY activity.rowid""")
    connection.execute("""INSERT INTO activity_feed_entries(entry_type,source_id)
        SELECT 'comment',comments.id FROM comments WHERE NOT EXISTS(
            SELECT 1 FROM activity_feed_entries WHERE entry_type='comment' AND source_id=comments.id)
        ORDER BY comments.rowid""")
    return connection.execute('SELECT COALESCE(MAX(sequence),0) FROM activity_feed_entries').fetchone()[0]


def _events(connection, snapshot, member):
    uploads = []
    for row in connection.execute("""SELECT activity.id,activity.media_id,activity.member_name,activity.created_at,
            entry.sequence,COALESCE(mc.linked_by,media.uploader_id) AS uploader_id,
            media.uploader_name,media.kind,mc.deleted_at,
            members.name AS current_name
        FROM activity JOIN activity_feed_entries entry
            ON entry.entry_type='upload' AND entry.source_id=activity.id AND entry.sequence<=?
        JOIN media ON media.id=activity.media_id
        JOIN media_children mc ON mc.media_id=media.id AND mc.child_id=activity.child_id
        LEFT JOIN members ON members.id=COALESCE(mc.linked_by,media.uploader_id)
        WHERE activity.type='upload' AND activity.child_id=?""", (snapshot, member['child_id'])):
        item = dict(row)
        item['stamp'], item['time'], item['day'] = _clock(item['created_at'])
        item['actor_key'] = item['uploader_id'] or 'unknown:' + item['id']
        uploads.append(item)
    uploads.sort(key=lambda item: (item['stamp'], item['id']))
    groups = []
    for upload in uploads:
        previous = groups[-1] if groups else None
        if (previous and previous['actor_key'] == upload['actor_key'] and previous['day'] == upload['day']
                and upload['day'] and upload['stamp'] - previous['start'] <= GROUP_SECONDS):
            previous['rows'].append(upload)
        else:
            groups.append({'actor_key': upload['actor_key'], 'start': upload['stamp'], 'day': upload['day'],
                           'anchor': upload['sequence'], 'rows': [upload]})
    events = []
    for group in groups:
        visible, seen = [], set()
        for row in group['rows']:
            if not row['deleted_at'] and row['media_id'] not in seen:
                visible.append(row)
                seen.add(row['media_id'])
        if not visible:
            continue
        latest = group['rows'][-1]
        actor = visible[-1]
        events.append({'type': 'upload', 'anchor': group['anchor'], 'stamp': latest['stamp'],
                       'order': 'u:' + latest['id'], 'created_at': visible[-1]['time'], 'day': group['day'],
                       'actor': {'id': actor['uploader_id'] or '',
                                 'name': actor['current_name'] or actor['uploader_name'] or actor['member_name'] or '家人'},
                       'media_ids': [row['media_id'] for row in visible],
                       'all_media_ids': list(dict.fromkeys(row['media_id'] for row in group['rows'])),
                       'photos': sum(row['kind'] == 'photo' for row in visible),
                       'videos': sum(row['kind'] == 'video' for row in visible)})
    for row in connection.execute("""SELECT comments.*,entry.sequence,members.name AS current_name,media.kind
        FROM comments JOIN activity_feed_entries entry
            ON entry.entry_type='comment' AND entry.source_id=comments.id AND entry.sequence<=?
        JOIN media ON media.id=comments.media_id
        JOIN media_children mc ON mc.media_id=media.id AND mc.child_id=comments.child_id AND mc.deleted_at IS NULL
        LEFT JOIN members ON members.id=comments.member_id WHERE comments.child_id=?""", (snapshot, member['child_id'])):
        stamp, time, day = _clock(row['created_at'])
        events.append({'type': 'comment', 'anchor': row['sequence'], 'stamp': stamp, 'order': 'c:' + row['id'],
                       'created_at': time, 'day': day,
                       'actor': {'id': row['member_id'] or '', 'name': row['current_name'] or row['name'] or '家人'},
                       'media_ids': [row['media_id']], 'photos': int(row['kind'] == 'photo'),
                       'all_media_ids': [row['media_id']],
                       'videos': int(row['kind'] == 'video'), 'comment_row': row})
    return sorted(events, key=lambda event: (event['stamp'], event['order']), reverse=True)


def build_router(database, get_member, media_dto, comment_dto, children=None):
    router = APIRouter()

    def can_respond(member):
        return member['account_role'] in ('admin', 'contributor') and not member.get('child_archived')

    def media_rows(connection, ids, member):
        if not ids:
            return []
        rows = connection.execute('''SELECT media.*,
            (SELECT COUNT(*) FROM comments WHERE media_id=media.id AND child_id=?) AS comments_count,
            EXISTS(SELECT 1 FROM favorites WHERE media_id=media.id AND member_id=?) AS is_favorite
            FROM media WHERE EXISTS(SELECT 1 FROM media_children mc WHERE mc.media_id=media.id
                AND mc.child_id=? AND mc.deleted_at IS NULL) AND id IN (''' + ','.join('?' for _ in ids) + ')',
                                  [member['child_id'], member['id'], member['child_id'], *ids]).fetchall()
        by_id = {row['id']: row for row in rows}
        birthday = connection.execute('SELECT birthday FROM children WHERE id=?', (member['child_id'],)).fetchone()[0]
        return [media_dto(by_id[identity], birthday, member) for identity in ids if identity in by_id]

    def event_dto(connection, event, snapshot, member):
        preview = media_rows(connection, event['media_ids'][:3], member)
        photos, videos = event['photos'], event['videos']
        if event['type'] == 'upload':
            content = ((f'{photos}张照片' if photos else '') + ('和' if photos and videos else '')
                       + (f'{videos}段视频' if videos else ''))
            text = '分享了' + content
        else:
            text = event['comment_row']['text']
        result = {'id': _encode({'v': 1, 'p': 'group', 's': snapshot, 'c': member['child_id'],
                                't': event['type'], 'a': event['anchor']}),
                  'event_key': event['type'] + ':' + str(event['anchor']),
                  'type': event['type'], 'actor': event['actor'], 'created_at': event['created_at'],
                  'day': event['day'], 'text': text, 'media_count': len(event['media_ids']),
                  'photos': photos, 'videos': videos, 'preview': preview,
                  'response_media': preview[0] if preview else None, 'can_respond': can_respond(member)}
        if event['type'] == 'comment':
            result['comment'] = comment_dto(event['comment_row'], member, connection)
        return result

    @router.get('/api/activity-feed')
    def feed(kind: str = 'all', limit: int = 20, cursor: str | None = None, member=Depends(get_member)):
        if kind not in ('all', 'media', 'comment'):
            raise HTTPException(422, '请选择全部动态、照片与视频或留言。')
        limit = max(1, min(limit, 60))
        page = _decode(cursor, 'feed') if cursor else None
        if page and (page.get('c') != member['child_id'] or page.get('k') != kind or not isinstance(page.get('o'), list)
                     or len(page['o']) != 2 or type(page['o'][0]) not in (int, float)
                     or not math.isfinite(page['o'][0])
                     or not isinstance(page['o'][1], str)):
            raise HTTPException(422, '筛选条件已变化，请重新读取家人动态。')
        with database() as connection:
            current = _synchronize(connection)
            snapshot = page['s'] if page else current
            if snapshot > current:
                raise HTTPException(422, '这段浏览位置已失效，请刷新家人动态。')
            events = _events(connection, snapshot, member)
            if kind != 'all':
                events = [event for event in events if event['type'] == ('upload' if kind == 'media' else 'comment')]
            if page:
                events = [event for event in events if (event['stamp'], event['order']) < tuple(page['o'])]
            selected = events[:limit]
            items = [event_dto(connection, event, snapshot, member) for event in selected]
            next_cursor = None
            if len(events) > limit:
                last = selected[-1]
                next_cursor = _encode({'v': 1, 'p': 'feed', 's': snapshot, 'k': kind, 'c': member['child_id'],
                                       'o': [last['stamp'], last['order']]})
        return {'items': items, 'next_cursor': next_cursor, 'can_respond': can_respond(member)}

    @router.get('/api/activity-feed/{group_id}/media')
    def group_media(group_id: str, limit: int = 60, cursor: str | None = None, member=Depends(get_member)):
        group = _decode(group_id, 'group')
        if group.get('c') != member['child_id'] or group.get('t') not in ('upload', 'comment') or type(group.get('a')) is not int:
            raise HTTPException(422, '这组动态已失效，请刷新后再打开。')
        page = _decode(cursor, 'media') if cursor else None
        if page and (page.get('g') != group_id or not isinstance(page.get('last'), str)):
            raise HTTPException(422, '这段浏览位置不属于当前动态，请重新打开。')
        limit = max(1, min(limit, 100))
        with database() as connection:
            current = _synchronize(connection)
            if group['s'] > current:
                raise HTTPException(422, '这组动态已失效，请刷新后再打开。')
            event = next((event for event in _events(connection, group['s'], member)
                          if event['type'] == group['t'] and event['anchor'] == group['a']), None)
            if event is None:
                raise HTTPException(404, '这组照片、视频或留言已不在动态里，请刷新后再看。')
            # Page by immutable media identity inside the snapshot group. Deleted
            # rows are filtered before returning, never exposed through this route.
            ids = event['media_ids']
            remaining = ids
            if page:
                original = event['all_media_ids']
                if page['last'] not in original:
                    raise HTTPException(409, '这组内容刚有变化，请重新打开，继续查看剩下的照片。')
                later = set(original[original.index(page['last']) + 1:])
                remaining = [identity for identity in ids if identity in later]
            selected = remaining[:limit]
            items = media_rows(connection, selected, member)
            next_cursor = (_encode({'v': 1, 'p': 'media', 's': group['s'], 'g': group_id,
                                    'last': selected[-1]}) if len(remaining) > limit else None)
        return {'items': items, 'total': len(ids), 'next_cursor': next_cursor}

    return router
