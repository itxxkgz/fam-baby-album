"""A private, single-family photo library for the Android client."""

from __future__ import annotations

import base64
import calendar
from collections import defaultdict, deque
from contextlib import contextmanager, asynccontextmanager
from datetime import date, datetime, timedelta, timezone
import hashlib
import hmac
import json
import math
import mimetypes
import os
from pathlib import Path
import re
import secrets
import shutil
import sqlite3
import subprocess
import threading
import time
from urllib.parse import quote
from types import SimpleNamespace
import uuid

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse, Response, StreamingResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool
from PIL import Image, ImageOps, UnidentifiedImageError

from media_inspection import inspect_media, preserve_file_time, _ffmpeg_path
from identity import (IdentityService, member_dto, can_edit_media, require_media_owner,
                      require_media_read, build_router as identity_router)
from storage import StorageManager, StorageError
from storage_api import build_router as storage_router
from memories import initialize as initialize_memories, build_router as memories_router, full_years
from activity_feed import (initialize as initialize_activity_feed, build_router as activity_feed_router,
                           create_comment, reply_for)
from admin_api import cookie_authorization, validate_web_csrf, build_router as admin_router
from children import ChildService, MediaChildren, initialize as initialize_children


LOCAL_TIMEZONE = timezone(timedelta(hours=8))
DATA = Path(os.environ.get('ALBUM_DATA', os.environ.get('ALBUM_DATA_DIR', './data'))).resolve()
LIBRARY = Path(os.environ.get('ALBUM_LIBRARY', './library')).resolve()
DATABASE = DATA / 'album.sqlite'
MAX_UPLOAD = int(os.environ.get('ALBUM_MAX_UPLOAD_BYTES', str(2 * 1024**3)))
SESSION_DAYS = 30
THUMBNAIL_LOCKS = [threading.Lock() for _ in range(32)]
LOGIN_ATTEMPTS = defaultdict(deque)
LOGIN_LOCK = threading.Lock()


def now_text():
    return datetime.now(LOCAL_TIMEZONE).isoformat(timespec='seconds')


@contextmanager
def database():
    connection = sqlite3.connect(DATABASE, timeout=30)
    connection.row_factory = sqlite3.Row
    connection.execute('PRAGMA foreign_keys=ON')
    try:
        yield connection
        connection.commit()
    except BaseException:
        connection.rollback()
        raise
    finally:
        connection.close()


identities = IdentityService(database, DATA)


def initialize_database():
    DATA.mkdir(parents=True, exist_ok=True)
    for name in ('uploads', 'thumbnails', 'temporary'):
        (DATA / name).mkdir(exist_ok=True)
    new_database_file = not DATABASE.exists()
    with database() as connection:
        # SQLite does not consistently apply busy_timeout while two first-start
        # workers both switch journal modes. Retry only that transient lock.
        journal_deadline = time.monotonic() + 30
        while True:
            try:
                if connection.execute('PRAGMA journal_mode').fetchone()[0].lower() != 'wal':
                    connection.execute('PRAGMA journal_mode=WAL')
                break
            except sqlite3.OperationalError as error:
                if (getattr(error, 'sqlite_errorcode', 0) & 0xff) not in {
                        sqlite3.SQLITE_BUSY, sqlite3.SQLITE_LOCKED} or time.monotonic() >= journal_deadline:
                    raise
                time.sleep(0.05)
        connection.execute('BEGIN IMMEDIATE')
        new_database = new_database_file and not connection.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' LIMIT 1").fetchone()
        schema = '''
            CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS members (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, role TEXT NOT NULL DEFAULT '家人',
                joined_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS sessions (
                token_hash TEXT PRIMARY KEY, member_id TEXT NOT NULL REFERENCES members(id),
                expires_at REAL NOT NULL
            );
            CREATE TABLE IF NOT EXISTS media (
                id TEXT PRIMARY KEY, source_id TEXT UNIQUE, kind TEXT NOT NULL,
                root_kind TEXT NOT NULL, relative_path TEXT NOT NULL,
                filename TEXT NOT NULL, mime_type TEXT NOT NULL,
                size_bytes INTEGER NOT NULL, sha256 TEXT NOT NULL,
                width INTEGER, height INTEGER, duration REAL,
                captured_at TEXT NOT NULL, album_date TEXT NOT NULL, sort_time REAL NOT NULL,
                description TEXT NOT NULL DEFAULT '', uploader_id TEXT REFERENCES members(id),
                uploader_name TEXT NOT NULL, favorite INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL, updated_at TEXT NOT NULL, deleted_at TEXT,
                metadata_json TEXT NOT NULL DEFAULT '{}'
            );
            CREATE INDEX IF NOT EXISTS media_order ON media(deleted_at, album_date DESC, sort_time DESC, id DESC);
            CREATE INDEX IF NOT EXISTS media_browse ON media(deleted_at, album_date DESC, sort_time ASC, id ASC);
            CREATE INDEX IF NOT EXISTS media_kind ON media(kind, deleted_at, album_date DESC);
            CREATE TABLE IF NOT EXISTS comments (
                id TEXT PRIMARY KEY, media_id TEXT NOT NULL REFERENCES media(id),
                member_id TEXT REFERENCES members(id), name TEXT NOT NULL,
                text TEXT NOT NULL, created_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS comments_media ON comments(media_id, created_at);
            CREATE TABLE IF NOT EXISTS activity (
                id TEXT PRIMARY KEY, media_id TEXT REFERENCES media(id),
                type TEXT NOT NULL, member_name TEXT NOT NULL, text TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS activity_time ON activity(created_at DESC, id DESC);
        '''
        for statement in schema.split(';'):
            if statement.strip():
                connection.execute(statement)
        defaults = {'name': 'FAM宝贝相册', 'child_name': '宝宝', 'birthday': '', 'avatar_media_id': ''}
        for key, value in defaults.items():
            connection.execute('INSERT OR IGNORE INTO settings VALUES (?,?)', (key, value))
        identities.initialize(connection, new_database=new_database)
        initialize_children(connection)
        initialize_memories(connection)
        initialize_activity_feed(connection)


initialize_database()
storage_manager = StorageManager(DATA, LIBRARY, database)
storage_manager.initialize()
app = FastAPI(title='FAM宝贝相册', docs_url=None, redoc_url=None, openapi_url=None)


