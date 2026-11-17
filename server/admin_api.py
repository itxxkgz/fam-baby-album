"""Same-origin administrator web sessions and album management endpoints."""
from __future__ import annotations

from datetime import datetime, timedelta
import hashlib
import hmac
import json
from pathlib import Path
import re
import secrets
import shutil
import tempfile
from urllib.parse import urlsplit
import zipfile

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field
from starlette.background import BackgroundTask

from identity import member_dto, normalize_username, password_digest, validate_password

COOKIE = 'fam_admin_session'
VERSION = '2.9.2'


def csrf_token(token):
    return hashlib.sha256(('FAM web request\0' + token).encode()).hexdigest()


def cookie_authorization(request):
    token = request.cookies.get(COOKIE, '')
    return 'Bearer ' + token if token else None


def validate_origin(request):
    origin = request.headers.get('origin')
    if origin:
        parsed = urlsplit(origin)
        expected = urlsplit(str(request.base_url))
        if (parsed.scheme, parsed.netloc) != (expected.scheme, expected.netloc):
            raise HTTPException(403, '请在相册后台页面中完成这项操作。')


def validate_web_csrf(request):
    validate_origin(request)
    token = request.cookies.get(COOKIE, '')
    supplied = request.headers.get('x-fam-csrf', '')
    if not token or not hmac.compare_digest(supplied, csrf_token(token)):
        raise HTTPException(403, '页面连接已更新，请刷新后台后重试。')


class WebLogin(BaseModel):
    username: str = Field(min_length=1, max_length=100)
    password: str = Field(min_length=1, max_length=200)


class AccountChange(BaseModel):
    current_password: str = Field(min_length=1, max_length=200)
    new_password: str | None = Field(default=None, max_length=200)
    new_username: str | None = Field(default=None, max_length=100)


class MediaSelection(BaseModel):
    ids: list[str] = Field(min_length=1, max_length=200)


class BatchEdit(MediaSelection):
    action: str
    captured_at: str | None = None
    description: str | None = Field(default=None, max_length=5000)
    child_ids: list[str] | None = Field(default=None, min_length=1, max_length=100)


class UploadSettings(BaseModel):
    max_upload_bytes: int = Field(ge=1024 * 1024)


