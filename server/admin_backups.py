"""Private, durable backups and non-destructive restore copies for administrators."""
from __future__ import annotations

import contextlib
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sqlite3
import stat
import tempfile
import threading
import time
import uuid
import zipfile

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

ZONE = timezone(timedelta(hours=8))
CHUNK = 1024 * 1024
STATUSES = {'queued': '等待备份', 'running': '正在备份', 'completed': '备份完成', 'failed': '备份未完成'}
DEFAULT_PLAN = {'enabled': False, 'frequency': 'daily', 'time': '03:00', 'weekday': 0,
                'mode': 'full', 'keep': 7, 'next_run': None}


def now_text():
    return datetime.now(ZONE).isoformat(timespec='seconds')


@contextlib.contextmanager
def sqlite_file(path):
    connection = sqlite3.connect(path)
    try:
        yield connection
        connection.commit()
    except BaseException:
        connection.rollback()
        raise
    finally:
        connection.close()


def next_run(plan, after=None):
    if not plan['enabled']:
        return None
    after = after or datetime.now(ZONE)
    after = after.astimezone(ZONE)
    hour, minute = map(int, plan['time'].split(':'))
    result = after.replace(hour=hour, minute=minute, second=0, microsecond=0)
    if plan['frequency'] == 'weekly':
        result += timedelta(days=(plan['weekday'] - result.weekday()) % 7)
        if result <= after:
            result += timedelta(days=7)
    elif result <= after:
        result += timedelta(days=1)
    return result.isoformat(timespec='seconds')


class BackupInput(BaseModel):
    mode: str = 'full'


class BackupPlan(BaseModel):
    enabled: bool = False
    frequency: str = 'daily'
    time: str = '03:00'
    weekday: int = Field(default=0, ge=0, le=6)
    mode: str = 'full'
    keep: int = Field(default=7, ge=1, le=90)


class RestoreInput(BaseModel):
    current_password: str = Field(min_length=1, max_length=200)