@app.exception_handler(RequestValidationError)
async def validation_error(request, exception):
    return JSONResponse({'detail': '填写的内容不完整或格式不正确，请检查后重试。'}, status_code=422)


@app.exception_handler(sqlite3.OperationalError)
async def database_error(request, exception):
    return JSONResponse({'detail': '相册暂时繁忙，请稍后重试。'}, status_code=503)


@app.exception_handler(OSError)
async def file_error(request, exception):
    return JSONResponse({'detail': '文件暂时无法读取或保存，请检查服务器存储。'}, status_code=503)


@app.exception_handler(StorageError)
async def storage_error(request, exception):
    return JSONResponse({'detail': exception.message}, status_code=exception.status_code)


@app.exception_handler(Exception)
async def unexpected_error(request, exception):
    return JSONResponse({'detail': '相册服务暂时遇到问题，请稍后重试。'}, status_code=500)


def authenticate(authorization):
    return identities.authenticate(authorization)


def get_member(request: Request, authorization: str | None = Header(default=None)):
    return getattr(request.state, 'member', None) or children.scope(authenticate(authorization))


def child_scope(member):
    return children.scope(member)


def child_birthday(connection, member):
    return children.birthday(connection, member)


def child_media_filter(member, alias='media', deleted=False):
    return children.media_filter(member, alias, deleted)


def effective_max_upload():
    with database() as connection:
        row = connection.execute("SELECT value FROM settings WHERE key='admin_max_upload_bytes'").fetchone()
    try:
        value = int(row[0]) if row else MAX_UPLOAD
        return min(MAX_UPLOAD, max(1024 * 1024, value))
    except (ValueError, TypeError):
        return MAX_UPLOAD


def require_admin(member=Depends(get_member)):
    if not children.family_admin(member):
        raise HTTPException(403, '这项设置需要管理员操作。')
    return member


def require_contributor(member=Depends(get_member)):
    children.require_active(member)
    if member['account_role'] not in {'admin', 'contributor'}:
        raise HTTPException(403, '你目前只能查看相册，请联系管理员开通上传和留言权限。')
    return member


@app.middleware('http')
async def authorize_before_upload(request: Request, call_next):
    public = {'/api/status', '/api/login', '/api/session', '/api/setup', '/api/invitations/accept', '/api/admin/session'}
    if request.url.path.startswith('/api/') and request.url.path not in public:
        try:
            authorization = request.headers.get('authorization')
            browser_session = not authorization and bool(cookie_authorization(request))
            request.state.member = await run_in_threadpool(authenticate, authorization or cookie_authorization(request))
            if browser_session:
                if not children.family_admin(request.state.member):
                    raise HTTPException(403, '网页后台仅供管理员使用，请用管理员账号登录。')
                if request.method not in {'GET', 'HEAD', 'OPTIONS'}:
                    validate_web_csrf(request)
        except HTTPException as error:
            return JSONResponse({'detail': error.detail}, status_code=error.status_code)
        if request.state.member.get('password_change_required'):
            allowed = {('GET', '/api/me'), ('POST', '/api/me/password'), ('POST', '/api/logout'),
                       ('POST', '/api/admin/account')}
            if (request.method, request.url.path) not in allowed:
                return JSONResponse({'detail': '请先修改初始密码，再使用家庭相册。',
                                     'password_change_required': True}, status_code=403)
        # Child state belongs to each request, never to a shared session or worker thread.
        path = request.url.path
        header_child = request.headers.get('x-fam-child')
        resource_query = path == '/api/family/avatar' or bool(re.fullmatch(r'/api/media/[^/]+/(file|thumbnail)', path))
        query_child = request.query_params.get('child_id') if resource_query else None
        if header_child and query_child and header_child != query_child:
            return JSONResponse({'detail': '宝宝相册发生变化，请重新打开这份内容。'}, status_code=400)
        chosen = query_child or header_child
        global_request = (path in {'/api/me', '/api/me/password', '/api/me/username', '/api/logout', '/api/members',
                                   '/api/admin/account', '/api/admin/settings', '/api/admin/records'}
                          or path.startswith(('/api/children', '/api/invitations', '/api/storage', '/api/admin/backups')))
        try:
            try:
                request.state.member = await run_in_threadpool(children.scope, request.state.member, chosen, None, global_request)
            except HTTPException:
                if not global_request:
                    raise
                request.state.member = await run_in_threadpool(children.scope, request.state.member, None, None, True)
        except HTTPException as error:
            return JSONResponse({'detail': error.detail}, status_code=error.status_code)
        upload_request = request.url.path in {'/api/upload', '/api/media'} or (
            request.url.path.startswith('/api/admin/imports/') and request.url.path.endswith('/files'))
        if request.method == 'POST' and upload_request:
            if request.state.member['account_role'] not in {'admin', 'contributor'}:
                return JSONResponse({'detail': '你目前没有上传权限，请联系管理员。'}, status_code=403)
            declared_size = request.headers.get('content-length')
            if declared_size:
                try:
                    if int(declared_size) > effective_max_upload() + 1024 * 1024:
                        return JSONResponse({'detail': '文件较大，超过了服务器设定的单次上传大小。'}, status_code=413)
                except ValueError:
                    return JSONResponse({'detail': '上传请求不完整，请重新选择文件。'}, status_code=400)
    return await call_next(request)


@app.middleware('http')
async def web_response_headers(request: Request, call_next):
    response = await call_next(request)
    response.headers['X-Content-Type-Options'] = 'nosniff'
    response.headers['Referrer-Policy'] = 'no-referrer'
    if request.url.path.startswith('/api/admin'):
        response.headers['Cache-Control'] = 'no-store'
    if request.url.path.startswith('/admin'):
        response.headers['X-Frame-Options'] = 'DENY'
        response.headers['Cache-Control'] = 'no-cache'
        response.headers['Content-Security-Policy'] = (
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            "img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; "
            "object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'")
    return response


def profile_data(connection, member=None):
    if member is None:
        owner = connection.execute("SELECT * FROM members WHERE account_role='admin' AND disabled=0 ORDER BY joined_at,id LIMIT 1").fetchone()
        if owner is None:
            raise HTTPException(403, '请先完成家庭管理员设置。')
        member = dict(owner)
    return children.profile(connection, member)


