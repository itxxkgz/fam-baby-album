"""Independent family accounts, invitations, and compatible legacy migration."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
import hashlib
import hmac
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import secrets
import sqlite3
import time
import uuid

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field


ZONE = timezone(timedelta(hours=8))
ROLES = {'admin': '管理员', 'contributor': '可上传家人', 'viewer': '仅查看家人'}
INVITATION_CODE_ATTEMPTS = 32
INVITATION_ATTEMPT_WINDOW = 15 * 60
INVITATION_SOURCE_LIMIT = 10
INVITATION_SERVICE_LIMIT = 30
INVITATION_DAILY_LIMIT = 100


def now_text():
    return datetime.now(ZONE).isoformat(timespec='seconds')


def password_digest(password, salt):
    return hashlib.scrypt(password.encode('utf-8'), salt=bytes.fromhex(salt), n=16384, r=8, p=1).hex()


def normalize_username(value):
    value = value.strip().lower()
    if not re.fullmatch(r'[a-z0-9][a-z0-9_.-]{2,31}', value):
        raise HTTPException(422, '账号请使用三到三十二位英文字母、数字、点、横线或下划线。')
    return value


def validate_password(value):
    if len(value) < 8 or len(value) > 200:
        raise HTTPException(422, '密码需要八到二百个字符。')


def validate_role(value):
    if value not in ROLES:
        raise HTTPException(422, '请选择有效的家人权限。')
    return value


def permissions(member):
    member = dict(member)
    role = member['account_role']
    unlocked = not member.get('password_change_required', False)
    has_child = 'child_id' not in member or bool(member.get('child_id'))
    active = has_child and not member.get('child_archived', False)
    family_admin = unlocked and member.get('family_role', role) == 'admin'
    admin = unlocked and active and role == 'admin'
    write = unlocked and active and role in ('admin', 'contributor')
    return {'can_upload': write, 'can_comment': write, 'can_favorite': unlocked and active,
            'can_view_trash': unlocked and has_child and role in ('admin', 'contributor'), 'can_manage_family': family_admin,
            'can_manage_members': family_admin, 'can_manage_storage': family_admin,
            'can_manage_child': admin, 'can_invite_child': admin}


def member_dto(row):
    row = dict(row)
    result = {key: row.get(key) for key in ('id', 'name', 'username', 'joined_at')}
    result.update(role=row['account_role'], role_label=ROLES[row['account_role']],
                  family_role=row.get('family_role', row['account_role']),
                  is_family_admin=row.get('family_role', row['account_role']) == 'admin',
                  child_id=row.get('child_id'),
                  registered=bool(row.get('password_hash') and row.get('username')),
                  disabled=bool(row.get('disabled')), permissions=permissions(row),
                  password_change_required=bool(row.get('password_change_required')))
    if row['account_role'] == 'admin':
        result['role_label'] = '家庭管理员' if result['is_family_admin'] else '宝宝管理员'
    if 'uploads_count' in row:
        result['uploads_count'] = row['uploads_count']
    return result


def can_edit_media(member, row):
    if '_can_edit_shared' in dict(row):
        return bool(row['_can_edit_shared']) and not member.get('child_archived', False)
    return member['account_role'] == 'admin' or (
        member['account_role'] == 'contributor' and row['uploader_id'] == member['id'])


def require_media_owner(member, row):
    if not can_edit_media(member, row):
        raise HTTPException(403, '你只能修改或移除自己上传的内容，请联系管理员处理其他内容。')


def require_media_read(member, row):
    local = dict(row).get('_can_manage_here', can_edit_media(member, row))
    if row['deleted_at'] and not local and member.get('family_role', member['account_role']) != 'admin':
        raise HTTPException(404, '这份照片或视频暂时无法查看。')


class IdentityService:
    def __init__(self, database, data):
        self.database = database
        self.data = Path(data)
        self.setup_file = self.data / 'setup-code.txt'

    def initialize(self, connection, *, new_database=False):
        columns = {row[1] for row in connection.execute('PRAGMA table_info(members)')}
        for name, definition in {
            'username': 'TEXT', 'password_salt': 'TEXT', 'password_hash': 'TEXT',
            'account_role': "TEXT NOT NULL DEFAULT 'contributor'",
            'disabled': 'INTEGER NOT NULL DEFAULT 0', 'registered_at': 'TEXT',
            'password_change_required': 'INTEGER NOT NULL DEFAULT 0',
        }.items():
            if name not in columns:
                connection.execute(f'ALTER TABLE members ADD COLUMN {name} {definition}')
        connection.execute('CREATE UNIQUE INDEX IF NOT EXISTS member_username ON members(username) WHERE username IS NOT NULL')
        connection.execute('''CREATE TABLE IF NOT EXISTS favorites (
            member_id TEXT NOT NULL REFERENCES members(id), media_id TEXT NOT NULL REFERENCES media(id),
            created_at TEXT NOT NULL, PRIMARY KEY(member_id,media_id))''')
        connection.execute('''CREATE TABLE IF NOT EXISTS invitations (
            id TEXT PRIMARY KEY, code_hash TEXT UNIQUE NOT NULL,
            member_id TEXT REFERENCES members(id), name TEXT NOT NULL DEFAULT '', role TEXT NOT NULL,
            expires_at REAL NOT NULL, created_at TEXT NOT NULL, created_by TEXT NOT NULL REFERENCES members(id),
            used_at TEXT, revoked_at TEXT)''')
        connection.execute('''CREATE TABLE IF NOT EXISTS invitation_attempts (
            source_hash TEXT NOT NULL,attempted_at REAL NOT NULL)''')
        connection.execute('CREATE INDEX IF NOT EXISTS invitation_attempt_time ON invitation_attempts(attempted_at)')
        connection.execute('CREATE INDEX IF NOT EXISTS invitation_attempt_source ON invitation_attempts(source_hash,attempted_at)')
        settings = dict(connection.execute('SELECT key,value FROM settings'))
        if settings.get('identity_version') != '2':
            connection.execute('DELETE FROM sessions')
            owner = connection.execute("SELECT * FROM members WHERE role='相册主人' ORDER BY joined_at,id LIMIT 1").fetchone()
            if owner:
                connection.execute("UPDATE members SET account_role='admin' WHERE id=?", (owner['id'],))
                password = os.environ.get('ALBUM_PASSWORD', '')
                salt, digest = settings.get('password_salt'), settings.get('password_hash')
                if password and not (owner['username'] and owner['password_hash']):
                    validate_password(password)
                    salt = secrets.token_hex(16)
                    digest = password_digest(password, salt)
                if salt and digest and not (owner['username'] and owner['password_hash']):
                    connection.execute('''UPDATE members SET username='owner', password_salt=?,password_hash=?,
                        registered_at=? WHERE id=?''', (salt, digest, now_text(), owner['id']))
                self.migrate_favorites(connection, owner['id'])
            connection.execute("DELETE FROM settings WHERE key IN ('password_hash','password_salt')")
            connection.execute("INSERT OR REPLACE INTO settings VALUES ('identity_version','2')")
        # Only a newly created database gets the convenient initial account.
        # Empty, unclaimed, disabled or partially migrated existing albums keep
        # their previous setup path and can never regain a public password.
        if (new_database and not os.environ.get('ALBUM_BOOTSTRAP_TOKEN', '').strip()
                and not connection.execute('SELECT 1 FROM members LIMIT 1').fetchone()
                and not connection.execute('SELECT 1 FROM media LIMIT 1').fetchone()):
            salt = secrets.token_hex(16)
            connection.execute('''INSERT INTO members
                (id,name,role,joined_at,username,password_salt,password_hash,account_role,
                 registered_at,password_change_required)
                VALUES (?,?,?,?,?,?,?,?,?,1)''',
                (uuid.uuid4().hex, '相册管理员', '相册主人', now_text(), 'admin', salt,
                 password_digest('admin', salt), 'admin', now_text()))
        if not self.ready(connection):
            code = os.environ.get('ALBUM_BOOTSTRAP_TOKEN', '').strip()
            if not code and self.setup_file.is_file():
                code = self.setup_file.read_text(encoding='utf-8').strip()
            if not code:
                code = secrets.token_urlsafe(24)
            if len(code) < 8:
                raise RuntimeError('首次设置口令至少需要八个字符。')
            descriptor = os.open(self.setup_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
                stream.write(code)
            connection.execute("INSERT OR REPLACE INTO settings VALUES ('setup_code_hash',?)", (self.code_hash(code),))
        else:
            connection.execute("DELETE FROM settings WHERE key='setup_code_hash'")
            self.setup_file.unlink(missing_ok=True)

    @staticmethod
    def migrate_favorites(connection, member_id):
        connection.execute('''INSERT OR IGNORE INTO favorites(member_id,media_id,created_at)
            SELECT ?,id,? FROM media WHERE favorite=1''', (member_id, now_text()))

    @staticmethod
    def ready(connection):
        return bool(connection.execute("SELECT 1 FROM members WHERE account_role='admin' AND disabled=0 AND password_hash IS NOT NULL AND username IS NOT NULL LIMIT 1").fetchone())

    @staticmethod
    def code_hash(value):
        return hashlib.sha256(value.strip().encode('utf-8')).hexdigest()

    def new_invitation_code(self, connection):
        # Called while holding BEGIN IMMEDIATE. Historical codes stay reserved,
        # including used, expired and revoked invitations, so they never revive.
        for _ in range(INVITATION_CODE_ATTEMPTS):
            code = f'{secrets.randbelow(1_000_000):06d}'
            if not connection.execute('SELECT 1 FROM invitations WHERE code_hash=?', (self.code_hash(code),)).fetchone():
                return code
        raise HTTPException(503, '暂时没有生成可用的邀请码，请稍后重试。')

    def limit_invitation_attempts(self, request):
        # Do not use caller-supplied forwarding headers. Only the ASGI peer
        # identity (or the server's explicitly trusted proxy handling) is used.
        source = request.client.host if request.client else 'unknown'
        try:
            source = ipaddress.ip_address(source).compressed
        except ValueError:
            source = source.strip().lower()
        source_hash = hashlib.sha256(('invitation-source:' + source).encode('utf-8')).hexdigest()
        now = time.time()
        # A separate committed transaction charges both successful and failed
        # attempts, and serializes limits across workers and process restarts.
        with self.database() as connection:
            connection.execute('BEGIN IMMEDIATE')
            connection.execute('DELETE FROM invitation_attempts WHERE attempted_at<=?', (now - 86400,))
            rows = connection.execute('SELECT source_hash,attempted_at FROM invitation_attempts ORDER BY attempted_at').fetchall()
            recent = [row['attempted_at'] for row in rows if row['attempted_at'] > now - INVITATION_ATTEMPT_WINDOW]
            local = [row['attempted_at'] for row in rows if row['source_hash'] == source_hash
                     and row['attempted_at'] > now - INVITATION_ATTEMPT_WINDOW]
            daily = [row['attempted_at'] for row in rows]
            retry = 0
            for times, limit, window in ((local, INVITATION_SOURCE_LIMIT, INVITATION_ATTEMPT_WINDOW),
                                         (recent, INVITATION_SERVICE_LIMIT, INVITATION_ATTEMPT_WINDOW),
                                         (daily, INVITATION_DAILY_LIMIT, 86400)):
                if len(times) >= limit:
                    retry = max(retry, math.ceil(times[-limit] + window - now))
            if retry > 0:
                raise HTTPException(429, '邀请码尝试太频繁，请稍后再试。', headers={'Retry-After': str(retry)})
            connection.execute('INSERT INTO invitation_attempts(source_hash,attempted_at) VALUES (?,?)', (source_hash, now))

    def issue_session(self, connection, member):
        token = secrets.token_urlsafe(36)
        expiry = time.time() + 30 * 86400
        connection.execute('DELETE FROM sessions WHERE expires_at<=?', (time.time(),))
        connection.execute('INSERT INTO sessions VALUES (?,?,?)', (hashlib.sha256(token.encode()).hexdigest(), member['id'], expiry))
        scoped = self.children.scope(dict(member), connection=connection, allow_empty=True) if hasattr(self, 'children') else member
        identity = member_dto(scoped)
        return {'token': token, 'expires_at': datetime.fromtimestamp(expiry, ZONE).isoformat(),
                'member': identity, 'password_change_required': identity['password_change_required']}

    @staticmethod
    def check_password(member, password):
        salt = member['password_salt'] if member and member['password_salt'] else '00' * 16
        actual = password_digest(password, salt)
        return bool(member and member['password_hash'] and not member['disabled'] and
                    hmac.compare_digest(actual, member['password_hash']))

    def authenticate(self, authorization):
        if not authorization or not authorization.startswith('Bearer '):
            raise HTTPException(401, '请先登录你的家庭相册。')
        digest = hashlib.sha256(authorization[7:].encode()).hexdigest()
        with self.database() as connection:
            row = connection.execute('''SELECT members.* FROM sessions JOIN members
                ON members.id=sessions.member_id WHERE token_hash=? AND expires_at>?
                AND members.disabled=0 AND members.username IS NOT NULL AND members.password_hash IS NOT NULL''',
                (digest, time.time())).fetchone()
        if row is None:
            raise HTTPException(401, '登录已失效，请使用自己的账号和密码重新登录。')
        return dict(row)

    @staticmethod
    def register(connection, username, password, name, role, member_id=None):
        username = normalize_username(username)
        validate_password(password)
        name = name.strip()
        if not name or len(name) > 40:
            raise HTTPException(422, '请填写四十字以内的家人称呼。')
        if connection.execute('SELECT 1 FROM members WHERE username=?', (username,)).fetchone():
            raise HTTPException(409, '这个账号已经有人使用，请换一个。')
        salt = secrets.token_hex(16)
        digest = password_digest(password, salt)
        if member_id:
            current = connection.execute('SELECT * FROM members WHERE id=?', (member_id,)).fetchone()
            if current is None or current['username'] or current['password_hash']:
                raise HTTPException(409, '这位家人已经完成注册，请联系管理员重新邀请。')
            connection.execute('''UPDATE members SET username=?,password_salt=?,password_hash=?,account_role=?,
                registered_at=?,disabled=0 WHERE id=?''', (username, salt, digest, role, now_text(), member_id))
        else:
            member_id = uuid.uuid4().hex
            connection.execute('''INSERT INTO members
                (id,name,role,joined_at,username,password_salt,password_hash,account_role,registered_at)
                VALUES (?,?,?,?,?,?,?,?,?)''', (member_id, name, '家人', now_text(), username, salt, digest, role, now_text()))
        return connection.execute('SELECT * FROM members WHERE id=?', (member_id,)).fetchone()


class LoginBody(BaseModel):
    username: str = Field(min_length=1, max_length=100)
    password: str = Field(min_length=1, max_length=200)


class SetupBody(LoginBody):
    setup_code: str = Field(min_length=1, max_length=200)
    name: str = Field(min_length=1, max_length=40)
    child_name: str = Field(default='宝宝', min_length=1, max_length=40)
    birthday: str | None = None


class PasswordBody(BaseModel):
    current_password: str = Field(min_length=1, max_length=200)
    new_password: str = Field(min_length=8, max_length=200)


class UsernameBody(BaseModel):
    current_password: str = Field(min_length=1, max_length=200)
    new_username: str = Field(min_length=1, max_length=100)


class MemberEdit(BaseModel):
    role: str | None = None
    family_role: str | None = None
    disabled: bool | None = None


class InviteBody(BaseModel):
    role: str = 'viewer'
    member_id: str | None = None
    name: str = Field(default='', max_length=40)
    expires_hours: int = Field(default=24, ge=1, le=168)
    child_ids: list[str] | None = Field(default=None, min_length=1, max_length=100)


class AcceptBody(LoginBody):
    code: str = Field(min_length=1, max_length=200)
    name: str = Field(default='', max_length=40)


class JoinBody(BaseModel):
    code: str = Field(min_length=1, max_length=200)


def invitation_dto(row, connection=None):
    row = dict(row)
    state = 'used' if row['used_at'] else 'revoked' if row['revoked_at'] else 'expired' if row['expires_at'] <= time.time() else 'active'
    child_ids = json.loads(row.get('child_ids_json') or '[]')
    names = []
    if connection is not None:
        for identity in child_ids:
            child = connection.execute('SELECT name FROM children WHERE id=?', (identity,)).fetchone()
            if child:
                names.append({'id': identity, 'name': child['name']})
    return {'id': row['id'], 'member_id': row['member_id'], 'name': row.get('member_name') or row['name'] or '新家人',
            'role': row['role'], 'role_label': ('家庭管理员' if row.get('family_admin') else '宝宝管理员') if row['role'] == 'admin' else ROLES[row['role']],
            'child_ids': child_ids, 'children': names, 'child_names': [child['name'] for child in names],
            'is_family_admin': bool(row.get('family_admin')),
            'expires_at': datetime.fromtimestamp(row['expires_at'], ZONE).isoformat(),
            'status': state, 'status_label': {'used': '已使用', 'revoked': '已撤销', 'expired': '已过期', 'active': '等待加入'}[state]}


def build_router(service, get_member, require_admin, profile_data, parse_time, attempt):
    router = APIRouter(prefix='/api')

    def logged(connection, member):
        result = service.issue_session(connection, member)
        if not result['password_change_required']:
            scoped = service.children.scope(dict(member), connection=connection, allow_empty=True)
            if scoped.get('child_id'):
                result['profile'] = result['family'] = profile_data(connection, scoped)
            result['children'] = [service.children._dto(connection, row, scoped) for row in connection.execute('''SELECT c.* FROM children c
                WHERE c.archived_at IS NULL AND (? OR EXISTS(SELECT 1 FROM child_members cm WHERE cm.child_id=c.id AND cm.member_id=?))
                ORDER BY c.created_at,c.id''', (service.children.family_admin(scoped), scoped['id']))]
        return result

    @router.get('/status')
    def status():
        with service.database() as connection:
            ready = service.ready(connection)
            default_credentials = bool(connection.execute('''SELECT 1 FROM members
                WHERE username='admin' AND password_change_required=1 AND disabled=0 LIMIT 1''').fetchone())
        return {'ready': ready, 'setup_required': not ready, 'app_name': 'FAM宝贝相册',
                'default_credentials': default_credentials,
                'message': ('请用初始账号登录，并先设置自己的密码' if default_credentials else
                            '相册已准备好' if ready else '请先创建家庭管理员账号')}

    @router.post('/setup')
    def setup(body: SetupBody, request: Request):
        attempt(request)
        with service.database() as connection:
            connection.execute('BEGIN IMMEDIATE')
            if service.ready(connection):
                raise HTTPException(409, '相册已经完成设置，请直接登录。')
            code = connection.execute("SELECT value FROM settings WHERE key='setup_code_hash'").fetchone()
            if code is None or not hmac.compare_digest(code[0], service.code_hash(body.setup_code)):
                raise HTTPException(401, '首次设置口令不正确，请查看服务器上的连接说明。')
            owner = connection.execute("SELECT * FROM members WHERE role='相册主人' AND username IS NULL ORDER BY joined_at,id LIMIT 1").fetchone()
            row = service.register(connection, body.username, body.password, owner['name'] if owner else body.name,
                                   'admin', owner['id'] if owner else None)
            if owner:
                service.migrate_favorites(connection, owner['id'])
            else:
                if not body.child_name.strip():
                    raise HTTPException(422, '请填写宝宝的名字。')
                connection.execute("UPDATE settings SET value=? WHERE key='child_name'", (body.child_name.strip(),))
                connection.execute('UPDATE children SET name=? WHERE id=(SELECT value FROM settings WHERE key=?)',
                                   (body.child_name.strip(), 'default_child_id'))
                if body.birthday:
                    birthday = parse_time(body.birthday).date()
                    if birthday > datetime.now(ZONE).date():
                        raise HTTPException(422, '生日不能晚于今天。')
                    connection.execute("UPDATE settings SET value=? WHERE key='birthday'", (birthday.isoformat(),))
                    connection.execute('UPDATE children SET birthday=? WHERE id=(SELECT value FROM settings WHERE key=?)',
                                       (birthday.isoformat(), 'default_child_id'))
            connection.execute("DELETE FROM settings WHERE key='setup_code_hash'")
            result = logged(connection, row)
        service.setup_file.unlink(missing_ok=True)
        return result

    @router.post('/session')
    @router.post('/login')
    def login(body: LoginBody, request: Request):
        attempt(request)
        username = normalize_username(body.username)
        with service.database() as connection:
            connection.execute('BEGIN IMMEDIATE')
            if not service.ready(connection):
                raise HTTPException(409, '相册还未完成首次设置，请先创建管理员账号。')
            row = connection.execute('SELECT * FROM members WHERE username=?', (username,)).fetchone()
            if not service.check_password(row, body.password):
                raise HTTPException(401, '账号或密码不正确，或该账号暂时无法使用。')
            return logged(connection, row)

    @router.get('/me')
    def me(member=Depends(get_member)):
        return member_dto(member)

    @router.post('/me/password')
    def change_password(body: PasswordBody, member=Depends(get_member)):
        with service.database() as connection:
            connection.execute('BEGIN IMMEDIATE')
            current = connection.execute('SELECT * FROM members WHERE id=?', (member['id'],)).fetchone()
            if not service.check_password(current, body.current_password):
                raise HTTPException(401, '原密码不正确，请重新输入。')
            validate_password(body.new_password)
            if service.check_password(current, body.new_password):
                raise HTTPException(422, '新密码不能与当前密码相同，请设置一个新的密码。')
            salt = secrets.token_hex(16)
            connection.execute('UPDATE members SET password_salt=?,password_hash=?,password_change_required=0 WHERE id=?',
                               (salt, password_digest(body.new_password, salt), member['id']))
            connection.execute('DELETE FROM sessions WHERE member_id=?', (member['id'],))
            current = connection.execute('SELECT * FROM members WHERE id=?', (member['id'],)).fetchone()
            return logged(connection, current)

    @router.post('/me/username')
    def change_username(body: UsernameBody, member=Depends(get_member)):
        username = normalize_username(body.new_username)
        with service.database() as connection:
            connection.execute('BEGIN IMMEDIATE')
            current = connection.execute('SELECT * FROM members WHERE id=?', (member['id'],)).fetchone()
            if not service.check_password(current, body.current_password):
                raise HTTPException(401, '当前密码不正确，请重新输入。')
            if current['password_change_required']:
                raise HTTPException(403, '请先修改初始密码，再修改用户名。')
            if username == current['username']:
                raise HTTPException(422, '新用户名与当前用户名相同，请填写新的用户名。')
            if connection.execute('SELECT 1 FROM members WHERE username=?', (username,)).fetchone():
                raise HTTPException(409, '这个用户名已经有人使用，请换一个。')
            connection.execute('UPDATE members SET username=? WHERE id=?', (username, member['id']))
            connection.execute('DELETE FROM sessions WHERE member_id=?', (member['id'],))
            current = connection.execute('SELECT * FROM members WHERE id=?', (member['id'],)).fetchone()
            return logged(connection, current)

    @router.patch('/members/{member_id}')
    def edit_member(member_id: str, body: MemberEdit, member=Depends(require_admin)):
        if body.role is not None and body.family_role is not None:
            raise HTTPException(422, '请分别修改家庭身份和宝宝相册权限。')
        with service.database() as connection:
            connection.execute('BEGIN IMMEDIATE')
            target = connection.execute('SELECT * FROM members WHERE id=?', (member_id,)).fetchone()
            if target is None:
                raise HTTPException(404, '没有找到这位家人。')
            requested_role = body.family_role if body.family_role is not None else body.role
            role = validate_role(requested_role) if requested_role is not None else target['account_role']
            disabled = bool(target['disabled']) if body.disabled is None else body.disabled
            if member_id == member['id'] and (disabled or role != 'admin'):
                raise HTTPException(409, '不能关闭或降低自己的管理员权限，请由另一位管理员处理。')
            if not disabled and role == 'admin' and not target['username']:
                raise HTTPException(409, '请先邀请这位家人完成注册，再设为管理员。')
            if target['account_role'] == 'admin' and (disabled or role != 'admin'):
                others = connection.execute("SELECT COUNT(*) FROM members WHERE account_role='admin' AND disabled=0 AND username IS NOT NULL AND id<>?", (member_id,)).fetchone()[0]
                if not others:
                    raise HTTPException(409, '相册至少需要保留一位可以登录的管理员。')
            connection.execute('UPDATE members SET account_role=?,disabled=? WHERE id=?', (role, int(disabled), member_id))
            # Legacy callers use this route to change the visible album's role.
            # Keep that meaning while leaving grants for other children alone.
            if body.role is not None and member.get('child_id'):
                connection.execute('INSERT OR REPLACE INTO child_members(child_id,member_id,role) VALUES (?,?,?)',
                                   (member['child_id'], member_id, role))
            if disabled:
                connection.execute('DELETE FROM sessions WHERE member_id=?', (member_id,))
                connection.execute('UPDATE invitations SET revoked_at=? WHERE member_id=? AND used_at IS NULL', (now_text(), member_id))
            return member_dto(connection.execute('SELECT * FROM members WHERE id=?', (member_id,)).fetchone())

    @router.get('/invitations')
    def invitations(member=Depends(get_member)):
        with service.database() as connection:
            rows = connection.execute('''SELECT invitations.*,members.name AS member_name FROM invitations
                LEFT JOIN members ON members.id=invitations.member_id ORDER BY invitations.created_at DESC,id DESC''').fetchall()
            admin = service.children.family_admin(member)
            manageable = {row[0] for row in connection.execute('''SELECT cm.child_id FROM child_members cm JOIN children c ON c.id=cm.child_id
                WHERE cm.member_id=? AND cm.role='admin' AND c.archived_at IS NULL''', (member['id'],))}
            if not admin and not manageable:
                raise HTTPException(403, '请让宝宝的管理员查看和管理邀请。')
            return {'items': [invitation_dto(row, connection) for row in rows if admin or
                             (not row['family_admin'] and set(json.loads(row['child_ids_json'])).issubset(manageable))]}

    @router.post('/invitations')
    def create_invitation(body: InviteBody, request: Request, member=Depends(get_member)):
        role = validate_role(body.role)
        invite_id = uuid.uuid4().hex
        with service.database() as connection:
            connection.execute('BEGIN IMMEDIATE')
            family_admin = service.children.family_admin(member)
            if body.child_ids is None:
                # Keep the explicitly requested legacy family-admin invitation.
                # The simple name-only path always grants viewing in the selected baby.
                if role == 'admin' and not family_admin:
                    raise HTTPException(403, '请先选择要邀请家人加入的宝宝相册。')
                selected = service.children.scope(member, request.headers.get('x-fam-child'), connection)
                child_ids = [selected['child_id']]
            else:
                child_ids = service.children.require_targets(connection, member, body.child_ids)
            for child_id in child_ids:
                scoped = service.children.scope(member, child_id, connection)
                service.children.require_active(scoped)
                if scoped['account_role'] != 'admin':
                    raise HTTPException(403, '只有宝宝管理员可以为这个相册邀请家人。')
            name = body.name.strip()
            # Old clients omitted the name and asked the recipient to enter it.
            # Explicitly supplied blank names must never become anonymous invites.
            if 'name' in body.model_fields_set and not name:
                raise HTTPException(422, '请填写家人称呼，例如妈妈、爷爷或小姨。')
            if body.member_id:
                target = connection.execute('SELECT * FROM members WHERE id=?', (body.member_id,)).fetchone()
                if target is None or target['username'] or target['password_hash'] or target['disabled']:
                    raise HTTPException(409, '请选择尚未注册且未被停用的原相册家人。')
                if not family_admin and not any(connection.execute('SELECT 1 FROM child_members WHERE child_id=? AND member_id=?',
                                                                   (child_id, body.member_id)).fetchone() for child_id in child_ids):
                    raise HTTPException(403, '请为这个宝宝相册中的家人创建邀请。')
                name = target['name']
                connection.execute('UPDATE invitations SET revoked_at=? WHERE member_id=? AND used_at IS NULL AND revoked_at IS NULL',
                                   (now_text(), body.member_id))
            code = service.new_invitation_code(connection)
            connection.execute('''INSERT INTO invitations(id,code_hash,member_id,name,role,expires_at,created_at,created_by,child_ids_json,family_admin)
                VALUES (?,?,?,?,?,?,?,?,?,?)''', (invite_id, service.code_hash(code), body.member_id, name, role,
                                            time.time() + body.expires_hours * 3600, now_text(), member['id'],
                                            json.dumps(child_ids), int(body.child_ids is None and role == 'admin')))
            result = invitation_dto(connection.execute('SELECT * FROM invitations WHERE id=?', (invite_id,)).fetchone(), connection)
        result['code'] = code
        return result

    @router.delete('/invitations/{invitation_id}')
    def revoke_invitation(invitation_id: str, member=Depends(get_member)):
        with service.database() as connection:
            row = connection.execute('SELECT * FROM invitations WHERE id=?', (invitation_id,)).fetchone()
            if row is None:
                raise HTTPException(404, '没有找到这份邀请。')
            if not service.children.family_admin(member):
                if row['family_admin']:
                    raise HTTPException(403, '只有家庭管理员可以撤销这份邀请。')
                for child_id in json.loads(row['child_ids_json']):
                    scoped = service.children.scope(member, child_id, connection)
                    service.children.require_active(scoped)
                    if scoped['account_role'] != 'admin':
                        raise HTTPException(403, '你不能管理这份邀请。')
            connection.execute('UPDATE invitations SET revoked_at=COALESCE(revoked_at,?) WHERE id=? AND used_at IS NULL',
                               (now_text(), invitation_id))
        return {'message': '邀请已撤销，原邀请码无法再加入相册'}

    @router.post('/invitations/accept')
    def accept_invitation(body: AcceptBody, request: Request):
        service.limit_invitation_attempts(request)
        with service.database() as connection:
            connection.execute('BEGIN IMMEDIATE')
            invite = connection.execute('SELECT * FROM invitations WHERE code_hash=?', (service.code_hash(body.code),)).fetchone()
            if invite is None or invite['used_at'] or invite['revoked_at'] or invite['expires_at'] <= time.time():
                raise HTTPException(401, '邀请码已失效，请让管理员重新邀请。')
            child_ids = validate_invite(connection, invite)
            if invite['member_id']:
                target = connection.execute('SELECT * FROM members WHERE id=?', (invite['member_id'],)).fetchone()
                if target is None or target['disabled']:
                    raise HTTPException(401, '这份邀请暂时无法使用，请联系管理员。')
                name = target['name']
            else:
                name = invite['name'] or body.name
            account_role = 'admin' if invite['family_admin'] else ('viewer' if invite['role'] == 'viewer' else 'contributor')
            row = service.register(connection, body.username, body.password, name, account_role, invite['member_id'])
            for child_id in child_ids:
                connection.execute('INSERT OR REPLACE INTO child_members VALUES (?,?,?)', (child_id, row['id'], invite['role']))
                service.children.set_member_relationship(connection, child_id, row['id'], name, overwrite=False)
            connection.execute('UPDATE invitations SET used_at=? WHERE id=?', (now_text(), invite['id']))
            result = logged(connection, row)
            result['joined_children'] = [connection.execute('SELECT name FROM children WHERE id=?', (value,)).fetchone()[0] for value in child_ids]
            return result

    def validate_invite(connection, invite):
        child_ids = json.loads(invite['child_ids_json'])
        creator = connection.execute('SELECT * FROM members WHERE id=? AND disabled=0', (invite['created_by'],)).fetchone()
        if creator is None or not child_ids:
            raise HTTPException(401, '这份邀请暂时无法使用，请让管理员重新邀请。')
        for child_id in child_ids:
            try:
                scoped = service.children.scope(dict(creator), child_id, connection)
                service.children.require_active(scoped)
                if scoped['account_role'] != 'admin':
                    raise HTTPException(403)
            except HTTPException:
                raise HTTPException(401, '邀请中的相册或权限已变化，请让管理员重新邀请。') from None
        if invite['family_admin'] and creator['account_role'] != 'admin':
            raise HTTPException(401, '这份家庭管理员邀请已失效，请重新邀请。')
        return child_ids

    @router.post('/invitations/join')
    def join_invitation(body: JoinBody, request: Request, member=Depends(get_member)):
        service.limit_invitation_attempts(request)
        with service.database() as connection:
            connection.execute('BEGIN IMMEDIATE')
            invite = connection.execute('SELECT * FROM invitations WHERE code_hash=?', (service.code_hash(body.code),)).fetchone()
            if invite is None or invite['used_at'] or invite['revoked_at'] or invite['expires_at'] <= time.time():
                raise HTTPException(401, '邀请码已失效，请让管理员重新邀请。')
            if invite['member_id'] and invite['member_id'] != member['id']:
                raise HTTPException(403, '这份邀请已关联另一位家人，请让管理员重新邀请。')
            child_ids = validate_invite(connection, invite)
            for child_id in child_ids:
                existing = connection.execute('SELECT role FROM child_members WHERE child_id=? AND member_id=?', (child_id, member['id'])).fetchone()
                order = {'viewer': 0, 'contributor': 1, 'admin': 2}
                role = existing['role'] if existing and order[existing['role']] > order[invite['role']] else invite['role']
                connection.execute('INSERT OR REPLACE INTO child_members VALUES (?,?,?)', (child_id, member['id'], role))
                # Joining another baby's album may use a different relationship.
                # Accepting a second invitation never renames an existing membership.
                if not existing and not service.children.family_admin(member) and invite['name'].strip():
                    service.children.set_member_relationship(connection, child_id, member['id'], invite['name'], overwrite=False)
            if invite['family_admin']:
                connection.execute("UPDATE members SET account_role='admin' WHERE id=?", (member['id'],))
            connection.execute('UPDATE invitations SET used_at=? WHERE id=?', (now_text(), invite['id']))
            updated = connection.execute('SELECT * FROM members WHERE id=?', (member['id'],)).fetchone()
            result = logged(connection, updated)
            result['joined_children'] = [connection.execute('SELECT name FROM children WHERE id=?', (value,)).fetchone()[0] for value in child_ids]
            return result

    return router