class AdminBackupService:
    def __init__(self, ctx):
        self.ctx = ctx
        self.directory = Path(ctx.DATA) / 'backups'
        self.restore_root = Path(ctx.DATA) / 'restored'
        self._private_directory(self.directory)
        self._private_directory(self.restore_root)
        self.owner = uuid.uuid4().hex
        self._stopping = threading.Event()
        self._wake = threading.Event()
        self._thread = None
        self._thread_lock = threading.Lock()
        self._heartbeat_at = 0.0
        self._effective_key = os.environ.get('ALBUM_STORAGE_KEY', '').encode('ascii')
        if not self._effective_key:
            key_path = Path(ctx.DATA) / 'storage.key'
            self._effective_key = key_path.read_bytes().strip() if key_path.is_file() else b''
        self.max_archive_bytes = int(os.getenv('ALBUM_BACKUP_MAX_BYTES', str(1024 ** 4)))
        with ctx.database() as db:
            db.execute('''CREATE TABLE IF NOT EXISTS admin_backups (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, mode TEXT NOT NULL, status TEXT NOT NULL,
                created_at TEXT NOT NULL, finished_at TEXT, bytes INTEGER NOT NULL DEFAULT 0,
                total INTEGER NOT NULL DEFAULT 0, processed INTEGER NOT NULL DEFAULT 0,
                message TEXT NOT NULL DEFAULT '', verified INTEGER NOT NULL DEFAULT 0,
                requested_by TEXT, owner TEXT)''')
            db.execute('''CREATE TABLE IF NOT EXISTS admin_backup_plan (
                singleton INTEGER PRIMARY KEY CHECK(singleton=1), value TEXT NOT NULL)''')
            db.execute('INSERT OR IGNORE INTO admin_backup_plan VALUES (1,?)', (json.dumps(DEFAULT_PLAN),))
            db.execute('''CREATE TABLE IF NOT EXISTS admin_backup_lease (
                singleton INTEGER PRIMARY KEY CHECK(singleton=1), owner TEXT, expires_at REAL NOT NULL)''')
            db.execute('INSERT OR IGNORE INTO admin_backup_lease VALUES (1,NULL,0)')
        self.router = self._router()

    @staticmethod
    def _private_directory(path):
        if path.is_symlink() or path.resolve() != path.absolute():
            raise RuntimeError('备份目录不能是符号链接，请使用独立的真实目录。')
        path.mkdir(parents=True, exist_ok=True)
        with contextlib.suppress(OSError):
            path.chmod(0o700)

    def _archive(self, identity, partial=False):
        if not re.fullmatch(r'[a-f0-9]{32}', identity):
            raise HTTPException(404, '没有找到这份备份。')
        if self.directory.is_symlink() or self.directory.resolve() != self.directory.absolute():
            raise HTTPException(409, '备份目录发生变化，请检查服务器上的目录设置。')
        target = self.directory / (identity + ('.partial' if partial else '.zip'))
        if target.is_symlink() or target.resolve().parent != self.directory.resolve():
            raise HTTPException(409, '备份文件位置不正确，请检查服务器上的目录设置。')
        return target

    def start(self):
        with self._thread_lock:
            if self._thread and self._thread.is_alive():
                self._wake.set()
                return
            self._stopping.clear()
            self._thread = threading.Thread(target=self._worker, name='fam-backups', daemon=True)
            self._thread.start()

    def stop(self):
        self._stopping.set()
        self._wake.set()
        if self._thread and self._thread is not threading.current_thread():
            self._thread.join(timeout=5)
        with self.ctx.database() as db:
            db.execute('UPDATE admin_backup_lease SET owner=NULL,expires_at=0 WHERE owner=?', (self.owner,))

    @staticmethod
    def _dto(row):
        row = dict(row)
        result = {key: row[key] for key in ('id', 'name', 'mode', 'status', 'created_at', 'finished_at',
                                           'bytes', 'total', 'processed', 'message')}
        result['verified'] = bool(row['verified'])
        result['status_label'] = STATUSES.get(row['status'], '等待处理')
        if row['status'] == 'completed':
            result['download_url'] = '/api/admin/backups/' + row['id'] + '/download'
        return result

    def list_backups(self):
        with self.ctx.database() as db:
            rows = db.execute('SELECT * FROM admin_backups ORDER BY created_at DESC,id DESC LIMIT 200').fetchall()
        return {'items': [self._dto(row) for row in rows]}

    def get(self, identity, completed=False):
        self._archive(identity)
        with self.ctx.database() as db:
            row = db.execute('SELECT * FROM admin_backups WHERE id=?', (identity,)).fetchone()
        if row is None:
            raise HTTPException(404, '没有找到这份备份。')
        if completed and row['status'] != 'completed':
            raise HTTPException(409, '这份备份还没有完成，请选择已完成的备份。')
        return self._dto(row)

    def plan(self):
        with self.ctx.database() as db:
            return json.loads(db.execute('SELECT value FROM admin_backup_plan WHERE singleton=1').fetchone()[0])

    def save_plan(self, values):
        plan = values.model_dump() if isinstance(values, BackupPlan) else BackupPlan(**values).model_dump()
        if plan['frequency'] not in ('daily', 'weekly') or plan['mode'] not in ('metadata', 'full'):
            raise HTTPException(422, '请选择正确的备份频率和备份内容。')
        if not re.fullmatch(r'(?:[01]\d|2[0-3]):[0-5]\d', plan['time']):
            raise HTTPException(422, '请填写有效的备份时间，例如 03:00。')
        plan['next_run'] = next_run(plan)
        with self.ctx.database() as db:
            db.execute('UPDATE admin_backup_plan SET value=? WHERE singleton=1', (json.dumps(plan),))
        self.start()
        self._wake.set()
        return plan

    def _insert_job(self, db, mode, member_id=None, scheduled=False):
        if mode not in ('full', 'metadata'):
            raise HTTPException(422, '请选择备份相册资料，或连同原照片一起备份。')
        identity = uuid.uuid4().hex
        stamp = datetime.now(ZONE)
        name = stamp.strftime('%Y年%m月%d日 %H:%M') + (' · 完整备份' if mode == 'full' else ' · 相册资料备份')
        db.execute('''INSERT INTO admin_backups(id,name,mode,status,created_at,requested_by,message)
            VALUES (?,?,?,'queued',?,?,?)''', (identity, name, mode, stamp.isoformat(timespec='microseconds'),
                                             member_id, '等待自动备份' if scheduled else '正在准备备份'))
        return identity

    def create(self, mode='full', member_id=None):
        with self.ctx.database() as db:
            db.execute('BEGIN IMMEDIATE')
            if db.execute("SELECT 1 FROM admin_backups WHERE status IN ('queued','running') LIMIT 1").fetchone():
                raise HTTPException(409, '已有备份任务正在处理，请等它完成后再创建。')
            identity = self._insert_job(db, mode, member_id)
        result = self.get(identity)
        self.start()
        self._wake.set()
        return result

    def _acquire_lease(self):
        interrupted = []
        with self.ctx.database() as db:
            db.execute('BEGIN IMMEDIATE')
            lease = db.execute('SELECT * FROM admin_backup_lease WHERE singleton=1').fetchone()
            if lease['owner'] not in (None, self.owner) and lease['expires_at'] > time.time():
                return False
            if lease['owner'] != self.owner:
                interrupted = [row[0] for row in db.execute("SELECT id FROM admin_backups WHERE status='running'")]
                db.execute("""UPDATE admin_backups SET status='failed',finished_at=?,message=?,owner=NULL
                    WHERE status='running'""", (now_text(), '上次备份被服务中断，没有生成完整备份，请重新备份。'))
            db.execute('UPDATE admin_backup_lease SET owner=?,expires_at=? WHERE singleton=1',
                       (self.owner, time.time() + 90))
        for identity in interrupted:
            with contextlib.suppress(OSError, HTTPException):
                self._archive(identity, partial=True).unlink(missing_ok=True)
                for folder in self.directory.glob('snapshot-' + identity + '-*'):
                    if folder.is_dir() and not folder.is_symlink() and folder.resolve().parent == self.directory.resolve():
                        shutil.rmtree(folder)
        self._heartbeat_at = time.monotonic()
        return True

    def _checkpoint(self, force=False):
        if self._stopping.is_set():
            raise RuntimeError('备份因服务停止而中断，请重新创建备份。')
        if force or time.monotonic() - self._heartbeat_at > 10:
            with self.ctx.database() as db:
                updated = db.execute('UPDATE admin_backup_lease SET expires_at=? WHERE singleton=1 AND owner=?',
                                     (time.time() + 90, self.owner))
                if updated.rowcount != 1:
                    raise RuntimeError('备份任务已由另一个服务接管，请稍后查看结果。')
            self._heartbeat_at = time.monotonic()

    def _schedule_due(self):
        with self.ctx.database() as db:
            db.execute('BEGIN IMMEDIATE')
            plan = json.loads(db.execute('SELECT value FROM admin_backup_plan WHERE singleton=1').fetchone()[0])
            if not plan['enabled'] or not plan.get('next_run'):
                return
            if datetime.fromisoformat(plan['next_run']) > datetime.now(ZONE):
                return
            if not db.execute("SELECT 1 FROM admin_backups WHERE status IN ('queued','running') LIMIT 1").fetchone():
                self._insert_job(db, plan['mode'], scheduled=True)
            plan['next_run'] = next_run(plan)
            db.execute('UPDATE admin_backup_plan SET value=? WHERE singleton=1', (json.dumps(plan),))

    def _worker(self):
        while not self._stopping.is_set():
            try:
                if self._acquire_lease():
                    self._schedule_due()
                    with self.ctx.database() as db:
                        db.execute('BEGIN IMMEDIATE')
                        row = db.execute("SELECT * FROM admin_backups WHERE status='queued' ORDER BY created_at,id LIMIT 1").fetchone()
                        if row:
                            db.execute("UPDATE admin_backups SET status='running',owner=?,message=? WHERE id=?",
                                       (self.owner, '正在保存相册资料', row['id']))
                    if row:
                        self._run_backup(dict(row))
                        continue
            except (sqlite3.Error, OSError):
                # A later tick can retry a transient busy database or unavailable disk.
                pass
            self._wake.wait(2)
            self._wake.clear()

    def _record(self, identity, **values):
        allowed = {'status', 'finished_at', 'bytes', 'total', 'processed', 'message', 'verified'}
        assert set(values).issubset(allowed)
        with self.ctx.database() as db:
            db.execute('UPDATE admin_backups SET ' + ','.join(key + '=?' for key in values) + ' WHERE id=?',
                       [*values.values(), identity])

    def _snapshot(self, path):
        with self.ctx.database() as source, sqlite_file(path) as destination:
            source.backup(destination, pages=512, progress=lambda *_: self._checkpoint())
            if destination.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
                raise RuntimeError('相册资料未能完整读取，请检查数据库后重试。')
        path.chmod(0o600)

    def _run_backup(self, job):
        identity = job['id']
        temporary = self._archive(identity, partial=True)
        destination = self._archive(identity)
        finished = threading.Event()

        def keep_lease():
            while not finished.wait(15):
                if self._stopping.is_set():
                    return
                with contextlib.suppress(sqlite3.Error):
                    with self.ctx.database() as db:
                        db.execute('UPDATE admin_backup_lease SET expires_at=? WHERE singleton=1 AND owner=?',
                                   (time.time() + 90, self.owner))

        heartbeat = threading.Thread(target=keep_lease, name='fam-backup-heartbeat', daemon=True)
        heartbeat.start()
        try:
            if temporary.exists():
                temporary.unlink()
            with tempfile.TemporaryDirectory(prefix='snapshot-' + identity + '-', dir=self.directory) as folder:
                database_path = Path(folder) / 'album.sqlite'
                self._snapshot(database_path)
                with sqlite_file(database_path) as snapshot:
                    snapshot.row_factory = sqlite3.Row
                    rows = [dict(row) for row in snapshot.execute('SELECT * FROM media ORDER BY id')]
                    settings = dict(snapshot.execute('SELECT key,value FROM settings'))
                    members_count = snapshot.execute('SELECT COUNT(*) FROM members').fetchone()[0]
                amount = sum(row['size_bytes'] for row in rows) if job['mode'] == 'full' else 0
                amount += database_path.stat().st_size + 16 * 1024 * 1024
                if amount > self.max_archive_bytes:
                    raise RuntimeError('本次备份超过服务器允许的备份大小，请调整备份范围或服务器设置。')
                if shutil.disk_usage(self.directory).free < amount + 64 * 1024 * 1024:
                    raise RuntimeError('服务器剩余空间不足以完成这份备份，请先释放空间。')
                if not self._effective_key:
                    raise RuntimeError('没有找到相册存储密钥，无法创建可恢复的备份。')
                manifest = {'format': 'fam-album-backup', 'version': 1, 'mode': job['mode'],
                            'created_at': now_text(), 'album_name': settings.get('name', '家庭相册'),
                            'photos': sum(row['kind'] == 'photo' for row in rows),
                            'videos': sum(row['kind'] == 'video' for row in rows), 'members': members_count,
                            'library': str(self.ctx.LIBRARY), 'entries': {}, 'media': []}
                total = len(rows) if job['mode'] == 'full' else 0
                self._record(identity, total=total, processed=0)

                def add(archive, name, chunks, expected_size=None, expected_hash=None):
                    digest, length = hashlib.sha256(), 0
                    with archive.open(name, 'w', force_zip64=True) as target:
                        for block in chunks:
                            self._checkpoint()
                            length += len(block)
                            if length > self.max_archive_bytes:
                                raise RuntimeError('文件超出允许的备份大小，请检查原存储。')
                            target.write(block)
                            digest.update(block)
                    actual = digest.hexdigest()
                    if expected_size is not None and length != expected_size:
                        raise RuntimeError('有原文件未能完整读取，本次备份没有完成，请检查照片存放位置。')
                    if expected_hash and actual != expected_hash:
                        raise RuntimeError('有原文件与相册记录不一致，本次备份没有完成，请检查照片存放位置。')
                    manifest['entries'][name] = {'size': length, 'sha256': actual}

                with zipfile.ZipFile(temporary, 'x', compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
                    temporary.chmod(0o600)
                    with database_path.open('rb') as stream:
                        add(archive, 'album.sqlite', iter(lambda: stream.read(CHUNK), b''))
                    add(archive, 'storage.key', [self._effective_key])
                    avatar = Path(self.ctx.DATA) / 'avatar.jpg'
                    if avatar.is_file() and not avatar.is_symlink():
                        with avatar.open('rb') as stream:
                            add(archive, 'avatar.jpg', iter(lambda: stream.read(CHUNK), b''))
                    if job['mode'] == 'full':
                        for index, row in enumerate(rows, 1):
                            suffix = Path(row['filename']).suffix.lower()
                            if not re.fullmatch(r'\.[a-z0-9]{1,12}', suffix):
                                suffix = '.bin'
                            archive_name = f'originals/{index:08d}{suffix}'
                            add(archive, archive_name, self.ctx.storage_manager.open_media(row),
                                row['size_bytes'], row['sha256'])
                            manifest['media'].append({'id': row['id'], 'path': archive_name,
                                                      'captured_at': row['captured_at']})
                            self._record(identity, processed=index, message=f'已保存 {index} / {total} 份原文件')
                    archive.writestr('manifest.json', json.dumps(manifest, ensure_ascii=False).encode('utf-8'))
                self._checkpoint(force=True)
                temporary.replace(destination)
                self._record(identity, status='completed', finished_at=now_text(), bytes=destination.stat().st_size,
                             message='相册资料和全部原文件已备份' if job['mode'] == 'full' else '相册资料已备份；原照片仍在原存储中',
                             verified=0)
                with contextlib.suppress(OSError):
                    self._prune()
        except Exception as error:
            message = str(error) if isinstance(error, RuntimeError) else '备份没有完成，请检查存储连接和剩余空间后重试。'
            if len(message) > 200:
                message = '备份没有完成，请检查存储连接和剩余空间后重试。'
            with contextlib.suppress(Exception):
                temporary.unlink(missing_ok=True)
                self._record(identity, status='failed', finished_at=now_text(), message=message, verified=0)
        finally:
            finished.set()
            heartbeat.join(timeout=2)

    def _prune(self):
        keep = self.plan()['keep']
        with self.ctx.database() as db:
            rows = db.execute("SELECT id FROM admin_backups WHERE status='completed' ORDER BY created_at DESC,id DESC").fetchall()
        for row in rows[keep:]:
            path = self._archive(row['id'])
            path.unlink(missing_ok=True)
            with self.ctx.database() as db:
                db.execute("DELETE FROM admin_backups WHERE id=? AND status='completed'", (row['id'],))

    def _read_manifest(self, archive):
        entries = archive.infolist()
        if not entries or len(entries) > 500000:
            raise HTTPException(422, '备份中的文件数量不正确，无法继续。')
        names, total = set(), 0
        for entry in entries:
            name = entry.filename
            parts = PurePosixPath(name).parts
            if (not name or '\\' in name or ':' in name or name.startswith('/') or
                    any(part in ('', '.', '..') for part in parts) or name in names or entry.is_dir() or
                    stat.S_ISLNK(entry.external_attr >> 16) or entry.flag_bits & 1):
                raise HTTPException(422, '备份内含不安全的文件位置，已停止恢复。')
            if name not in {'manifest.json', 'album.sqlite', 'storage.key', 'avatar.jpg'} and not re.fullmatch(r'originals/\d{8}\.[a-z0-9]{1,12}', name):
                raise HTTPException(422, '备份包含无法识别的文件，已停止恢复。')
            names.add(name)
            total += entry.file_size
            if total > self.max_archive_bytes:
                raise HTTPException(422, '备份解开后超过服务器允许的大小，无法继续。')
        if not {'manifest.json', 'album.sqlite', 'storage.key'}.issubset(names):
            raise HTTPException(422, '备份缺少相册资料或存储密钥，无法恢复。')
        if archive.getinfo('manifest.json').file_size > 128 * 1024 * 1024:
            raise HTTPException(422, '备份说明文件过大，无法读取。')
        try:
            manifest = json.loads(archive.read('manifest.json'))
            assert manifest['format'] == 'fam-album-backup' and manifest['version'] == 1
            assert manifest['mode'] in ('full', 'metadata')
            assert isinstance(manifest['album_name'], str) and isinstance(manifest['created_at'], str)
            assert all(isinstance(manifest[key], int) and manifest[key] >= 0 for key in ('photos', 'videos', 'members'))
            assert isinstance(manifest['entries'], dict) and set(manifest['entries']) == names - {'manifest.json'}
            assert isinstance(manifest['media'], list)
            seen_ids, seen_paths = set(), set()
            for item in manifest['media']:
                assert isinstance(item['id'], str) and item['id'] not in seen_ids
                assert item['path'] in names and item['path'].startswith('originals/') and item['path'] not in seen_paths
                seen_ids.add(item['id'])
                seen_paths.add(item['path'])
            assert seen_paths == {name for name in names if name.startswith('originals/')}
            if manifest['mode'] == 'metadata':
                assert not seen_paths
            for name, expected in manifest['entries'].items():
                assert isinstance(expected['size'], int) and expected['size'] >= 0
                assert re.fullmatch('[0-9a-f]{64}', expected['sha256'])
                assert archive.getinfo(name).file_size == expected['size']
        except (KeyError, TypeError, ValueError, AssertionError):
            raise HTTPException(422, '备份说明与文件内容不一致，无法继续。') from None
        return manifest

    def _verify(self, identity):
        self.get(identity, completed=True)
        target = self._archive(identity)
        try:
            with zipfile.ZipFile(target) as archive:
                manifest = self._read_manifest(archive)
                for name, expected in manifest['entries'].items():
                    digest, length = hashlib.sha256(), 0
                    with archive.open(name) as stream:
                        for block in iter(lambda: stream.read(CHUNK), b''):
                            length += len(block)
                            if length > expected['size']:
                                raise HTTPException(422, '备份文件大小不正确，无法继续。')
                            digest.update(block)
                    if length != expected['size'] or digest.hexdigest() != expected['sha256']:
                        raise HTTPException(422, '备份中的文件未通过完整性检查，请改用另一份备份。')
            return manifest
        except (zipfile.BadZipFile, OSError, RuntimeError, EOFError):
            raise HTTPException(422, '备份文件无法完整读取，请检查文件或改用另一份备份。') from None

    def verify(self, identity):
        try:
            manifest = self._verify(identity)
        except HTTPException:
            with contextlib.suppress(Exception):
                self._record(identity, verified=0)
            raise
        self._record(identity, verified=1)
        return {'verified': True, 'files': len(manifest['entries']), 'message': '备份文件完整，可以用于恢复副本。'}

    def preview(self, identity):
        manifest = self._verify(identity)
        return {key: manifest[key] for key in ('created_at', 'photos', 'videos', 'members', 'mode')} | {
            'name': manifest['album_name'],
            'message': ('将恢复到独立副本，不覆盖当前相册；启用副本后使用备份中的账号登录。' if manifest['mode'] == 'full'
                        else '这份备份只含相册资料，原照片仍需连接原存储；恢复副本不会覆盖当前相册。')}

    def restore(self, identity, member, password):
        with self.ctx.database() as db:
            current = db.execute('SELECT * FROM members WHERE id=?', (member['id'],)).fetchone()
            if not self.ctx.identities.check_password(current, password):
                raise HTTPException(401, '当前密码不正确，请重新输入。')
        manifest = self._verify(identity)
        required = sum(entry['size'] for entry in manifest['entries'].values())
        if shutil.disk_usage(self.restore_root).free < required + 64 * 1024 * 1024:
            raise HTTPException(409, '服务器剩余空间不足以恢复副本，请先释放空间。')
        if self.restore_root.is_symlink() or self.restore_root.resolve() != self.restore_root.absolute():
            raise HTTPException(409, '恢复目录发生变化，请检查服务器目录。')
        identity_part = uuid.uuid4().hex[:10]
        folder = self.restore_root / (datetime.now(ZONE).strftime('%Y%m%d-%H%M%S-') + identity_part)
        folder.mkdir(mode=0o700)
        data = folder / 'data'
        library = folder / 'library'
        data.mkdir(mode=0o700)
        library.mkdir(mode=0o700)
        uploads = data / 'uploads'
        uploads.mkdir(mode=0o700)
        try:
            with zipfile.ZipFile(self._archive(identity)) as archive:
                for name in ('album.sqlite', 'storage.key', 'avatar.jpg'):
                    if name in manifest['entries']:
                        self._extract_verified(archive, name, data / name, manifest['entries'][name])
                original_map = {}
                if manifest['mode'] == 'full':
                    (uploads / 'restored').mkdir(mode=0o700)
                    for item in manifest['media']:
                        relative = 'restored/' + PurePosixPath(item['path']).name
                        target = uploads / relative
                        self._extract_verified(archive, item['path'], target, manifest['entries'][item['path']])
                        with contextlib.suppress(ValueError, OSError):
                            stamp = datetime.fromisoformat(item['captured_at']).timestamp()
                            os.utime(target, (stamp, stamp))
                        original_map[item['id']] = relative
            with sqlite_file(data / 'album.sqlite') as db:
                db.execute('PRAGMA trusted_schema=OFF')
                if db.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
                    raise HTTPException(422, '备份中的相册资料损坏，无法恢复。')
                db.execute('DELETE FROM sessions')
                tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
                for table in sorted(tables):
                    if table.startswith('admin_') and re.fullmatch('[a-z_]+', table):
                        db.execute('DELETE FROM "' + table + '"')
                if manifest['mode'] == 'full':
                    rows = db.execute('SELECT id,size_bytes,sha256 FROM media').fetchall()
                    if {row[0] for row in rows} != set(original_map):
                        raise HTTPException(422, '备份中的原文件与相册资料不匹配，无法恢复。')
                    entries = {item['id']: manifest['entries'][item['path']] for item in manifest['media']}
                    for media_id, size, digest in rows:
                        if entries[media_id]['size'] != size or entries[media_id]['sha256'] != digest:
                            raise HTTPException(422, '备份中的原文件与相册记录不一致，无法恢复。')
                        db.execute("UPDATE media SET storage_id='local-default',object_key=?,root_kind='storage',relative_path=? WHERE id=?",
                                   (original_map[media_id], original_map[media_id], media_id))
                    # A complete copy is self-contained; cloud credentials are unnecessary in it.
                    db.execute('DELETE FROM storage_configs')
                    config = {'provider': 'local', 'local_path': str(uploads.resolve()), 'endpoint': '',
                              'region': '', 'bucket': '', 'prefix': '', 'addressing_style': 'auto'}
                    db.execute('''INSERT INTO storage_configs(id,name,provider,config_json,credentials_ciphertext,is_default,
                        tested_at,created_at,updated_at,write_started) VALUES ('local-default',?,'local',?,NULL,1,?,?,?,1)''',
                               ('恢复副本的服务器', json.dumps(config), now_text(), now_text(), now_text()))
                if db.execute('PRAGMA foreign_key_check').fetchone():
                    raise HTTPException(422, '备份中的相册关系不完整，无法恢复。')
                db.commit()
                db.execute('PRAGMA journal_mode=DELETE')
            instructions = ('FAM宝贝相册 · 恢复副本\n\n当前相册没有被覆盖或切换。\n'
                            '这份副本的数据目录：' + str(data.resolve()) + '\n'
                            '启动另一个相册服务时，将 ALBUM_DATA 指向上面的 data 目录；使用备份中的账号密码登录。\n')
            if manifest['mode'] == 'full':
                instructions += '所有原文件已保存到副本中。ALBUM_LIBRARY 可指向副本中的 library 目录。\n'
            else:
                instructions += ('这份副本只恢复相册资料，没有复制原照片。仍需连接原 NAS 或云存储；'
                                 '本地存储目录需重新允许访问，ALBUM_LIBRARY 需指向原图库：' + manifest.get('library', '') + '\n'
                                 '旧版保存在原 data/uploads 的文件也需另外复制到本副本的 data/uploads。\n')
            instructions += '已清除旧登录和后台任务，当前账号、照片归属与拍摄时间保留。\n'
            (folder / '启用副本说明.txt').write_text(instructions, encoding='utf-8')
        except BaseException:
            # Only a newly allocated, checked child of restored may be cleaned up.
            if folder.resolve().parent == self.restore_root.resolve() and not folder.is_symlink():
                shutil.rmtree(folder)
            raise
        return {'message': ('独立副本已恢复，当前相册未改变。启用步骤已保存在副本中的说明文件。' if manifest['mode'] == 'full'
                            else '相册资料已恢复为独立副本，原照片仍需连接原存储；请查看副本中的启用说明。'),
                'directory': str(folder.resolve())}

    @staticmethod
    def _extract_verified(archive, name, path, expected):
        digest, length = hashlib.sha256(), 0
        with archive.open(name) as source, path.open('xb') as target:
            for block in iter(lambda: source.read(CHUNK), b''):
                length += len(block)
                if length > expected['size']:
                    raise HTTPException(422, '备份中的文件大小不正确，已停止恢复。')
                target.write(block)
                digest.update(block)
        path.chmod(0o600)
        if length != expected['size'] or digest.hexdigest() != expected['sha256']:
            raise HTTPException(422, '备份在恢复过程中发生变化，已停止恢复。')

    def _router(self):
        router = APIRouter(prefix='/api/admin/backups', dependencies=[Depends(self.ctx.require_admin)])

        @router.get('')
        def listing():
            return self.list_backups()

        @router.post('', status_code=202)
        def create(body: BackupInput, member=Depends(self.ctx.require_admin)):
            return self.create(body.mode, member['id'])

        @router.get('/plan')
        def plan():
            return self.plan()

        @router.put('/plan')
        def save_plan(body: BackupPlan):
            return self.save_plan(body)

        @router.get('/{identity}')
        def detail(identity: str):
            return self.get(identity)

        @router.get('/{identity}/download')
        def download(identity: str):
            item = self.get(identity, completed=True)
            target = self._archive(identity)
            if not target.is_file():
                raise HTTPException(404, '备份文件已不在服务器上，请检查备份目录。')
            return FileResponse(target, filename=item['name'].replace(':', '时') + '.zip', media_type='application/zip',
                                headers={'Cache-Control': 'no-store'})

        @router.post('/{identity}/verify')
        def verify(identity: str):
            return self.verify(identity)

        @router.post('/{identity}/restore-preview')
        def preview(identity: str):
            return self.preview(identity)

        @router.post('/{identity}/restore')
        def restore(identity: str, body: RestoreInput, member=Depends(self.ctx.require_admin)):
            return self.restore(identity, member, body.current_password)

        return router