def age_label(birthday, day):
    if not birthday:
        return '成长记录'
    born = date.fromisoformat(birthday)
    if day < born:
        return '出生前'
    total_months = (day.year - born.year) * 12 + day.month - born.month
    anchor = day.replace(day=min(born.day, calendar.monthrange(day.year, day.month)[1]))
    if anchor > day:
        total_months -= 1
        previous = day.replace(day=1) - timedelta(days=1)
        anchor = previous.replace(day=min(born.day, calendar.monthrange(previous.year, previous.month)[1]))
    years, months = divmod(total_months, 12)
    days = (day - anchor).days
    parts = ([f'{years}岁'] if years else []) + ([f'{months}个月'] if months else []) + ([f'{days}天'] if days else [])
    return ''.join(parts) or '出生当天'


def parse_time(value):
    try:
        text = value.strip()
        if len(text) == 10:
            captured = datetime.combine(date.fromisoformat(text), datetime.min.time(), LOCAL_TIMEZONE)
        else:
            captured = datetime.fromisoformat(text.replace('Z', '+00:00'))
            if captured.tzinfo is None:
                captured = captured.replace(tzinfo=LOCAL_TIMEZONE)
            captured = captured.astimezone(LOCAL_TIMEZONE)
        if captured.year < 1900 or captured.year > 2200:
            raise ValueError()
        return captured
    except (TypeError, ValueError):
        raise HTTPException(422, '日期格式不正确，请重新选择拍摄日期。') from None


children = ChildService(database, DATA, now_text, age_label)
identities.children = children


def get_media(connection, media_id, member=None):
    if member is not None:
        member = children.scope(member, connection=connection)
        row = children.require_media(connection, media_id, member)
        row['comments_count'] = connection.execute('SELECT COUNT(*) FROM comments WHERE media_id=? AND child_id=?',
                                                   (media_id, member['child_id'])).fetchone()[0]
        row['is_favorite'] = bool(connection.execute('SELECT 1 FROM favorites WHERE media_id=? AND member_id=?',
                                                     (media_id, member['id'])).fetchone())
        return row
    row = connection.execute('''SELECT media.*,
        (SELECT COUNT(*) FROM comments WHERE media_id=media.id) AS comments_count,
        EXISTS(SELECT 1 FROM favorites WHERE media_id=media.id AND member_id=?) AS is_favorite
        FROM media WHERE id=?''', (member['id'] if member else '', media_id)).fetchone()
    if row is None:
        raise HTTPException(404, '这份照片或视频不存在。')
    return row


def media_dto(row, birthday='', member=None):
    item = dict(row)
    if member is not None and not (item.get('child_id') == member.get('child_id') and '_can_manage_here' in item):
        member = children.scope(member)
        with database() as connection:
            item = get_media(connection, item['id'], member)
            birthday = child_birthday(connection, member)
    day = date.fromisoformat(item['album_date'])
    result = {key: item.get(key) for key in ('id', 'kind', 'width', 'height', 'duration', 'captured_at', 'sort_time',
        'album_date', 'description', 'uploader_name', 'filename', 'size_bytes', 'created_at', 'updated_at', 'deleted_at')}
    editable = bool(member and can_edit_media(member, item))
    result.update(favorite=bool(item.get('is_favorite')), comments_count=item.get('comments_count', 0),
                  uploader_id=item['uploader_id'], can_edit=editable and not item['deleted_at'],
                  can_delete=bool(item.get('_can_manage_here', editable)) and not item['deleted_at'],
                  can_restore=bool(item.get('_can_manage_here', editable)) and bool(item['deleted_at']),
                  thumbnail_url=f"/api/media/{item['id']}/thumbnail" + (f"?child_id={member['child_id']}" if member else ''),
                  file_url=f"/api/media/{item['id']}/file" + (f"?child_id={member['child_id']}" if member else ''),
                  shared_count=item.get('shared_count', 1),
                  date_label=f'{day.year}年{day.month}月{day.day}日', age=age_label(birthday, day))
    return result


def media_path(row):
    root = LIBRARY if row['root_kind'] == 'library' else DATA / 'uploads'
    path = (root / row['relative_path']).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file():
        raise HTTPException(404, '媒体文件暂时找不到，请检查服务器上的相册目录。')
    return path


def query_filters(kind, month, exact_date, favorite, deleted, member):
    condition, parameters = child_media_filter(member, deleted=deleted)
    conditions = [condition]
    if kind not in ('all', 'photo', 'video'):
        raise HTTPException(422, '请选择照片、视频或全部内容。')
    if kind != 'all':
        conditions.append('kind=?')
        parameters.append(kind)
    if month:
        try:
            if not re.fullmatch(r'\d{4}-\d{2}', month):
                raise ValueError()
            date.fromisoformat(month + '-01')
        except ValueError:
            raise HTTPException(422, '月份格式不正确。') from None
        conditions.append('substr(album_date,1,7)=?')
        parameters.append(month)
    if exact_date:
        day = parse_time(exact_date).date().isoformat()
        conditions.append('album_date=?')
        parameters.append(day)
    if favorite:
        conditions.append('EXISTS(SELECT 1 FROM favorites WHERE media_id=media.id AND member_id=?)')
        parameters.append(member['id'])
    if deleted and member['account_role'] != 'admin':
        if member['account_role'] == 'viewer':
            raise HTTPException(403, '回收站需要上传或管理权限。')
        conditions.append('uploader_id=?')
        parameters.append(member['id'])
    return conditions, parameters


def decode_cursor(cursor):
    try:
        if len(cursor) > 2048:
            raise ValueError()
        result = json.loads(base64.urlsafe_b64decode(cursor + '=' * (-len(cursor) % 4)))
        if (not isinstance(result, list) or len(result) != 3 or not isinstance(result[0], str)
                or isinstance(result[1], bool) or not isinstance(result[1], (int, float))
                or not math.isfinite(result[1]) or not isinstance(result[2], str) or not result[2]):
            raise ValueError()
        date.fromisoformat(result[0])
        return result
    except (ValueError, TypeError, KeyError, OverflowError, json.JSONDecodeError):
        raise HTTPException(422, '加载位置已失效，请刷新相册。') from None


def encode_cursor(row):
    value = [row['album_date'], row['sort_time'], row['id']]
    return base64.urlsafe_b64encode(json.dumps(value).encode()).decode().rstrip('=')


class MediaEdit(BaseModel):
    description: str | None = Field(default=None, max_length=5000)
    captured_at: str | None = None
    favorite: bool | None = None


