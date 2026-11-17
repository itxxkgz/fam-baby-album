"""Per-child albums and permissions without duplicating original files."""
from __future__ import annotations

from datetime import datetime, timezone, timedelta
import json
from pathlib import Path
import uuid

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

ROLE_LABELS = {'admin': '宝宝管理员', 'contributor': '可上传', 'viewer': '仅查看'}


def initialize(connection):
    statements = [
        '''CREATE TABLE IF NOT EXISTS children (
            id TEXT PRIMARY KEY,name TEXT NOT NULL,birthday TEXT NOT NULL DEFAULT '',
            avatar_media_id TEXT,archived_at TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)''',
        '''CREATE TABLE IF NOT EXISTS child_members (
            child_id TEXT NOT NULL REFERENCES children(id),member_id TEXT NOT NULL REFERENCES members(id),
            role TEXT NOT NULL CHECK(role IN ('admin','contributor','viewer')),
            PRIMARY KEY(child_id,member_id))''',
        '''CREATE TABLE IF NOT EXISTS child_member_relationships (
            child_id TEXT NOT NULL REFERENCES children(id) ON DELETE CASCADE,
            member_id TEXT NOT NULL REFERENCES members(id) ON DELETE CASCADE,
            relationship TEXT NOT NULL,updated_at TEXT NOT NULL,
            PRIMARY KEY(child_id,member_id))''',
        '''CREATE TABLE IF NOT EXISTS media_children (
            media_id TEXT NOT NULL REFERENCES media(id) ON DELETE CASCADE,
            child_id TEXT NOT NULL REFERENCES children(id),deleted_at TEXT,
            linked_by TEXT REFERENCES members(id),created_at TEXT NOT NULL,
            PRIMARY KEY(media_id,child_id))''',
        'CREATE INDEX IF NOT EXISTS media_child_scope ON media_children(child_id,deleted_at,media_id)',
    ]
    for statement in statements:
        connection.execute(statement)
    for table in ('comments', 'activity'):
        if 'child_id' not in {row[1] for row in connection.execute('PRAGMA table_info(' + table + ')')}:
            connection.execute('ALTER TABLE ' + table + ' ADD COLUMN child_id TEXT REFERENCES children(id)')
    if 'child_ids_json' not in {row[1] for row in connection.execute('PRAGMA table_info(invitations)')}:
        connection.execute("ALTER TABLE invitations ADD COLUMN child_ids_json TEXT NOT NULL DEFAULT '[]'")
        connection.execute('ALTER TABLE invitations ADD COLUMN family_admin INTEGER NOT NULL DEFAULT 0')
    settings = dict(connection.execute('SELECT key,value FROM settings'))
    if settings.get('children_version') == '1':
        return
    identity = settings.get('default_child_id') or uuid.uuid4().hex
    now = datetime.now(timezone(timedelta(hours=8))).isoformat(timespec='seconds')
    connection.execute('INSERT OR IGNORE INTO children VALUES (?,?,?,?,NULL,?,?)',
                       (identity, settings.get('child_name') or '宝宝', settings.get('birthday', ''),
                        settings.get('avatar_media_id') or None, now, now))
    connection.execute("INSERT OR REPLACE INTO settings VALUES ('default_child_id',?)", (identity,))
    connection.execute('''INSERT OR IGNORE INTO media_children(media_id,child_id,deleted_at,linked_by,created_at)
        SELECT id,?,deleted_at,uploader_id,created_at FROM media''', (identity,))
    connection.execute('''INSERT OR IGNORE INTO child_members(child_id,member_id,role)
        SELECT ?,id,account_role FROM members''', (identity,))
    connection.execute('UPDATE comments SET child_id=? WHERE child_id IS NULL', (identity,))
    connection.execute('UPDATE activity SET child_id=? WHERE child_id IS NULL', (identity,))
    # Existing invitations only grant the original child's scope, including old admin invites.
    connection.execute('UPDATE invitations SET child_ids_json=?,family_admin=0', (json.dumps([identity]),))
    connection.execute("INSERT OR REPLACE INTO settings VALUES ('children_version','1')")


class ChildCreate(BaseModel):
    name: str = Field(min_length=1, max_length=40)
    birthday: str | None = ''
    avatar_media_id: str | None = None


