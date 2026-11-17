"""Durable, administrator-only media import queues.

Files are received before processing starts. A database lease keeps one import
worker active across application instances; the lease heartbeat also covers long
video uploads. Source folders are deployment allowlisted, never arbitrary paths.
"""
from __future__ import annotations

import asyncio
from contextlib import contextmanager
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import threading
import time
import uuid

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from pydantic import BaseModel, Field


EXTENSIONS = {'.jpg', '.jpeg', '.png', '.webp', '.gif', '.mp4', '.mov', '.m4v', '.3gp'}
MAX_FILES = 10000
RESERVE_BYTES = 32 * 1024 * 1024
LABELS = {'receiving': '等待开始', 'queued': '等待导入', 'running': '正在导入',
          'paused': '已暂停', 'completed': '已完成', 'failed': '部分未完成', 'cancelled': '已取消'}
ITEM_LABELS = {'pending': '等待导入', 'processing': '正在导入', 'imported': '已导入',
               'duplicate': '已跳过重复内容', 'failed': '未能导入', 'cancelled': '已取消'}


class NewImport(BaseModel):
    name: str = Field(default='电脑导入', min_length=1, max_length=80)
    source: str = 'upload'
    child_ids: list[str] | None = Field(default=None, min_length=1, max_length=100)


class DirectoryInput(BaseModel):
    root_id: str = Field(min_length=1, max_length=80)
    path: str = Field(default='', max_length=1000)
    name: str = Field(default='服务器目录导入', min_length=1, max_length=80)
    child_ids: list[str] | None = Field(default=None, min_length=1, max_length=100)


def _name(value):
    value = re.sub(r'[\x00-\x1f\x7f]', '', value).strip()
    if not value:
        raise HTTPException(422, '请填写容易识别的导入名称。')
    return value[:180]


def _linked(path):
    return path.is_symlink() or (hasattr(path, 'is_junction') and path.is_junction())