class ProfileEdit(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=50)
    child_name: str | None = Field(default=None, min_length=1, max_length=40)
    birthday: str | None = None
    avatar_media_id: str | None = None


class CommentBody(BaseModel):
    text: str = Field(min_length=1, max_length=2000)
    reply_to_id: str | None = Field(default=None, min_length=1, max_length=200)
    request_id: str | None = Field(default=None, min_length=8, max_length=100)


def limit_account_attempts(request):
    remote = request.client.host if request.client else 'local'
    with LOGIN_LOCK:
        attempts = LOGIN_ATTEMPTS[remote]
        while attempts and attempts[0] < time.time() - 300:
            attempts.popleft()
        if len(attempts) >= 30:
            raise HTTPException(429, '账号操作次数较多，请五分钟后再试。')
        attempts.append(time.time())


app.include_router(identity_router(identities, get_member, require_admin, profile_data, parse_time, limit_account_attempts))
app.include_router(storage_router(storage_manager, require_admin))
app.include_router(memories_router(database, get_member, media_dto, encode_cursor, decode_cursor, now_text, children=children))
app.include_router(children.build_router(get_member, require_admin, parse_time))


@app.post('/api/logout')
def logout(member=Depends(get_member), authorization: str = Header()):
    with database() as connection:
        connection.execute('DELETE FROM sessions WHERE token_hash=?', (hashlib.sha256(authorization[7:].encode()).hexdigest(),))
    return {'message': '已退出家庭相册'}


@app.get('/api/profile')
@app.get('/api/family')
def family(member=Depends(get_member)):
    with database() as connection:
        return profile_data(connection, member)


@app.get('/api/family/avatar')
def family_avatar(member=Depends(get_member)):
    with database() as connection:
        default = connection.execute("SELECT value FROM settings WHERE key='default_child_id'").fetchone()[0]
        if member['child_id'] != default:
            raise HTTPException(404, '这个宝宝还没有设置头像。')
    target = DATA / 'avatar.jpg'
    if not target.is_file():
        raise HTTPException(404, '还没有设置宝宝头像。')
    return FileResponse(target, media_type='image/jpeg', headers={'Cache-Control': 'private, no-store', 'Vary': 'Cookie, Authorization, X-FAM-Child'})


@app.put('/api/profile')
@app.put('/api/family')
def update_profile(body: ProfileEdit, member=Depends(get_member)):
    children.require_active(member)
    if member['account_role'] != 'admin':
        raise HTTPException(403, '请让这个宝宝的管理员修改资料。')
    values = body.model_dump(exclude_unset=True)
    for key in ('name', 'child_name'):
        if key in values and not (values[key] or '').strip():
            raise HTTPException(422, '请填写相册和宝宝的名字。')
    if 'birthday' in values and values['birthday']:
        born = parse_time(values['birthday']).date()
        if born > datetime.now(LOCAL_TIMEZONE).date():
            raise HTTPException(422, '生日不能晚于今天。')
        values['birthday'] = born.isoformat()
    with database() as connection:
        connection.execute('BEGIN IMMEDIATE')
        if 'name' in values:
            original_name = connection.execute("SELECT value FROM settings WHERE key='name'").fetchone()[0]
            if not children.family_admin(member) and values['name'].strip() != original_name:
                raise HTTPException(403, '家庭相册的名称需要家庭管理员修改。')
            connection.execute("UPDATE settings SET value=? WHERE key='name'", (values.pop('name').strip(),))
        if values.get('avatar_media_id'):
            avatar = get_media(connection, values['avatar_media_id'], member)
            if avatar['kind'] != 'photo' or avatar['deleted_at']:
                raise HTTPException(422, '请选择一张相册中的照片作为头像。')
        for key, value in values.items():
            column = 'name' if key == 'child_name' else key
            connection.execute('UPDATE children SET ' + column + '=?,updated_at=? WHERE id=?',
                               ((value or '').strip(), now_text(), member['child_id']))
        return profile_data(connection, member)


@app.get('/api/media')
def list_media(kind: str = 'all', month: str | None = None, date: str | None = None,
               favorite: bool = False, deleted: bool = False, cursor: str | None = None,
               limit: int = 60, start_date: str | None = None, newer_cursor: str | None = None,
               member=Depends(get_member)):
    limit = max(1, min(limit, 200))
    if sum(value is not None for value in (cursor, newer_cursor, start_date)) > 1:
        raise HTTPException(422, '一次只能选择一种翻页或日期定位方式，请重新选择。')
    selected_day = None
    if start_date is not None:
        if not re.fullmatch(r'\d{4}-\d{2}-\d{2}', start_date):
            raise HTTPException(422, '定位日期格式不正确，请重新选择日期。')
        selected_day = parse_time(start_date).date().isoformat()
    position = decode_cursor(cursor if cursor is not None else newer_cursor) if cursor is not None or newer_cursor is not None else None
    conditions, parameters = query_filters(kind, month, date, favorite, deleted, member)
    filters = ' AND '.join(conditions)
    earlier = '(album_date>? OR (album_date=? AND (sort_time<? OR (sort_time=? AND id<?))))'
    later = '(album_date<? OR (album_date=? AND (sort_time>? OR (sort_time=? AND id>?))))'
    with database() as connection:
        # Counts, positions, permissions, birthday and media details all belong
        # to this snapshot, even while another family member changes the album.
        connection.execute('BEGIN')
        member = children.scope(member, connection=connection)
        total = connection.execute('SELECT COUNT(*) FROM media WHERE ' + filters, parameters).fetchone()[0]
        page_filters, page_parameters = list(conditions), list(parameters)
        if selected_day is not None:
            exists = connection.execute('SELECT 1 FROM media WHERE ' + filters + ' AND album_date=? LIMIT 1',
                                        parameters + [selected_day]).fetchone()
            if exists is None:
                raise HTTPException(404, '当前浏览范围里没有这一天的照片或视频，请重新选择日期。')
            page_filters.append('album_date<=?')
            page_parameters.append(selected_day)
        if position is not None:
            day, stamp, identity = position
            page_filters.append(earlier if newer_cursor is not None else later)
            page_parameters.extend((day, day, stamp, stamp, identity))
        ordering = 'album_date ASC,sort_time DESC,id DESC' if newer_cursor is not None else 'album_date DESC,sort_time ASC,id ASC'
        rows = connection.execute('SELECT media.* FROM media WHERE ' + ' AND '.join(page_filters) +
                                  ' ORDER BY ' + ordering + ' LIMIT ?', page_parameters + [limit]).fetchall()
        if newer_cursor is not None:
            rows.reverse()
        def has_neighbour(row, comparison):
            boundary = [row['album_date'], row['album_date'], row['sort_time'], row['sort_time'], row['id']]
            return bool(connection.execute('SELECT 1 FROM media WHERE ' + filters + ' AND ' + comparison + ' LIMIT 1',
                                           parameters + boundary).fetchone())
        has_previous = bool(rows and has_neighbour(rows[0], earlier))
        has_next = bool(rows and has_neighbour(rows[-1], later))
        birthday = child_birthday(connection, member)
        return {'items': [media_dto(get_media(connection, row['id'], member), birthday, member) for row in rows],
                'next_cursor': encode_cursor(rows[-1]) if has_next else None,
                'has_previous': has_previous, 'previous_cursor': encode_cursor(rows[0]) if rows else None,
                'total': total}