class ChildEdit(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=40)
    birthday: str | None = None
    avatar_media_id: str | None = None
    archived: bool | None = None


class ChildGrant(BaseModel):
    role: str | None


class ChildRelationship(BaseModel):
    relationship: str = Field(min_length=1, max_length=40)


class MediaChildren(BaseModel):
    child_ids: list[str] = Field(min_length=1, max_length=100)


class ChildService:
    def __init__(self, database, data, now_text, age_label):
        self.database, self.data, self.now_text, self.age_label = database, Path(data), now_text, age_label

    @staticmethod
    def family_admin(member):
        return member.get('family_role', member.get('account_role')) == 'admin'

    def scope(self, member, child_id=None, connection=None, allow_empty=False):
        if connection is None:
            with self.database() as db:
                return self.scope(member, child_id, db, allow_empty)
        result = dict(member)
        family_role = result.get('family_role', result['account_role'])
        admin = family_role == 'admin'
        explicit = child_id if child_id is not None else result.get('child_id')
        if explicit:
            row = connection.execute('SELECT * FROM children WHERE id=?', (explicit,)).fetchone()
            grant = connection.execute('SELECT role FROM child_members WHERE child_id=? AND member_id=?',
                                       (explicit, member['id'])).fetchone()
            if row is None or (not admin and (row['archived_at'] or not grant)):
                raise HTTPException(403, '你暂时无法查看这个宝宝的相册，请联系家庭管理员。')
        else:
            default = connection.execute("SELECT value FROM settings WHERE key='default_child_id'").fetchone()[0]
            row = connection.execute('''SELECT c.* FROM children c WHERE c.archived_at IS NULL
                AND (? OR EXISTS(SELECT 1 FROM child_members cm WHERE cm.child_id=c.id AND cm.member_id=?))
                ORDER BY (c.id=?) DESC,c.created_at,c.id LIMIT 1''', (admin, member['id'], default)).fetchone()
            grant = connection.execute('SELECT role FROM child_members WHERE child_id=? AND member_id=?',
                                       (row['id'], member['id'])).fetchone() if row else None
        if row is None and not allow_empty:
            raise HTTPException(403, '还没有可以查看的宝宝相册，请让家人邀请你加入。')
        role = 'admin' if admin else (grant['role'] if grant else 'viewer')
        result.update(family_role=family_role, is_family_admin=admin, account_role=role, child_role=role,
                      child_id=row['id'] if row else None, child_archived=bool(row and row['archived_at']))
        return result

    def birthday(self, connection, member):
        member = self.scope(member, connection=connection)
        return connection.execute('SELECT birthday FROM children WHERE id=?', (member['child_id'],)).fetchone()[0]

    def require_active(self, member):
        if member.get('child_archived'):
            raise HTTPException(409, '这个宝宝的相册已归档，请先恢复使用后再修改。')

    def media_filter(self, member, alias='media', deleted=False):
        member = self.scope(member)
        if alias not in {'media', 'm'}:
            raise ValueError('Unsupported media alias')
        status = '' if deleted is None else (' AND mc.deleted_at IS NOT NULL' if deleted else ' AND mc.deleted_at IS NULL')
        return ('EXISTS(SELECT 1 FROM media_children mc WHERE mc.media_id=' + alias + '.id AND mc.child_id=?' + status + ')',
                [member['child_id']])

    def require_targets(self, connection, member, ids, write=True):
        if not isinstance(ids, list) or not ids or len(ids) > 100 or any(not isinstance(v, str) or len(v) > 100 for v in ids):
            raise HTTPException(422, '请选择要存入的宝宝相册。')
        targets = list(dict.fromkeys(ids))
        for identity in targets:
            scoped = self.scope(member, identity, connection)
            if write:
                self.require_active(scoped)
                if scoped['account_role'] not in ('admin', 'contributor'):
                    raise HTTPException(403, '你没有向所选宝宝相册上传的权限。')
        return targets

    def _permissions(self, connection, row, member):
        grants = connection.execute('''SELECT mc.child_id,c.archived_at,cm.role FROM media_children mc
            JOIN children c ON c.id=mc.child_id LEFT JOIN child_members cm
            ON cm.child_id=mc.child_id AND cm.member_id=? WHERE mc.media_id=?''', (member['id'], row['id'])).fetchall()
        admin = self.family_admin(member)
        def allowed(grant):
            role = 'admin' if admin else grant['role']
            return not grant['archived_at'] and (role == 'admin' or (role == 'contributor' and row['uploader_id'] == member['id']))
        current = next((g for g in grants if g['child_id'] == member['child_id']), None)
        return bool(current and allowed(current)), bool(grants and all(allowed(g) for g in grants)), len(grants)

    def require_media(self, connection, media_id, member, include_deleted=True):
        member = self.scope(member, connection=connection)
        row = connection.execute('''SELECT m.*,mc.deleted_at AS child_deleted_at FROM media m
            JOIN media_children mc ON mc.media_id=m.id WHERE m.id=? AND mc.child_id=?''',
                                 (media_id, member['child_id'])).fetchone()
        if row is None:
            raise HTTPException(404, '这份照片或视频不在当前宝宝的相册中。')
        result = dict(row)
        result['deleted_at'] = result.pop('child_deleted_at')
        local, shared, count = self._permissions(connection, result, member)
        result.update(_can_manage_here=local, _can_edit_shared=shared, shared_count=count, child_id=member['child_id'])
        # Family administrators may inspect an archived album, including its recycle bin.
        readable_trash = self.family_admin(member) or local
        if result['deleted_at'] and (not include_deleted or not readable_trash):
            raise HTTPException(404, '这份照片或视频暂时无法查看。')
        return result

    def require_shared_edit(self, connection, row, member):
        member = self.scope(member, connection=connection)
        self.require_active(member)
        checked = self.require_media(connection, row['id'], member)
        if not checked['_can_edit_shared']:
            raise HTTPException(403, '这份内容由多个宝宝共用，修改时间或说明需要能管理它所属的所有相册。')

    def link_media(self, connection, media_id, ids, member, deleted_at=None):
        ids = self.require_targets(connection, member, ids)
        for identity in ids:
            inserted = connection.execute('''INSERT OR IGNORE INTO media_children(media_id,child_id,deleted_at,linked_by,created_at)
                VALUES (?,?,?,?,?)''', (media_id, identity, deleted_at, member['id'], self.now_text()))
            if inserted.rowcount and deleted_at is None:
                media = connection.execute('SELECT kind FROM media WHERE id=?', (media_id,)).fetchone()
                connection.execute('''INSERT INTO activity(id,media_id,type,member_name,text,created_at,child_id)
                    VALUES (?,?,'upload',?,?,?,?)''', (uuid.uuid4().hex, media_id, member['name'],
                        member['name'] + ('上传了照片' if media['kind'] == 'photo' else '上传了视频'), self.now_text(), identity))
        if deleted_at is None:
            connection.execute('''UPDATE media SET deleted_at=NULL WHERE id=? AND EXISTS(
                SELECT 1 FROM media_children WHERE media_id=? AND deleted_at IS NULL)''', (media_id, media_id))

    def set_deleted(self, connection, media_id, member, deleted_at):
        member = self.scope(member, connection=connection)
        self.require_active(member)
        row = self.require_media(connection, media_id, member)
        if not row['_can_manage_here']:
            raise HTTPException(403, '你只能整理自己上传的内容，请联系这个宝宝的管理员。')
        connection.execute('UPDATE media_children SET deleted_at=? WHERE media_id=? AND child_id=?',
                           (deleted_at, media_id, member['child_id']))
        remaining = connection.execute('SELECT 1 FROM media_children WHERE media_id=? AND deleted_at IS NULL', (media_id,)).fetchone()
        connection.execute('UPDATE media SET deleted_at=?,updated_at=? WHERE id=?',
                           (None if remaining else deleted_at, self.now_text(), media_id))

    def _dto(self, connection, row, member):
        scoped = self.scope(member, row['id'], connection)
        counts = connection.execute('''SELECT COALESCE(SUM(m.kind='photo'),0),COALESCE(SUM(m.kind='video'),0)
            FROM media m JOIN media_children mc ON mc.media_id=m.id WHERE mc.child_id=? AND mc.deleted_at IS NULL''', (row['id'],)).fetchone()
        avatar = row['avatar_media_id']
        usable = avatar and connection.execute('''SELECT 1 FROM media m JOIN media_children mc ON mc.media_id=m.id
            WHERE m.id=? AND mc.child_id=? AND mc.deleted_at IS NULL AND m.kind='photo' ''', (avatar, row['id'])).fetchone()
        default = connection.execute("SELECT value FROM settings WHERE key='default_child_id'").fetchone()[0]
        url = f'/api/media/{avatar}/thumbnail?child_id={row["id"]}' if usable else (
            f'/api/family/avatar?child_id={row["id"]}' if row['id'] == default and (self.data / 'avatar.jpg').is_file() else None)
        return {'id': row['id'], 'name': row['name'], 'birthday': row['birthday'], 'avatar_media_id': avatar,
                'avatar_url': url, 'age': self.age_label(row['birthday'], datetime.now(timezone(timedelta(hours=8))).date()),
                'archived': bool(row['archived_at']), 'role': scoped['account_role'],
                'permissions': {'can_upload': not row['archived_at'] and scoped['account_role'] in ('admin', 'contributor'),
                                'can_manage': not row['archived_at'] and scoped['account_role'] == 'admin'},
                'counts': {'photos': counts[0], 'videos': counts[1]}}

    def list_children(self, member, include_archived=False, selected=None):
        admin = self.family_admin(member)
        with self.database() as db:
            default = db.execute("SELECT value FROM settings WHERE key='default_child_id'").fetchone()[0]
            rows = db.execute('''SELECT c.* FROM children c WHERE (? OR c.archived_at IS NULL)
                AND (? OR EXISTS(SELECT 1 FROM child_members cm WHERE cm.child_id=c.id AND cm.member_id=?))
                ORDER BY (c.id=?) DESC,c.created_at,c.id''', (include_archived and admin, admin, member['id'], default)).fetchall()
            items = [self._dto(db, row, member) for row in rows]
        active = [i for i in items if not i['archived']]
        available = {i['id'] for i in items}
        selected = selected if selected in available else (active[0]['id'] if active else None)
        return {'items': items, 'selected_child_id': selected,
                'default_child_id': default if default in available else (active[0]['id'] if active else None),
                'can_create': admin, 'is_family_admin': admin}

    def profile(self, connection, member):
        scoped = self.scope(member, connection=connection)
        row = connection.execute('SELECT * FROM children WHERE id=?', (scoped['child_id'],)).fetchone()
        child = self._dto(connection, row, scoped)
        size = connection.execute('''SELECT COALESCE(SUM(m.size_bytes),0) FROM media m JOIN media_children mc
            ON mc.media_id=m.id WHERE mc.child_id=? AND mc.deleted_at IS NULL''', (row['id'],)).fetchone()[0]
        member_count = connection.execute('''SELECT COUNT(*) FROM members m
            WHERE COALESCE(m.username,'')<>'' AND COALESCE(m.password_hash,'')<>''
            AND (m.account_role='admin' OR EXISTS(
            SELECT 1 FROM child_members cm WHERE cm.member_id=m.id AND cm.child_id=?))''', (row['id'],)).fetchone()[0]
        family_name = connection.execute("SELECT value FROM settings WHERE key='name'").fetchone()[0]
        counts = dict(child['counts'], members=member_count)
        return {'name': family_name, 'child_name': child['name'], 'child_id': row['id'], 'birthday': child['birthday'],
                'avatar_url': child['avatar_url'], 'avatar_media_id': child['avatar_media_id'], 'age': child['age'],
                'archived': child['archived'], 'counts': counts, 'photos_count': counts['photos'],
                'videos_count': counts['videos'], 'members_count': member_count, 'storage_bytes': size}

    def member_children(self, connection, member_id):
        return [dict(row) for row in connection.execute('''SELECT c.id AS child_id,c.name,cm.role FROM child_members cm
            JOIN children c ON c.id=cm.child_id WHERE cm.member_id=? ORDER BY c.created_at,c.id''', (member_id,))]

    def member_relationship(self, connection, child_id, member):
        row = connection.execute('''SELECT relationship FROM child_member_relationships
            WHERE child_id=? AND member_id=?''', (child_id, member['id'])).fetchone()
        return row['relationship'] if row else member['name']

    def set_member_relationship(self, connection, child_id, member_id, relationship, *, overwrite=True):
        value = relationship.strip()
        if not value or len(value) > 40:
            raise HTTPException(422, '请填写四十字以内的家人称呼，例如妈妈、爷爷或小姨。')
        connection.execute('INSERT OR ' + ('REPLACE' if overwrite else 'IGNORE') +
                           ' INTO child_member_relationships VALUES (?,?,?,?)',
                           (child_id, member_id, value, self.now_text()))

    def child_member_dto(self, connection, child_id, target, actor):
        grant = connection.execute('SELECT role FROM child_members WHERE child_id=? AND member_id=?',
                                   (child_id, target['id'])).fetchone()
        family_admin = target['account_role'] == 'admin'
        role = 'admin' if family_admin else grant['role'] if grant else 'viewer'
        registered = bool(target['username'] and target['password_hash'])
        can_manage = (actor['account_role'] == 'admin' and not actor.get('child_archived')
                      and not actor.get('password_change_required') and registered
                      and (family_admin or grant is not None))
        protected = family_admin or target['id'] == actor['id']
        return {'id': target['id'], 'name': target['name'], 'username': target['username'], 'role': role,
                'role_label': '家庭管理员' if family_admin else ROLE_LABELS[role],
                'is_family_admin': family_admin, 'registered': registered, 'disabled': bool(target['disabled']),
                'relationship': self.member_relationship(connection, child_id, target),
                'can_edit_relationship': bool(can_manage),
                'can_change_role': bool(can_manage and not protected),
                'can_remove': bool(can_manage and not protected)}

    def build_router(self, get_member, require_admin, parse_time):
        router = APIRouter(prefix='/api/children')

        def validate_values(values):
            if 'name' in values:
                values['name'] = (values['name'] or '').strip()
                if not values['name']:
                    raise HTTPException(422, '请填写宝宝的名字。')
            if 'birthday' in values:
                birthday = values['birthday'] or ''
                if birthday:
                    parsed = parse_time(birthday).date()
                    if parsed > datetime.now(timezone(timedelta(hours=8))).date():
                        raise HTTPException(422, '生日不能晚于今天。')
                    birthday = parsed.isoformat()
                values['birthday'] = birthday
            return values

        @router.get('')
        def listing(include_archived: bool = False, member=Depends(get_member)):
            return self.list_children(member, include_archived, member.get('child_id'))

        @router.post('')
        def create(body: ChildCreate, member=Depends(require_admin)):
            values = validate_values(body.model_dump())
            if values.get('avatar_media_id'):
                raise HTTPException(422, '请先创建宝宝相册，再从这个宝宝的照片中选择头像。')
            identity, now = uuid.uuid4().hex, self.now_text()
            with self.database() as db:
                db.execute('INSERT INTO children VALUES (?,?,?,NULL,NULL,?,?)', (identity, values['name'], values['birthday'], now, now))
                return self._dto(db, db.execute('SELECT * FROM children WHERE id=?', (identity,)).fetchone(), member)

        @router.patch('/{child_id}')
        def update(child_id: str, body: ChildEdit, member=Depends(get_member)):
            values = validate_values(body.model_dump(exclude_unset=True))
            with self.database() as db:
                db.execute('BEGIN IMMEDIATE')
                scoped = self.scope(member, child_id, db)
                if scoped['account_role'] != 'admin':
                    raise HTTPException(403, '请让这个宝宝的管理员修改资料。')
                archived = values.pop('archived', None)
                if archived is not None:
                    if not self.family_admin(member):
                        raise HTTPException(403, '只有家庭管理员可以归档或恢复宝宝相册。')
                    if archived and not scoped['child_archived'] and db.execute('SELECT COUNT(*) FROM children WHERE archived_at IS NULL').fetchone()[0] <= 1:
                        raise HTTPException(409, '请至少保留一个正在使用的宝宝相册。')
                    values['archived_at'] = self.now_text() if archived else None
                elif scoped['child_archived']:
                    self.require_active(scoped)
                if values.get('avatar_media_id'):
                    photo = self.require_media(db, values['avatar_media_id'], scoped, include_deleted=False)
                    if photo['kind'] != 'photo':
                        raise HTTPException(422, '请选择这个宝宝相册中的照片作为头像。')
                if values:
                    values['updated_at'] = self.now_text()
                    db.execute('UPDATE children SET ' + ','.join(key + '=?' for key in values) + ' WHERE id=?', [*values.values(), child_id])
                return self._dto(db, db.execute('SELECT * FROM children WHERE id=?', (child_id,)).fetchone(), member)

        @router.get('/{child_id}/members')
        def members(child_id: str, member=Depends(get_member)):
            with self.database() as db:
                scoped = self.scope(member, child_id, db)
                if scoped['account_role'] != 'admin':
                    raise HTTPException(403, '只有这个宝宝的管理员可以管理家人。')
                rows = db.execute('''SELECT m.*,cm.role AS child_role FROM members m LEFT JOIN child_members cm
                    ON cm.member_id=m.id AND cm.child_id=?
                    WHERE COALESCE(m.username,'')<>'' AND COALESCE(m.password_hash,'')<>''
                    AND (m.account_role='admin' OR cm.member_id IS NOT NULL)
                    ORDER BY m.joined_at,m.id''', (child_id,)).fetchall()
                items = [self.child_member_dto(db, child_id, row, scoped) for row in rows]
                child = self._dto(db, db.execute('SELECT * FROM children WHERE id=?', (child_id,)).fetchone(), scoped)
                return {'items': items, 'child': child}

        @router.patch('/{child_id}/members/{member_id}')
        def relationship(child_id: str, member_id: str, body: ChildRelationship, member=Depends(get_member)):
            with self.database() as db:
                db.execute('BEGIN IMMEDIATE')
                scoped = self.scope(member, child_id, db)
                self.require_active(scoped)
                if scoped['account_role'] != 'admin':
                    raise HTTPException(403, '只有这个宝宝的管理员可以修改家人称呼。')
                target = db.execute('SELECT * FROM members WHERE id=?', (member_id,)).fetchone()
                if not target or not target['username'] or not target['password_hash']:
                    raise HTTPException(404, '这位家人尚未加入当前宝宝相册。')
                if target['account_role'] != 'admin' and not db.execute(
                        'SELECT 1 FROM child_members WHERE child_id=? AND member_id=?',
                        (child_id, member_id)).fetchone():
                    raise HTTPException(404, '这位家人尚未加入当前宝宝相册。')
                self.set_member_relationship(db, child_id, member_id, body.relationship)
                return self.child_member_dto(db, child_id, target, scoped)

        @router.put('/{child_id}/members/{member_id}')
        def grant(child_id: str, member_id: str, body: ChildGrant, member=Depends(get_member)):
            if body.role is not None and body.role not in ROLE_LABELS:
                raise HTTPException(422, '请选择管理员、可上传或仅查看权限。')
            with self.database() as db:
                db.execute('BEGIN IMMEDIATE')
                scoped = self.scope(member, child_id, db)
                self.require_active(scoped)
                if scoped['account_role'] != 'admin':
                    raise HTTPException(403, '只有这个宝宝的管理员可以调整权限。')
                target = db.execute('SELECT * FROM members WHERE id=?', (member_id,)).fetchone()
                if not target:
                    raise HTTPException(404, '没有找到这位家人。')
                if target['account_role'] == 'admin':
                    raise HTTPException(409, '家庭管理员可以管理全部宝宝，无需在这里调整。')
                if not self.family_admin(member):
                    if member_id == member['id'] and body.role != 'admin':
                        raise HTTPException(409, '不能移除或降低自己的权限，请让家庭管理员处理。')
                    if not db.execute('SELECT 1 FROM child_members WHERE child_id=? AND member_id=?', (child_id, member_id)).fetchone():
                        raise HTTPException(403, '请通过邀请让这位家人加入宝宝相册。')
                if body.role is None:
                    db.execute('DELETE FROM child_members WHERE child_id=? AND member_id=?', (child_id, member_id))
                    db.execute('DELETE FROM child_member_relationships WHERE child_id=? AND member_id=?',
                               (child_id, member_id))
                    invitations = db.execute('SELECT id,child_ids_json FROM invitations WHERE member_id=? AND used_at IS NULL AND revoked_at IS NULL', (member_id,)).fetchall()
                    for invitation in invitations:
                        if child_id in json.loads(invitation['child_ids_json']):
                            db.execute('UPDATE invitations SET revoked_at=? WHERE id=?', (self.now_text(), invitation['id']))
                else:
                    db.execute('INSERT OR REPLACE INTO child_members VALUES (?,?,?)', (child_id, member_id, body.role))
            return {'message': '宝宝相册的家人权限已更新'}

        return router