class AdminImportService:
    def __init__(self, ctx):
        self.ctx = ctx
        self.staging = Path(ctx.DATA) / 'import-staging'
        self.staging.mkdir(parents=True, exist_ok=True)
        if _linked(self.staging):
            raise RuntimeError('导入暂存目录不能是符号链接。')
        self.owner = uuid.uuid4().hex
        self._stop = threading.Event()
        self._wake = threading.Event()
        self._thread = None
        self._heartbeat = None
        self._start_lock = threading.Lock()
        self._lease_lost = threading.Event()
        self._leased = False
        self._file_lock = None
        self.roots = {'library': ('服务器图库', Path(ctx.LIBRARY).resolve())}
        raw = os.environ.get('ALBUM_IMPORT_ROOTS', '[]') or '[]'
        try:
            configured = json.loads(raw)
            if not isinstance(configured, list) or any(not isinstance(p, str) for p in configured):
                raise ValueError()
            for number, value in enumerate(configured):
                path = Path(value).expanduser()
                if not path.is_absolute():
                    raise ValueError()
                self.roots['extra-' + str(number)] = ('导入目录 ' + str(number + 1), path.resolve())
        except (ValueError, TypeError):
            raise RuntimeError('导入目录设置无效，请使用绝对目录组成的列表。') from None
        with ctx.database() as db:
            db.execute('''CREATE TABLE IF NOT EXISTS admin_import_jobs (
                id TEXT PRIMARY KEY,name TEXT NOT NULL,source TEXT NOT NULL,status TEXT NOT NULL,
                member_id TEXT NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
                message TEXT NOT NULL DEFAULT '')''')
            db.execute('''CREATE TABLE IF NOT EXISTS admin_import_items (
                id TEXT PRIMARY KEY,job_id TEXT NOT NULL REFERENCES admin_import_jobs(id),
                name TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'pending',size_bytes INTEGER NOT NULL DEFAULT 0,
                sha256 TEXT NOT NULL DEFAULT '',staged_path TEXT NOT NULL DEFAULT '',
                root_id TEXT NOT NULL DEFAULT '',source_path TEXT NOT NULL DEFAULT '',
                fallback_time TEXT,media_id TEXT,message TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,updated_at TEXT NOT NULL)''')
            db.execute('CREATE INDEX IF NOT EXISTS admin_import_order ON admin_import_items(job_id,status,created_at,id)')
            db.execute('''CREATE TABLE IF NOT EXISTS admin_import_worker (
                singleton INTEGER PRIMARY KEY CHECK(singleton=1),owner TEXT NOT NULL,expires_at REAL NOT NULL)''')
            if 'child_ids_json' not in {r[1] for r in db.execute('PRAGMA table_info(admin_import_jobs)')}:
                db.execute("ALTER TABLE admin_import_jobs ADD COLUMN child_ids_json TEXT NOT NULL DEFAULT '[]'")
                default = db.execute("SELECT value FROM settings WHERE key='default_child_id'").fetchone()
                if default:
                    db.execute('UPDATE admin_import_jobs SET child_ids_json=?', (json.dumps([default[0]]),))
        self.router = self._router()

    def _limit(self):
        return self.ctx.effective_max_upload() if hasattr(self.ctx, 'effective_max_upload') else self.ctx.MAX_UPLOAD

    def _get(self, db, job_id):
        row = db.execute('SELECT * FROM admin_import_jobs WHERE id=?', (job_id,)).fetchone()
        if row is None:
            raise HTTPException(404, '没有找到这项导入任务，请刷新后重试。')
        return row

    def _dto(self, db, row, detail=False):
        result = dict(row)
        counts = db.execute('''SELECT COUNT(*) AS total,COALESCE(SUM(size_bytes),0) AS bytes,
            COALESCE(SUM(status='imported'),0) AS imported,COALESCE(SUM(status='duplicate'),0) AS duplicates,
            COALESCE(SUM(status='failed'),0) AS failed,COALESCE(SUM(status='cancelled'),0) AS cancelled
            FROM admin_import_items WHERE job_id=?''', (row['id'],)).fetchone()
        result.update(dict(counts))
        result['processed'] = result['imported'] + result['duplicates'] + result['failed'] + result['cancelled']
        result['status_label'] = LABELS.get(row['status'], '等待处理')
        result.pop('member_id', None)
        result['child_ids'] = json.loads(result.pop('child_ids_json', '[]'))
        result['child_names'] = [r[0] for r in db.execute('SELECT name FROM children WHERE id IN (' +
            ','.join('?' for _ in result['child_ids']) + ')', result['child_ids'])] if result['child_ids'] else []
        if detail:
            items = db.execute('''SELECT name,status,message FROM admin_import_items
                WHERE job_id=? ORDER BY created_at,id LIMIT 1000''', (row['id'],)).fetchall()
            result['items'] = [dict(item, status_label=ITEM_LABELS.get(item['status'], '等待处理')) for item in items]
            result['items_truncated'] = result['total'] > 1000
        return result

    def list_jobs(self, child_id=None):
        with self.ctx.database() as db:
            where = ' WHERE EXISTS(SELECT 1 FROM json_each(child_ids_json) WHERE value=?)' if child_id else ''
            rows = db.execute('SELECT * FROM admin_import_jobs' + where + ' ORDER BY created_at DESC,id DESC LIMIT 100',
                              [child_id] if child_id else []).fetchall()
            return {'items': [self._dto(db, row) for row in rows]}

    def get_job(self, job_id, detail=True):
        with self.ctx.database() as db:
            return self._dto(db, self._get(db, job_id), detail)

    def _create(self, name, source, member, status='receiving', db=None, child_ids=None):
        if db is None:
            with self.ctx.database() as connection:
                return self._create(name, source, member, status, connection, child_ids)
        identity, now = uuid.uuid4().hex, self.ctx.now_text()
        scoped = self.ctx.children.scope(member, connection=db)
        child_ids = self.ctx.children.require_targets(db, scoped, child_ids or [scoped['child_id']])
        db.execute('INSERT INTO admin_import_jobs(id,name,source,status,member_id,created_at,updated_at,message,child_ids_json) VALUES (?,?,?,?,?,?,?,?,?)',
                   (identity, _name(name), source, status, member['id'], now, now,
                    '文件上传到服务器后，再点击开始导入。' if source == 'upload' else '文件会在服务器上依次导入。', json.dumps(child_ids)))
        return identity

    def _source(self, root_id, relative, *, directory=False):
        if root_id not in self.roots:
            raise HTTPException(422, '请选择允许导入的服务器目录。')
        _, root = self.roots[root_id]
        relative = relative.replace('\\', '/')
        if (relative.startswith('/') or ':' in relative or '\x00' in relative
                or any(p.startswith('.') for p in relative.split('/') if p)):
            raise HTTPException(422, '请填写所选目录内的文件夹，不要使用上级目录或隐藏目录。')
        target = root / relative
        try:
            current = root
            for part in Path(relative).parts:
                current = current / part
                if _linked(current):
                    raise ValueError()
            resolved = target.resolve(strict=True)
            if not resolved.is_relative_to(root) or _linked(target):
                raise ValueError()
            if directory and not resolved.is_dir():
                raise ValueError()
            if not directory and not resolved.is_file():
                raise ValueError()
        except (OSError, ValueError, RuntimeError):
            raise HTTPException(422, '这个位置无法读取，或不在允许导入的目录内。') from None
        return resolved

    def _scan(self, body, digest=True, member=None):
        folder = self._source(body.root_id, body.path, directory=True)
        root = self.roots[body.root_id][1]
        entries, unsupported, total_bytes, duplicates = [], 0, 0, 0
        with self.ctx.database() as db:
            if member is None:
                known = {row[0] for row in db.execute('SELECT sha256 FROM media')}
            else:
                scoped = self.ctx.children.scope(member, connection=db)
                targets = self.ctx.children.require_targets(db, scoped, body.child_ids or [scoped['child_id']])
                known = {row[0] for row in db.execute('''SELECT sha256 FROM media WHERE NOT EXISTS(
                    SELECT 1 FROM json_each(?) t WHERE NOT EXISTS(SELECT 1 FROM media_children mc
                        JOIN media same ON same.id=mc.media_id
                        WHERE same.sha256=media.sha256 AND same.size_bytes=media.size_bytes AND mc.child_id=t.value))''', (json.dumps(targets),))}
        seen = set(known)
        visited = 0
        for base, directories, filenames in os.walk(folder, followlinks=False):
            directories[:] = sorted(d for d in directories if not d.startswith('.') and not _linked(Path(base) / d))
            for filename in sorted(filenames):
                visited += 1
                if visited > MAX_FILES:
                    raise HTTPException(422, '这个目录的文件较多，请按子文件夹分批导入，每批最多一万个文件。')
                target = Path(base) / filename
                if filename.startswith('.') or _linked(target) or target.suffix.lower() not in EXTENSIONS:
                    unsupported += 1
                    continue
                relative = target.relative_to(root).as_posix()
                checked = self._source(body.root_id, relative)
                info = checked.stat()
                checksum = ''
                if digest:
                    h = hashlib.sha256()
                    with self._open_source(body.root_id, relative) as stream:
                        while block := stream.read(1024 * 1024):
                            h.update(block)
                    checksum = h.hexdigest()
                    if checksum in seen:
                        duplicates += 1
                    seen.add(checksum)
                total_bytes += info.st_size
                entries.append({'name': _name(filename), 'root_id': body.root_id, 'source_path': relative,
                                'size_bytes': info.st_size, 'sha256': checksum,
                                'fallback_time': datetime.fromtimestamp(info.st_mtime, self.ctx.LOCAL_TIMEZONE).isoformat()})
        return entries, {'count': len(entries), 'bytes': total_bytes, 'duplicates': duplicates, 'unsupported': unsupported}

    @contextmanager
    def _open_source(self, root_id, relative):
        path = self._source(root_id, relative)
        with path.open('rb') as stream:
            # Recheck after opening to catch a folder/link swapped between the
            # containment check and open(), before reading any file content.
            checked = self._source(root_id, relative).stat()
            opened = os.fstat(stream.fileno())
            if (opened.st_dev, opened.st_ino) != (checked.st_dev, checked.st_ino):
                raise HTTPException(409, '文件位置发生了变化，请重新扫描后导入。')
            yield stream

    def _insert_item(self, db, job, name, *, size=0, checksum='', staged='', root_id='', source_path='', fallback=None):
        identity, now = uuid.uuid4().hex, self.ctx.now_text()
        db.execute('''INSERT INTO admin_import_items
            (id,job_id,name,size_bytes,sha256,staged_path,root_id,source_path,fallback_time,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)''',
                   (identity, job, name, size, checksum, staged, root_id, source_path, fallback, now, now))
        return identity

    async def receive(self, job_id, file, fallback=None):
        with self.ctx.database() as db:
            row = self._get(db, job_id)
            if row['source'] != 'upload' or row['status'] != 'receiving':
                raise HTTPException(409, '这项任务已开始处理，新增文件请建立另一项导入任务。')
            if db.execute('SELECT COUNT(*) FROM admin_import_items WHERE job_id=?', (job_id,)).fetchone()[0] >= MAX_FILES:
                raise HTTPException(422, '这一批文件较多，请建立另一项导入任务。')
        filename = _name(Path((file.filename or '').replace('\\', '/')).name)
        suffix = Path(filename).suffix.lower()
        if suffix not in EXTENSIONS:
            await file.close()
            raise HTTPException(422, '请选择常见照片格式或 MP4、MOV 视频。')
        if fallback:
            fallback = self.ctx.parse_time(fallback).isoformat()
        target = self.staging / (uuid.uuid4().hex + suffix)
        limit, size, hasher = self._limit(), 0, hashlib.sha256()
        try:
            with target.open('xb') as output:
                while block := await file.read(1024 * 1024):
                    size += len(block)
                    if size > limit:
                        raise HTTPException(413, '文件超过了相册设定的单次上传大小。')
                    if shutil.disk_usage(self.staging).free < len(block) + RESERVE_BYTES:
                        raise HTTPException(507, '服务器剩余空间不足，请清理空间后再上传。')
                    output.write(block)
                    hasher.update(block)
                output.flush()
                os.fsync(output.fileno())
            if not size:
                raise HTTPException(422, '这个文件是空的，请重新选择。')
            with self.ctx.database() as db:
                db.execute('BEGIN IMMEDIATE')
                if self._get(db, job_id)['status'] != 'receiving':
                    raise HTTPException(409, '任务状态已改变，请刷新后重新上传这个文件。')
                if db.execute('SELECT COUNT(*) FROM admin_import_items WHERE job_id=?', (job_id,)).fetchone()[0] >= MAX_FILES:
                    raise HTTPException(422, '这一批文件较多，请建立另一项导入任务。')
                self._insert_item(db, job_id, filename, size=size, checksum=hasher.hexdigest(),
                                  staged=target.name, fallback=fallback)
                db.execute('UPDATE admin_import_jobs SET updated_at=? WHERE id=?', (self.ctx.now_text(), job_id))
        except BaseException:
            target.unlink(missing_ok=True)
            raise
        finally:
            await file.close()
        return self.get_job(job_id, False)

    def action(self, job_id, action, member):
        cleanup = []
        with self.ctx.database() as db:
            db.execute('BEGIN IMMEDIATE')
            job = self._get(db, job_id)
            status, message = job['status'], ''
            if action == 'start':
                if status != 'receiving':
                    raise HTTPException(409, '任务已经开始，请刷新查看进度。')
                if not db.execute('SELECT 1 FROM admin_import_items WHERE job_id=?', (job_id,)).fetchone():
                    raise HTTPException(422, '请先上传至少一个文件，再开始导入。')
                status, message = 'queued', '文件已经传到服务器，关闭页面也会继续导入。'
            elif action == 'pause':
                if status not in ('queued', 'running'):
                    raise HTTPException(409, '当前任务无需暂停。')
                status, message = 'paused', '已暂停；正在处理的文件会先完成，之后不再导入下一份。'
            elif action == 'resume':
                if status != 'paused':
                    raise HTTPException(409, '只有暂停的任务可以继续。')
                status, message = 'queued', '已安排继续导入。'
            elif action == 'retry':
                if status not in ('failed', 'completed', 'paused'):
                    raise HTTPException(409, '请等待当前处理结束，再重试失败项。')
                changed = db.execute("UPDATE admin_import_items SET status='pending',message='' WHERE job_id=? AND status='failed'", (job_id,)).rowcount
                if not changed:
                    raise HTTPException(409, '没有需要重试的文件。')
                status, message = 'queued', '已安排重新处理未成功的文件。'
            elif action == 'cancel':
                if status in ('completed', 'cancelled'):
                    raise HTTPException(409, '这项任务已经结束。')
                cleanup = [r[0] for r in db.execute("SELECT staged_path FROM admin_import_items WHERE job_id=? AND status IN ('pending','failed')", (job_id,))]
                db.execute("UPDATE admin_import_items SET status='cancelled',message='已取消',updated_at=? WHERE job_id=? AND status IN ('pending','failed')", (self.ctx.now_text(), job_id))
                status, message = 'cancelled', '已取消；当前文件处理完后停止，已导入的照片会保留。'
            else:
                raise HTTPException(404, '不支持这项导入操作。')
            db.execute('UPDATE admin_import_jobs SET status=?,message=?,updated_at=?,member_id=? WHERE id=?',
                       (status, message, self.ctx.now_text(), member['id'], job_id))
        for filename in cleanup:
            self._clean(filename)
        if status == 'queued':
            self.start()
            self._wake.set()
        return self.get_job(job_id, False)

    def _clean(self, filename):
        if filename and Path(filename).name == filename:
            try:
                (self.staging / filename).unlink(missing_ok=True)
            except OSError:
                pass

    def _acquire(self):
        # An OS lock is held until the current file finishes, even if shutdown
        # outlasts the HTTP server's grace period. It prevents a lease timeout
        # from allowing two slow workers to import the same original.
        if self._file_lock is None:
            handle = (self.staging / '.worker.lock').open('a+b')
            try:
                if handle.seek(0, os.SEEK_END) == 0:
                    handle.write(b'0')
                    handle.flush()
                handle.seek(0)
                if os.name == 'nt':
                    import msvcrt
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                handle.close()
                return False
            self._file_lock = handle
        with self.ctx.database() as db:
            db.execute('BEGIN IMMEDIATE')
            now = time.time()
            # Owning the cross-process lock proves no previous worker is active.
            db.execute('INSERT OR REPLACE INTO admin_import_worker VALUES (1,?,?)', (self.owner, now + 30))
            # The previous worker disappeared without finishing its current file.
            # Exact file hashing on resume prevents a completed-but-unrecorded upload from duplicating.
            db.execute("UPDATE admin_import_items SET status='pending' WHERE status='processing'")
            db.execute("UPDATE admin_import_jobs SET status='paused',message=?,updated_at=? WHERE status='running'",
                       ('服务重新启动，点击继续即可接着导入。', self.ctx.now_text()))
        self._leased = True
        return True

    def _keep_alive(self, worker):
        while worker.is_alive():
            if self._leased:
                try:
                    with self.ctx.database() as db:
                        changed = db.execute('UPDATE admin_import_worker SET expires_at=? WHERE singleton=1 AND owner=?',
                                             (time.time() + 30, self.owner)).rowcount
                    if not changed:
                        self._lease_lost.set()
                        self._wake.set()
                        return
                except sqlite3.Error:
                    # Do not continue processing if lease ownership can no longer be guaranteed.
                    self._lease_lost.set()
                    self._wake.set()
                    return
            time.sleep(3)

    def start(self):
        with self._start_lock:
            if self._thread and self._thread.is_alive():
                return
            self._stop.clear()
            self._lease_lost.clear()
            self._thread = threading.Thread(target=self._work, name='fam-import-worker', daemon=True)
            self._thread.start()
            self._heartbeat = threading.Thread(target=self._keep_alive, args=(self._thread,), name='fam-import-heartbeat', daemon=True)
            self._heartbeat.start()

    def stop(self):
        self._stop.set()
        self._wake.set()
        if self._thread and self._thread is not threading.current_thread():
            self._thread.join(timeout=5)

    def _next(self):
        with self.ctx.database() as db:
            db.execute('BEGIN IMMEDIATE')
            job = db.execute("SELECT * FROM admin_import_jobs WHERE status IN ('queued','running') ORDER BY created_at,id LIMIT 1").fetchone()
            if job is None:
                return None
            member = db.execute('SELECT * FROM members WHERE id=?', (job['member_id'],)).fetchone()
            if not member or member['disabled'] or member['account_role'] != 'admin' or dict(member).get('password_change_required'):
                db.execute("UPDATE admin_import_jobs SET status='paused',message=?,updated_at=? WHERE id=?",
                           ('发起这项导入的账号暂时没有管理员权限，请由管理员继续任务。', self.ctx.now_text(), job['id']))
                return None
            try:
                targets = json.loads(job['child_ids_json'])
                scoped = self.ctx.children.scope(dict(member), targets[0], db)
                self.ctx.children.require_targets(db, scoped, targets)
            except (HTTPException, ValueError, IndexError):
                db.execute("UPDATE admin_import_jobs SET status='paused',message=?,updated_at=? WHERE id=?",
                           ('目标宝宝相册暂时不能接收照片，请恢复相册或调整权限后继续。', self.ctx.now_text(), job['id']))
                return None
            item = db.execute("SELECT * FROM admin_import_items WHERE job_id=? AND status IN ('pending','processing') ORDER BY created_at,id LIMIT 1", (job['id'],)).fetchone()
            if item is None:
                failures = db.execute("SELECT COUNT(*) FROM admin_import_items WHERE job_id=? AND status='failed'", (job['id'],)).fetchone()[0]
                db.execute('UPDATE admin_import_jobs SET status=?,message=?,updated_at=? WHERE id=?',
                           ('failed' if failures else 'completed', '有文件未能导入，可以查看原因后重试。' if failures else '这一批回忆已经整理完成。', self.ctx.now_text(), job['id']))
                return None
            now = self.ctx.now_text()
            db.execute("UPDATE admin_import_jobs SET status='running',updated_at=? WHERE id=?", (now, job['id']))
            db.execute("UPDATE admin_import_items SET status='processing',updated_at=? WHERE id=?", (now, item['id']))
            scoped['_import_child_ids'] = targets
            return dict(item), scoped

    def _copy_source(self, item):
        original = self._source(item['root_id'], item['source_path'])
        info = original.stat()
        if info.st_size > self._limit():
            raise HTTPException(413, '文件超过了相册设定的单次上传大小。')
        if shutil.disk_usage(self.staging).free < info.st_size * 2 + RESERVE_BYTES:
            raise HTTPException(507, '服务器剩余空间不足，请清理空间后重试。')
        target = self.staging / (uuid.uuid4().hex + original.suffix.lower())
        hasher, size = hashlib.sha256(), 0
        try:
            with self._open_source(item['root_id'], item['source_path']) as source, target.open('xb') as output:
                while block := source.read(1024 * 1024):
                    size += len(block)
                    if size > self._limit():
                        raise HTTPException(413, '文件超过了相册设定的单次上传大小。')
                    output.write(block)
                    hasher.update(block)
            after = original.stat()
            if info.st_mtime_ns != after.st_mtime_ns or info.st_size != after.st_size:
                raise HTTPException(409, '原文件正在变化，请等文件保存完成后重试。')
            item.update(staged_path=target.name, sha256=hasher.hexdigest(), size_bytes=size,
                        fallback_time=datetime.fromtimestamp(info.st_mtime, self.ctx.LOCAL_TIMEZONE).isoformat())
            with self.ctx.database() as db:
                db.execute('UPDATE admin_import_items SET staged_path=?,sha256=?,size_bytes=?,fallback_time=? WHERE id=?',
                           (item['staged_path'], item['sha256'], size, item['fallback_time'], item['id']))
            return target
        except BaseException:
            target.unlink(missing_ok=True)
            raise

    def _process(self, item, member):
        status, message, media_id = 'failed', '文件未能导入，请稍后重试。', None
        try:
            if item['root_id']:
                self._clean(item['staged_path'])
                target = self._copy_source(item)
            else:
                if not item['staged_path'] or Path(item['staged_path']).name != item['staged_path']:
                    raise HTTPException(422, '暂存文件无法读取，请重新上传。')
                target = self.staging / item['staged_path']
                if _linked(target) or not target.is_file():
                    raise HTTPException(422, '暂存文件无法读取，请重新上传。')
            with self.ctx.database() as db:
                db.execute('BEGIN IMMEDIATE')
                targets = self.ctx.children.require_targets(db, member, member.get('_import_child_ids') or [member['child_id']])
                duplicate = db.execute('SELECT id,deleted_at FROM media WHERE sha256=? AND size_bytes=? LIMIT 1',
                                       (item['sha256'], item['size_bytes'])).fetchone()
                if duplicate:
                    # Older uploads may contain several records for identical
                    # bytes. Each target can already own a different record.
                    existing = {}
                    for child in targets:
                        found = db.execute('''SELECT mc.deleted_at FROM media_children mc JOIN media m ON m.id=mc.media_id
                            WHERE mc.child_id=? AND m.sha256=? AND m.size_bytes=?
                            ORDER BY (mc.deleted_at IS NULL) DESC LIMIT 1''', (child, item['sha256'], item['size_bytes'])).fetchone()
                        if found is not None:
                            existing[child] = found['deleted_at']
                    missing = [child for child in targets if child not in existing]
                    added = bool(missing)
                    if missing:
                        self.ctx.children.link_media(db, duplicate['id'], missing, member)
                    status, media_id = ('imported' if added else 'duplicate'), duplicate['id']
                    message = ('已加入所选宝宝相册，原文件没有重复保存。' if added else
                               '相同内容已在回收站，可到对应宝宝的回收站恢复。' if any(existing.get(child) for child in targets) else
                               '所选宝宝的相册已经有这份内容，已跳过。')
            if not duplicate:
                if shutil.disk_usage(self.staging).free < item['size_bytes'] + RESERVE_BYTES:
                    raise HTTPException(507, '服务器剩余空间不足，请清理空间后重试。')
                with target.open('rb') as source:
                    result = asyncio.run(self.ctx.upload_media(file=UploadFile(file=source, filename=item['name']),
                                         captured_at=None, fallback_captured_at=item['fallback_time'],
                                         description='', member=member, child_ids=json.dumps(targets)))
                status, media_id, message = 'imported', result['id'], '已导入，拍摄时间已保留。'
        except Exception as error:
            detail = getattr(error, 'detail', None) or getattr(error, 'message', None)
            if isinstance(detail, str) and len(detail) <= 220 and re.search(r'[\u4e00-\u9fff]', detail):
                message = detail
        with self.ctx.database() as db:
            db.execute('UPDATE admin_import_items SET status=?,message=?,media_id=?,updated_at=? WHERE id=?',
                       (status, message, media_id, self.ctx.now_text(), item['id']))
            db.execute('UPDATE admin_import_jobs SET updated_at=? WHERE id=?', (self.ctx.now_text(), item['job_id']))
            job = self._get(db, item['job_id'])
        if status in ('imported', 'duplicate') or job['status'] == 'cancelled':
            self._clean(item['staged_path'])

    def _work(self):
        try:
            while not self._stop.is_set() and not self._lease_lost.is_set():
                try:
                    if not self._leased and not self._acquire():
                        self._wake.wait(1)
                        self._wake.clear()
                        continue
                    pair = self._next()
                    if pair:
                        self._process(*pair)
                    else:
                        self._wake.wait(0.35)
                        self._wake.clear()
                except sqlite3.Error:
                    self._wake.wait(0.5)
                    self._wake.clear()
        finally:
            if self._leased:
                try:
                    with self.ctx.database() as db:
                        owned = db.execute('DELETE FROM admin_import_worker WHERE singleton=1 AND owner=?', (self.owner,)).rowcount
                        if owned:
                            db.execute("UPDATE admin_import_jobs SET status='paused',message=?,updated_at=? WHERE status='running'",
                                       ('服务已暂停，重新启动后可继续导入。', self.ctx.now_text()))
                except sqlite3.Error:
                    pass
                self._leased = False
            if self._file_lock:
                self._file_lock.close()
                self._file_lock = None

    def _router(self):
        router = APIRouter(prefix='/api/admin/imports', dependencies=[Depends(self.ctx.require_admin)])

        @router.get('')
        def listing(member=Depends(self.ctx.require_admin)):
            return self.list_jobs(member['child_id'])

        @router.post('')
        def create(body: NewImport, member=Depends(self.ctx.require_admin)):
            if body.source != 'upload':
                raise HTTPException(422, '请选择电脑上传，或使用服务器目录导入入口。')
            return self.get_job(self._create(body.name, 'upload', member, child_ids=body.child_ids), False)

        @router.get('/roots')
        def roots():
            return {'items': [{'id': key, 'name': title, 'path': str(path), 'available': path.is_dir()}
                              for key, (title, path) in self.roots.items()]}

        @router.post('/scan')
        def scan(body: DirectoryInput, member=Depends(self.ctx.require_admin)):
            return self._scan(body, member=member)[1]

        @router.post('/directory')
        def directory(body: DirectoryInput, member=Depends(self.ctx.require_admin)):
            entries, summary = self._scan(body, digest=False, member=member)
            if not entries:
                raise HTTPException(422, '这个文件夹中没有可导入的照片或视频。')
            with self.ctx.database() as db:
                job_id = self._create(body.name, 'directory', member, 'queued', db, body.child_ids)
                for item in entries:
                    self._insert_item(db, job_id, item['name'], size=item['size_bytes'], root_id=item['root_id'],
                                      source_path=item['source_path'], fallback=item['fallback_time'])
            self.start()
            self._wake.set()
            return self.get_job(job_id, False)

        @router.get('/{job_id}')
        def detail(job_id: str):
            return self.get_job(job_id)

        @router.post('/{job_id}/files')
        async def files(job_id: str, file: UploadFile = File(), fallback_captured_at: str | None = Form(default=None)):
            return await self.receive(job_id, file, fallback_captured_at)

        @router.post('/{job_id}/{operation}')
        def action(job_id: str, operation: str, member=Depends(self.ctx.require_admin)):
            return self.action(job_id, operation, member)

        return router