@app.get('/api/album-locator')
def album_locator(kind: str = 'all', month: str | None = None, date: str | None = None,
                  favorite: bool = False, member=Depends(get_member)):
    conditions, parameters = query_filters(kind, month, date, favorite, False, member)
    with database() as connection:
        connection.execute('BEGIN')
        member = children.scope(member, connection=connection)
        birthday = child_birthday(connection, member)
        born = parse_time(birthday).date() if birthday else None
        rows = connection.execute('SELECT album_date,COUNT(*) AS count FROM media WHERE ' +
                                  ' AND '.join(conditions) + ' GROUP BY album_date ORDER BY album_date DESC', parameters).fetchall()
        items = []
        for row in rows:
            day = parse_time(row['album_date']).date()
            year = full_years(born, day) if born else day.year
            label = ('出生前' if year < 0 else '未满1岁' if year == 0 else f'{year}岁') if born else f'{year}年'
            age_months = None
            month_label = f'{day.year}年{day.month}月'
            if born:
                if day < born:
                    age_months, month_label = -1, '出生前'
                else:
                    anniversary = day.replace(day=min(born.day, calendar.monthrange(day.year, day.month)[1]))
                    age_months = (day.year - born.year) * 12 + day.month - born.month - int(day < anniversary)
                    years, months = divmod(age_months, 12)
                    month_label = (f'{years}岁' if years else '') + (f'{months}个月' if months else '') or '出生'
            items.append({'date': row['album_date'], 'age': age_label(birthday, day),
                          'label': label, 'year': year, 'count': row['count'],
                          'age_months': age_months, 'month_label': month_label})
        return {'items': items, 'total': sum(row['count'] for row in rows), 'has_birthday': bool(birthday)}


@app.get('/api/media-window')
def media_window(anchor: str, kind: str = 'all', month: str | None = None,
                 date: str | None = None, favorite: bool = False, deleted: bool = False,
                 before: int = 12, after: int = 12, member=Depends(get_member)):
    """A bounded, bidirectional window in the same order as the filtered album."""
    before, after = max(0, min(before, 100)), max(0, min(after, 100))
    conditions, parameters = query_filters(kind, month, date, favorite, deleted, member)
    filters = ' AND '.join(conditions)
    fields = '''SELECT media.*,
        (SELECT COUNT(*) FROM comments WHERE media_id=media.id) AS comments_count,
        EXISTS(SELECT 1 FROM favorites WHERE media_id=media.id AND member_id=?) AS is_favorite
        FROM media WHERE '''
    earlier = '(album_date>? OR (album_date=? AND (sort_time<? OR (sort_time=? AND id<?))))'
    later = '(album_date<? OR (album_date=? AND (sort_time>? OR (sort_time=? AND id>?))))'
    with database() as connection:
        # Keep the anchor, counts and neighbours in one read snapshot even if a
        # family member uploads or removes an item while this request is running.
        connection.execute('BEGIN')
        current = connection.execute(fields + filters + ' AND id=?',
                                     [member['id']] + parameters + [anchor]).fetchone()
        if current is None:
            raise HTTPException(404, '这份内容不在当前浏览范围内，请返回相册重新选择。')
        ordering = [current['album_date'], current['album_date'], current['sort_time'],
                    current['sort_time'], current['id']]
        total = connection.execute('SELECT COUNT(*) FROM media WHERE ' + filters,
                                   parameters).fetchone()[0]
        position = connection.execute('SELECT COUNT(*) FROM media WHERE ' + filters + ' AND ' + earlier,
                                      parameters + ordering).fetchone()[0]
        previous = connection.execute(fields + filters + ' AND ' + earlier +
                                      ' ORDER BY album_date ASC,sort_time DESC,id DESC LIMIT ?',
                                      [member['id']] + parameters + ordering + [before]).fetchall() if before else []
        following = connection.execute(fields + filters + ' AND ' + later +
                                       ' ORDER BY album_date DESC,sort_time ASC,id ASC LIMIT ?',
                                       [member['id']] + parameters + ordering + [after]).fetchall() if after else []
        previous.reverse()
        birthday = child_birthday(connection, member)
        rows = previous + [current] + following
        return {'items': [media_dto(row, birthday, member) for row in rows],
                'index': len(previous), 'position': position, 'total': total,
                'has_before': position > len(previous),
                'has_after': position + 1 + len(following) < total}


@app.get('/api/timeline')
def timeline(kind: str = 'all', month: str | None = None, before: str | None = None,
             favorite: bool = False, limit: int = 12, member=Depends(get_member)):
    conditions, parameters = query_filters(kind, month, None, favorite, False, member)
    limit = max(1, min(limit, 31))
    with database() as connection:
        total = connection.execute('SELECT COUNT(*) FROM media WHERE ' + ' AND '.join(conditions), parameters).fetchone()[0]
        if before:
            conditions.append('album_date<?')
            parameters.append(parse_time(before).date().isoformat())
        days = [row[0] for row in connection.execute('SELECT DISTINCT album_date FROM media WHERE ' +
            ' AND '.join(conditions) + ' ORDER BY album_date DESC LIMIT ?', parameters + [limit + 1])]
        selected = days[:limit]
        birthday = child_birthday(connection, member)
        groups = []
        if selected:
            conditions.append('album_date IN (' + ','.join('?' for _ in selected) + ')')
            rows = connection.execute('''SELECT media.*,
                (SELECT COUNT(*) FROM comments WHERE media_id=media.id) AS comments_count,
                EXISTS(SELECT 1 FROM favorites WHERE media_id=media.id AND member_id=?) AS is_favorite
                FROM media WHERE ''' + ' AND '.join(conditions) +
                ' ORDER BY album_date DESC,sort_time ASC,id ASC', [member['id']] + parameters + selected).fetchall()
            for day in selected:
                groups.append({'date': day, 'age': age_label(birthday, date.fromisoformat(day)),
                               'items': [media_dto(row, birthday, member) for row in rows if row['album_date'] == day]})
    return {'groups': groups, 'next_before': selected[-1] if len(days) > limit else None, 'total': total}