def build_router(ctx):
    router = APIRouter(prefix='/api/admin')
    with ctx.database() as db:
        db.execute('''CREATE TABLE IF NOT EXISTS admin_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL,
            message TEXT NOT NULL, created_at TEXT NOT NULL, level TEXT NOT NULL)''')

    def record(db, title, message, level='success'):
        db.execute('INSERT INTO admin_events(title,message,created_at,level) VALUES (?,?,?,?)',
                   (title, message, ctx.now_text(), level))
        db.execute('DELETE FROM admin_events WHERE id NOT IN (SELECT id FROM admin_events ORDER BY id DESC LIMIT 1000)')

    def web_identity(request, *, csrf=False):
        if csrf:
            validate_web_csrf(request)
        member = ctx.identities.authenticate(cookie_authorization(request))
        if member['account_role'] != 'admin':
            raise HTTPException(403, '网页后台仅供管理员使用，请用管理员账号登录。')
        return member

    def session_response(request, session):
        token = session['token']
        response = JSONResponse({'member': session['member'], 'csrf': csrf_token(token),
                                 'password_change_required': session['password_change_required']})
        response.set_cookie(COOKIE, token, httponly=True, samesite='strict',
                            secure=request.url.scheme == 'https', path='/api', max_age=30 * 86400)
        response.headers['Cache-Control'] = 'no-store'
        return response

    @router.post('/session')
    def login(body: WebLogin, request: Request):
        validate_origin(request)
        ctx.limit_account_attempts(request)
        username = normalize_username(body.username)
        with ctx.database() as db:
            db.execute('BEGIN IMMEDIATE')
            if not ctx.identities.ready(db):
                raise HTTPException(409, '请先完成管理员设置，再登录后台。')
            member = db.execute('SELECT * FROM members WHERE username=?', (username,)).fetchone()
            if not ctx.identities.check_password(member, body.password):
                raise HTTPException(401, '账号或密码不正确，请重新输入。')
            if member['account_role'] != 'admin':
                raise HTTPException(403, '网页后台仅供管理员使用，家人可以继续使用 App。')
            # End only the browser's previous session, never another device's login.
            previous = request.cookies.get(COOKIE)
            if previous:
                db.execute('DELETE FROM sessions WHERE token_hash=?', (hashlib.sha256(previous.encode()).hexdigest(),))
            session = ctx.identities.issue_session(db, member)
        return session_response(request, session)

    @router.get('/session')
    def session(request: Request):
        member = web_identity(request)
        response = JSONResponse({'member': member_dto(member),
                                 'csrf': csrf_token(request.cookies[COOKIE]),
                                 'password_change_required': bool(member['password_change_required'])})
        response.headers['Cache-Control'] = 'no-store'
        return response

    @router.delete('/session')
    def logout(request: Request):
        web_identity(request, csrf=True)
        digest = hashlib.sha256(request.cookies[COOKIE].encode()).hexdigest()
        with ctx.database() as db:
            db.execute('DELETE FROM sessions WHERE token_hash=?', (digest,))
        response = JSONResponse({'message': '已退出管理后台'})
        response.delete_cookie(COOKIE, path='/api', httponly=True, samesite='strict')
        return response

    @router.post('/account')
    def account(body: AccountChange, request: Request, member=Depends(ctx.require_admin)):
        if body.new_password is None and body.new_username is None:
            raise HTTPException(422, '请填写要修改的用户名或密码。')
        with ctx.database() as db:
            db.execute('BEGIN IMMEDIATE')
            current = db.execute('SELECT * FROM members WHERE id=?', (member['id'],)).fetchone()
            if not ctx.identities.check_password(current, body.current_password):
                raise HTTPException(401, '当前密码不正确，请重新输入。')
            if current['password_change_required'] and body.new_password is None:
                raise HTTPException(403, '请先设置自己的新密码，再使用相册。')
            if body.new_username is not None:
                username = normalize_username(body.new_username)
                if username == current['username'] and body.new_password is None:
                    raise HTTPException(422, '新用户名与当前相同，请填写新的用户名。')
                if db.execute('SELECT 1 FROM members WHERE username=? AND id<>?', (username, member['id'])).fetchone():
                    raise HTTPException(409, '这个用户名已经有人使用，请换一个。')
                db.execute('UPDATE members SET username=? WHERE id=?', (username, member['id']))
            if body.new_password is not None:
                validate_password(body.new_password)
                if ctx.identities.check_password(current, body.new_password):
                    raise HTTPException(422, '新密码不能与当前密码相同。')
                salt = secrets.token_hex(16)
                db.execute('UPDATE members SET password_salt=?,password_hash=?,password_change_required=0 WHERE id=?',
                           (salt, password_digest(body.new_password, salt), member['id']))
            db.execute('DELETE FROM sessions WHERE member_id=?', (member['id'],))
            current = db.execute('SELECT * FROM members WHERE id=?', (member['id'],)).fetchone()
            result = ctx.identities.issue_session(db, current)
            record(db, '账号信息已更新', '管理员更新了自己的登录信息，其他设备需要重新登录。')
        return session_response(request, result)

    def jobs(member=None):
        service = getattr(ctx, 'admin_imports', None)
        return service.list_jobs(member.get('child_id') if member else None)['items'] if service else []

    def backups():
        service = getattr(ctx, 'admin_backups', None)
        return service.list_backups()['items'] if service else []

    @router.get('/overview')
    def overview(member=Depends(ctx.require_admin)):
        with ctx.database() as db:
            profile = ctx.profile_data(db, member)
            birthday = profile.get('birthday', '')
            where, values = ctx.children.media_filter(member)
            recent = [ctx.media_dto(row, birthday, member) for row in db.execute(
                'SELECT * FROM media WHERE ' + where + ' ORDER BY created_at DESC,id DESC LIMIT 8', values)]
            week = (datetime.now(ctx.LOCAL_TIMEZONE) - timedelta(days=7)).isoformat()
            week_count = db.execute('SELECT COUNT(*) FROM media WHERE ' + where + ' AND created_at>=?', values + [week]).fetchone()[0]
        disk = shutil.disk_usage(ctx.DATA)
        storage = ctx.storage_manager.list_configs()['items']
        all_jobs, all_backups = jobs(member), backups()
        return {'profile': profile, 'recent': recent, 'week_count': week_count, 'version': VERSION,
                'storage': {'total_bytes': disk.total, 'free_bytes': disk.free, 'used_bytes': disk.used},
                'default_storage': next((s for s in storage if s['is_default']), None),
                'backups': {'latest': next((b for b in all_backups if b['status'] == 'completed'), None),
                            'running': next((b for b in all_backups if b['status'] in {'running', 'queued'}), None)},
                'tasks': {'active': sum(j['status'] in {'receiving', 'queued', 'running', 'paused'} for j in all_jobs),
                          'failed': sum(bool(j.get('failed')) for j in all_jobs)}}

    @router.get('/media')
    def media(kind: str = 'all', search: str = '', uploader_id: str = '', date_from: str = '',
              date_to: str = '', deleted: bool = False, offset: int = 0, limit: int = 40,
              member=Depends(ctx.require_admin)):
        if kind not in {'all', 'photo', 'video'} or len(search) > 100 or len(uploader_id) > 100:
            raise HTTPException(422, '筛选条件不正确，请重新选择。')
        if offset < 0 or limit < 1 or limit > 200:
            raise HTTPException(422, '请重新加载照片列表。')
        scoped, values = ctx.children.media_filter(member, deleted=deleted)
        conditions = [scoped]
        if kind != 'all':
            conditions.append('kind=?'); values.append(kind)
        if search.strip():
            conditions.append("instr(lower(filename||' '||description||' '||uploader_name),lower(?))>0")
            values.append(search.strip())
        if uploader_id:
            conditions.append('uploader_id=?'); values.append(uploader_id)
        start = ctx.parse_time(date_from).date().isoformat() if date_from else ''
        end = ctx.parse_time(date_to).date().isoformat() if date_to else ''
        if start and end and start > end:
            raise HTTPException(422, '开始日期不能晚于结束日期。')
        for day, operator in ((start, '>='), (end, '<=')):
            if day:
                conditions.append('album_date' + operator + '?'); values.append(day)
        where = ' AND '.join(conditions)
        with ctx.database() as db:
            db.execute('BEGIN')
            total = db.execute('SELECT COUNT(*) FROM media WHERE ' + where, values).fetchone()[0]
            rows = db.execute('SELECT * FROM media WHERE ' + where +
                              ' ORDER BY album_date DESC,sort_time ASC,id ASC LIMIT ? OFFSET ?', values + [limit, offset]).fetchall()
            birthday = ctx.children.birthday(db, member)
        return {'items': [ctx.media_dto(row, birthday, member) for row in rows], 'total': total,
                'offset': offset, 'has_more': offset + len(rows) < total}

    def selected(db, ids, member):
        unique = list(dict.fromkeys(ids))
        if any(len(item) > 100 for item in unique):
            raise HTTPException(422, '选择的内容不正确，请重新选择。')
        rows = []
        for identity in unique:
            try:
                rows.append(ctx.children.require_media(db, identity, member))
            except HTTPException as error:
                if error.status_code == 404:
                    raise HTTPException(409, '部分内容不在当前宝宝相册中，请刷新后重新选择。') from None
                raise
        return rows

    @router.post('/media/batch')
    def batch(body: BatchEdit, member=Depends(ctx.require_admin)):
        if body.action not in {'trash', 'restore', 'edit', 'link'}:
            raise HTTPException(422, '请选择正确的批量操作。')
        ctx.children.require_active(member)
        with ctx.database() as db:
            db.execute('BEGIN IMMEDIATE')
            rows = selected(db, body.ids, member)
            if body.action == 'link':
                targets = ctx.children.require_targets(db, member, body.child_ids or [])
                if any(row['deleted_at'] for row in rows):
                    raise HTTPException(409, '请先恢复这些内容，再添加到其他宝宝相册。')
                for row in rows:
                    ctx.children.link_media(db, row['id'], targets, member)
                record(db, '已添加到宝宝相册', f'本次整理了 {len(rows)} 份内容，原文件没有重复保存。')
                return {'count': len(rows), 'message': '已添加到所选宝宝相册，原文件仍只保存一份。'}
            values = {'updated_at': ctx.now_text()}
            if body.action == 'trash':
                values['deleted_at'] = ctx.now_text()
            elif body.action == 'restore':
                values['deleted_at'] = None
            else:
                if any(row['deleted_at'] for row in rows):
                    raise HTTPException(409, '请先恢复回收站中的内容，再修改。')
                if body.captured_at is None and body.description is None:
                    raise HTTPException(422, '请填写新的拍摄时间或照片说明。')
                if body.captured_at is not None:
                    captured = ctx.parse_time(body.captured_at)
                    values.update(captured_at=captured.isoformat(), album_date=captured.date().isoformat(), sort_time=captured.timestamp())
                if body.description is not None:
                    values['description'] = body.description.strip()
            for row in rows:
                if body.action in ('trash', 'restore'):
                    ctx.children.set_deleted(db, row['id'], member, values['deleted_at'])
                else:
                    ctx.children.require_shared_edit(db, row, member)
                    db.execute('UPDATE media SET ' + ','.join(key + '=?' for key in values) + ' WHERE id=?',
                               list(values.values()) + [row['id']])
            title = {'trash': '已移入回收站', 'restore': '已恢复照片与视频', 'edit': '已更新照片资料'}[body.action]
            record(db, title, f'本次处理了 {len(rows)} 份内容。')
        return {'count': len(rows), 'message': title}

    @router.post('/media/download')
    def download(body: MediaSelection, member=Depends(ctx.require_admin)):
        with ctx.database() as db:
            rows = selected(db, body.ids, member)
        required = sum(row['size_bytes'] for row in rows)
        if required + 64 * 1024 * 1024 > shutil.disk_usage(ctx.DATA).free:
            raise HTTPException(409, '服务器可用空间不足以打包这些文件，请分批下载。')
        descriptor, name = tempfile.mkstemp(prefix='family-download-', suffix='.zip', dir=ctx.DATA / 'temporary')
        import os
        os.close(descriptor)
        target = Path(name)
        try:
            descriptions = []
            with zipfile.ZipFile(target, 'w', zipfile.ZIP_STORED, allowZip64=True) as archive:
                for index, row in enumerate(rows, 1):
                    filename = re.sub(r'[\\/\x00-\x1f<>:"|?*]', '_', row['filename']).strip(' .')[:150] or '照片'
                    filename = f'{index:03d}-{filename}'
                    captured = ctx.parse_time(row['captured_at'])
                    timestamp = (max(1980, min(2107, captured.year)), captured.month, captured.day,
                                 captured.hour, captured.minute, captured.second)
                    entry = zipfile.ZipInfo(filename, timestamp)
                    digest, size = hashlib.sha256(), 0
                    with archive.open(entry, 'w', force_zip64=True) as output:
                        for chunk in ctx.storage_manager.open_media(row):
                            output.write(chunk); digest.update(chunk); size += len(chunk)
                    if size != row['size_bytes'] or digest.hexdigest() != row['sha256']:
                        raise HTTPException(409, '有原文件未能完整读取，请检查存储后重试。')
                    descriptions.append({'文件名': filename, '拍摄时间': captured.strftime('%Y年%m月%d日 %H:%M:%S'),
                                         '上传家人': row['uploader_name'], '照片说明': row['description']})
                archive.writestr('照片资料.json', json.dumps(descriptions, ensure_ascii=False, indent=2).encode('utf-8'))
            return FileResponse(target, media_type='application/zip', filename='FAM相册原文件.zip',
                                background=BackgroundTask(target.unlink, missing_ok=True))
        except BaseException:
            target.unlink(missing_ok=True)
            raise

    @router.get('/settings')
    def settings(member=Depends(ctx.require_admin)):
        return {'max_upload_bytes': ctx.effective_max_upload(), 'server_max_upload_bytes': ctx.MAX_UPLOAD, 'version': VERSION}

    @router.put('/settings')
    def save_settings(body: UploadSettings, member=Depends(ctx.require_admin)):
        if body.max_upload_bytes > ctx.MAX_UPLOAD:
            raise HTTPException(422, '不能超过服务器部署时设置的上传上限。')
        with ctx.database() as db:
            db.execute("INSERT OR REPLACE INTO settings VALUES ('admin_max_upload_bytes',?)", (str(body.max_upload_bytes),))
            record(db, '上传设置已更新', '管理员修改了单个文件的上传大小限制。')
        return settings(member)

    @router.get('/records')
    def records(member=Depends(ctx.require_admin)):
        with ctx.database() as db:
            items = [dict(row) for row in db.execute('SELECT title,message,created_at,level FROM admin_events ORDER BY id DESC LIMIT 40')]
        for job in jobs(member)[:15]:
            items.append({'title': job['name'], 'message': job.get('message') or job['status_label'],
                          'created_at': job.get('updated_at', job['created_at']),
                          'level': 'warning' if job.get('failed') else 'info'})
        for backup in backups()[:10]:
            items.append({'title': backup['name'], 'message': backup.get('message') or backup['status_label'],
                          'created_at': backup.get('finished_at') or backup['created_at'],
                          'level': 'warning' if backup['status'] == 'failed' else 'info'})
        return {'items': sorted(items, key=lambda x: x['created_at'], reverse=True)[:50]}

    return router