@app.get('/api/months')
def months(member=Depends(get_member)):
    condition, parameters = child_media_filter(member)
    with database() as connection:
        rows = connection.execute('''SELECT substr(album_date,1,7) AS month,COUNT(*) AS count,
            SUM(kind='photo') AS photos,SUM(kind='video') AS videos FROM media
            WHERE ''' + condition + ' GROUP BY month ORDER BY month DESC', parameters).fetchall()
    return {'months': [dict(row) for row in rows]}


@app.get('/api/trash')
def trash(limit: int = 60, cursor: str | None = None, member=Depends(get_member)):
    return list_media(deleted=True, limit=limit, cursor=cursor, member=member)


@app.get('/api/members')
def members(request: Request, include_legacy: bool = False, member=Depends(get_member)):
    scoped = children.scope(member, request.headers.get('x-fam-child') if include_legacy else None,
                            allow_empty=True)
    if include_legacy and not children.family_admin(member) and (
            scoped['account_role'] != 'admin' or scoped.get('child_archived')):
        raise HTTPException(403, '只有管理员可以查看需要关联的历史家人记录。')
    with database() as connection:
        rows = connection.execute('''SELECT members.*,
            (SELECT COUNT(*) FROM media m JOIN media_children mc ON mc.media_id=m.id
             WHERE m.uploader_id=members.id AND mc.deleted_at IS NULL AND mc.child_id=?) AS uploads_count
            FROM members WHERE (? OR (COALESCE(username,'')<>'' AND COALESCE(password_hash,'')<>''))
                AND (? OR account_role='admin' OR EXISTS(SELECT 1 FROM child_members cm
                WHERE cm.member_id=members.id AND cm.child_id=?)) ORDER BY joined_at,id''',
                                  (scoped['child_id'], include_legacy, children.family_admin(member), scoped['child_id'])).fetchall()
        items = []
        for row in rows:
            context = dict(row)
            if not children.family_admin(member):
                grant = connection.execute('SELECT role FROM child_members WHERE child_id=? AND member_id=?',
                                           (scoped['child_id'], row['id'])).fetchone()
                context['family_role'] = row['account_role']
                context['account_role'] = 'admin' if row['account_role'] == 'admin' else grant['role']
            value = member_dto(context)
            if children.family_admin(member):
                value['children'] = [dict(child, role_label={'admin':'宝宝管理员','contributor':'可上传','viewer':'仅查看'}[child['role']])
                                     for child in children.member_children(connection, row['id'])]
            else:
                value = {key: value[key] for key in ('id', 'name', 'role', 'role_label', 'is_family_admin', 'registered', 'disabled')}
            value['relationship'] = children.member_relationship(connection, scoped['child_id'], row)
            items.append(value)
    return {'items': items}


@app.get('/api/activity')
def activity(limit: int = 50, before: str | None = None, member=Depends(get_member)):
    limit = max(1, min(limit, 100))
    where, parameters = child_media_filter(member)
    where += ' AND activity.child_id=?'
    parameters.append(member['child_id'])
    if before:
        where += ' AND activity.created_at<?'
        parameters.append(parse_time(before).isoformat())
    with database() as connection:
        rows = connection.execute('''SELECT activity.*,media.kind FROM activity
            JOIN media ON media.id=activity.media_id WHERE ''' + where +
            ' ORDER BY activity.created_at DESC,activity.id DESC LIMIT ?', parameters + [limit + 1]).fetchall()
    items = [dict(row) | {'thumbnail_url': f"/api/media/{row['media_id']}/thumbnail?child_id={member['child_id']}"} for row in rows[:limit]]
    return {'items': items, 'next_before': items[-1]['created_at'] if len(rows) > limit else None}


@app.get('/api/media/{media_id}')
def media_detail(media_id: str, member=Depends(get_member)):
    with database() as connection:
        row = get_media(connection, media_id, member)
        require_media_read(member, row)
        birthday = child_birthday(connection, member)
        result = media_dto(row, birthday, member)
        result['comments'] = [comment_dto(comment, member, connection) for comment in connection.execute(
            'SELECT * FROM comments WHERE media_id=? AND child_id=? ORDER BY created_at,id', (media_id, member['child_id']))]
    return result


@app.put('/api/media/{media_id}')
@app.patch('/api/media/{media_id}')
def edit_media(media_id: str, body: MediaEdit, member=Depends(get_member)):
    children.require_active(member)
    fields = body.model_dump(exclude_none=True)
    with database() as connection:
        current = get_media(connection, media_id, member)
        require_media_read(member, current)
        if current['deleted_at']:
            raise HTTPException(409, '请先从回收站恢复这份内容。')
        personal_favorite = fields.pop('favorite', None)
        if fields:
            children.require_shared_edit(connection, current, member)
        if personal_favorite is not None:
            if personal_favorite:
                connection.execute('INSERT OR IGNORE INTO favorites VALUES (?,?,?)', (member['id'], media_id, now_text()))
            else:
                connection.execute('DELETE FROM favorites WHERE member_id=? AND media_id=?', (member['id'], media_id))
        if 'captured_at' in fields:
            captured = parse_time(fields['captured_at'])
            fields.update(captured_at=captured.isoformat(), album_date=captured.date().isoformat(), sort_time=captured.timestamp())
        if fields:
            fields['updated_at'] = now_text()
            connection.execute('UPDATE media SET ' + ','.join(key + '=?' for key in fields) + ' WHERE id=?',
                               list(fields.values()) + [media_id])
    return media_detail(media_id, member)


@app.put('/api/media/{media_id}/favorite')
def favorite_media(media_id: str, body: MediaEdit, member=Depends(get_member)):
    if body.favorite is None:
        raise HTTPException(422, '请选择收藏或取消收藏。')
    return edit_media(media_id, MediaEdit(favorite=body.favorite), member)


@app.delete('/api/media/{media_id}')
def delete_media(media_id: str, member=Depends(get_member)):
    with database() as connection:
        children.set_deleted(connection, media_id, member, now_text())
    return {'message': '已移入回收站，可以随时恢复'}


@app.post('/api/media/{media_id}/restore')
def restore_media(media_id: str, member=Depends(get_member)):
    with database() as connection:
        children.set_deleted(connection, media_id, member, None)
    return media_detail(media_id, member)


@app.post('/api/media/{media_id}/children')
def link_media_children(media_id: str, body: MediaChildren, member=Depends(require_contributor)):
    with database() as connection:
        connection.execute('BEGIN IMMEDIATE')
        row = get_media(connection, media_id, member)
        if row['deleted_at']:
            raise HTTPException(409, '请先恢复照片，再添加到其他宝宝相册。')
        children.require_shared_edit(connection, row, member)
        children.link_media(connection, media_id, body.child_ids, member)
    return media_detail(media_id, member)


@app.get('/api/media/{media_id}/comments')
def comments(media_id: str, member=Depends(get_member)):
    with database() as connection:
        require_media_read(member, get_media(connection, media_id, member))
        rows = connection.execute('SELECT * FROM comments WHERE media_id=? AND child_id=? ORDER BY created_at,id',
                                  (media_id, member['child_id'])).fetchall()
        return {'items': [comment_dto(row, member, connection) for row in rows]}


def comment_dto(row, member, connection=None):
    result = {key: row[key] for key in ('id', 'name', 'text', 'created_at')} | {
        'can_delete': not member.get('child_archived', False) and (member['account_role'] == 'admin' or (
            member['account_role'] == 'contributor' and row['member_id'] == member['id']))}
    if connection is None:
        with database() as connection:
            result['reply_to'] = reply_for(connection, row['id'])
    else:
        result['reply_to'] = reply_for(connection, row['id'])
    return result


@app.post('/api/media/{media_id}/comments')
def add_comment(media_id: str, body: CommentBody, member=Depends(require_contributor)):
    return create_comment(database, get_media, comment_dto, now_text, media_id, body, member)


@app.delete('/api/media/{media_id}/comments/{comment_id}')
def delete_comment(media_id: str, comment_id: str, member=Depends(require_contributor)):
    with database() as connection:
        require_media_read(member, get_media(connection, media_id, member))
        comment = connection.execute('SELECT * FROM comments WHERE id=? AND media_id=? AND child_id=?',
                                     (comment_id, media_id, member['child_id'])).fetchone()
        if comment is None:
            raise HTTPException(404, '这条留言已经不存在。')
        if member['account_role'] != 'admin' and comment['member_id'] != member['id']:
            raise HTTPException(403, '你只能删除自己写的留言。')
        connection.execute('DELETE FROM comments WHERE id=?', (comment_id,))
        activity_row = connection.execute("SELECT id FROM activity WHERE id=? AND type='comment' AND media_id=?",
                                          (comment_id, media_id)).fetchone()
        if activity_row is None:
            activity_row = connection.execute("""SELECT id FROM activity WHERE media_id=? AND type='comment'
                AND member_name=? AND text=? AND created_at=? ORDER BY id LIMIT 1""",
                (media_id, comment['name'], comment['text'], comment['created_at'])).fetchone()
        if activity_row:
            connection.execute('DELETE FROM activity WHERE id=?', (activity_row['id'],))
    return {'message': '留言已删除'}


app.include_router(activity_feed_router(database, get_member, media_dto, comment_dto, children=children))


def ensure_thumbnail(row):
    signature = hashlib.sha256((row['id'] + row['sha256']).encode()).hexdigest()
    target = DATA / 'thumbnails' / (signature + '.jpg')
    with THUMBNAIL_LOCKS[int(signature[:4], 16) % len(THUMBNAIL_LOCKS)]:
        if target.is_file():
            return target
        temporary = target.with_name(signature + '.temporary.jpg')
        try:
            with storage_manager.materialize(row) as source:
                if row['kind'] == 'photo':
                    with Image.open(source) as original:
                        picture = ImageOps.exif_transpose(original)
                        picture.thumbnail((640, 640))
                        picture.convert('RGB').save(temporary, 'JPEG', quality=85, optimize=True)
                else:
                    position = min(1.0, max(0, (row['duration'] or 1) / 3))
                    command = [_ffmpeg_path(), '-hide_banner', '-loglevel', 'error', '-nostdin', '-threads', '2',
                               '-ss', str(position), '-i', str(source), '-frames:v', '1',
                               '-vf', "scale=640:640:force_original_aspect_ratio=decrease", '-q:v', '3', '-y', str(temporary)]
                    result = subprocess.run(command, capture_output=True, timeout=45,
                                            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
                    if result.returncode or not temporary.is_file():
                        raise ValueError('视频封面暂时无法生成')
            os.replace(temporary, target)
        except (OSError, ValueError, UnidentifiedImageError, subprocess.TimeoutExpired):
            temporary.unlink(missing_ok=True)
            raise HTTPException(422, '这份内容的预览暂时无法生成，可以尝试打开原文件。') from None
    return target


@app.get('/api/media/{media_id}/thumbnail')
def thumbnail(media_id: str, member=Depends(get_member)):
    with database() as connection:
        row = dict(get_media(connection, media_id, member))
        require_media_read(member, row)
    return FileResponse(ensure_thumbnail(row), media_type='image/jpeg', headers={'Cache-Control': 'private, no-store', 'Vary': 'Cookie, Authorization, X-FAM-Child'})


@app.api_route('/api/media/{media_id}/file', methods=['GET', 'HEAD'])
def original_file(media_id: str, request: Request, member=Depends(get_member)):
    with database() as connection:
        row = dict(get_media(connection, media_id, member))
        require_media_read(member, row)
    size = storage_manager.stat_media(row)
    etag = '"' + row['sha256'] + '"'
    headers = {'Accept-Ranges': 'bytes', 'Content-Type': row['mime_type'], 'ETag': etag,
               'Cache-Control': 'private, no-store', 'Vary': 'Cookie, Authorization, X-FAM-Child',
               'Content-Disposition': "inline; filename*=UTF-8''" + quote(row['filename'])}
    if request.headers.get('if-none-match') == etag:
        return Response(status_code=304, headers=headers)
    first, last, status_code = 0, size - 1, 200
    requested = request.headers.get('range')
    if request.headers.get('if-range') not in (None, etag):
        requested = None
    if requested:
        match = re.fullmatch(r'bytes=(\d*)-(\d*)', requested)
        try:
            if not match or not any(match.groups()) or size == 0:
                raise ValueError()
            if match[1]:
                first = int(match[1])
                last = min(int(match[2]), size - 1) if match[2] else size - 1
            else:
                suffix = int(match[2])
                if suffix <= 0:
                    raise ValueError()
                first = max(0, size - suffix)
            if first > last or first >= size:
                raise ValueError()
        except ValueError:
            return Response(status_code=416, headers={'Content-Range': f'bytes */{size}'})
        status_code = 206
        headers['Content-Range'] = f'bytes {first}-{last}/{size}'
    length = max(0, last - first + 1)
    headers['Content-Length'] = str(length)
    if request.method == 'HEAD':
        return Response(status_code=status_code, headers=headers)

    def chunks():
        yield from storage_manager.open_media(row, first=first, last=last)
    return StreamingResponse(chunks(), status_code=status_code, headers=headers)


@app.post('/api/upload')
@app.post('/api/media')
async def upload_media(file: UploadFile = File(), captured_at: str | None = Form(default=None),
                       fallback_captured_at: str | None = Form(default=None),
                       description: str = Form(default=''), member=Depends(require_contributor),
                       child_ids: str | None = Form(default=None)):
    member = children.scope(member)
    children.require_active(member)
    try:
        targets = json.loads(child_ids) if isinstance(child_ids, str) else [member['child_id']]
    except (ValueError, TypeError):
        raise HTTPException(422, '请选择要上传到的宝宝相册。') from None
    with database() as connection:
        targets = children.require_targets(connection, member, targets)
    if len(description) > 5000:
        raise HTTPException(422, '照片说明太长，请控制在五千字以内。')
    if captured_at:
        parse_time(captured_at)
    if fallback_captured_at:
        parse_time(fallback_captured_at)
    filename = Path((file.filename or '照片.jpg').replace('\\', '/')).name
    suffix = Path(filename).suffix.lower()
    if suffix not in {'.jpg', '.jpeg', '.png', '.webp', '.gif', '.mp4', '.mov', '.m4v', '.3gp'}:
        raise HTTPException(422, '暂时支持常见照片格式和 MP4、MOV 视频。')
    identity = uuid.uuid4().hex
    temporary = DATA / 'temporary' / (identity + suffix)
    digest = hashlib.sha256()
    size = 0
    upload_limit = effective_max_upload()
    try:
        with temporary.open('wb') as stream:
            while block := await file.read(512 * 1024):
                size += len(block)
                if size > upload_limit:
                    raise HTTPException(413, '文件较大，超过了服务器设定的单次上传大小。')
                digest.update(block)
                stream.write(block)
            stream.flush()
            os.fsync(stream.fileno())
        metadata = await run_in_threadpool(inspect_media, temporary, captured_at or fallback_captured_at, True)
        if not metadata['valid']:
            raise HTTPException(422, '文件无法完整读取，请重新选择原照片或视频。')
        selected_time = captured_at or metadata.get('capture_time') or metadata.get('capture_date') or now_text()
        captured = parse_time(selected_time)
        created = now_text()
        preserve_file_time(temporary, {'capture_time': captured.isoformat()})
        mime_type = mimetypes.guess_type(filename)[0] or 'application/octet-stream'
        blob = await run_in_threadpool(storage_manager.store_upload, temporary, suffix, mime_type, digest.hexdigest())
        try:
            with database() as connection:
                connection.execute('''INSERT INTO media
                    (id,source_id,kind,root_kind,relative_path,filename,mime_type,size_bytes,sha256,width,height,duration,
                     captured_at,album_date,sort_time,description,uploader_id,uploader_name,created_at,updated_at,metadata_json,
                     storage_id,object_key)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)''',
                    (identity, None, metadata['kind'], blob['root_kind'], blob['relative_path'], filename,
                     mime_type, size, digest.hexdigest(),
                     metadata['width'], metadata['height'], metadata.get('duration'), captured.isoformat(),
                     captured.date().isoformat(), captured.timestamp(), description.strip(), member['id'], member['name'],
                     created, created, json.dumps(metadata, ensure_ascii=False), blob['storage_id'], blob['object_key']))
                children.link_media(connection, identity, targets, member)
        except BaseException:
            await run_in_threadpool(storage_manager.delete_blob, blob['storage_id'], blob['object_key'])
            raise
    finally:
        temporary.unlink(missing_ok=True)
        await file.close()
    result_member = member if member['child_id'] in targets else children.scope(member, targets[0])
    return media_detail(identity, result_member)


# The web UI shares the application's identity and storage services. Business
# tasks keep their own durable state and do not depend on an open browser tab.
admin_context = SimpleNamespace(**globals())
from admin_imports import AdminImportService
from admin_backups import AdminBackupService

admin_imports = AdminImportService(admin_context)
admin_backups = AdminBackupService(admin_context)
admin_context.admin_imports = admin_imports
admin_context.admin_backups = admin_backups
app.include_router(admin_imports.router)
app.include_router(admin_backups.router)
app.include_router(admin_router(admin_context))


@asynccontextmanager
async def admin_lifespan(application):
    admin_imports.start()
    admin_backups.start()
    try:
        yield
    finally:
        await run_in_threadpool(admin_imports.stop)
        await run_in_threadpool(admin_backups.stop)


app.router.lifespan_context = admin_lifespan


@app.get('/', include_in_schema=False)
def admin_home():
    return RedirectResponse('/admin/')


WEB_DIRECTORY = Path(__file__).resolve().parent / 'web'
if WEB_DIRECTORY.is_dir():
    # Windows file associations may register JavaScript as text/plain. ES
    # modules need a JavaScript MIME type when nosniff is enabled.
    mimetypes.add_type('text/javascript', '.js')
    mimetypes.add_type('text/javascript', '.mjs')
    app.mount('/admin', StaticFiles(directory=WEB_DIRECTORY, html=True), name='admin-web')
