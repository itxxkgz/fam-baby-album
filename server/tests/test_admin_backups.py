"""Backup, schedule and non-destructive restore checks with synthetic albums."""
from datetime import datetime
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sqlite3
import stat
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import uuid
import zipfile

from fastapi.testclient import TestClient
from fastapi import HTTPException
from PIL import Image

SERVER = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER))
from admin_backups import AdminBackupService, ZONE, next_run, sqlite_file


class AdminBackupTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='fam-backup-tests-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.environment = {'ALBUM_DATA': str(self.root / 'data'), 'ALBUM_LIBRARY': str(self.root / 'library'),
                            'ALBUM_BOOTSTRAP_TOKEN': '', 'ALBUM_PASSWORD': '', 'ALBUM_STORAGE_KEY': '',
                            'ALBUM_STORAGE_ROOTS': '', 'ALBUM_IMPORT_ROOTS': '', 'ALBUM_STORAGE_ALLOWED_ENDPOINTS': ''}
        self.app, self.client, self.service = self.load_album(self.environment)
        response = self.client.post('/api/login', json={'username': 'admin', 'password': 'admin'})
        self.assertEqual(response.status_code, 200, response.text)
        self.limited = {'Authorization': 'Bearer ' + response.json()['token']}
        response = self.client.post('/api/me/password', headers=self.limited, json={
            'current_password': 'admin', 'new_password': 'synthetic-private-password'})
        self.assertEqual(response.status_code, 200, response.text)
        self.headers = {'Authorization': 'Bearer ' + response.json()['token']}
        self.member = response.json()['member']

    def load_album(self, environment):
        name = '_backup_test_' + uuid.uuid4().hex
        spec = importlib.util.spec_from_file_location(name, SERVER / 'app.py')
        module = importlib.util.module_from_spec(spec)
        sys.modules[name] = module
        self.addCleanup(sys.modules.pop, name, None)
        with patch.dict(os.environ, environment):
            spec.loader.exec_module(module)
            service = getattr(module, 'admin_backups', None)
            if service is None:
                service = AdminBackupService(module)
                module.app.include_router(service.router)
        client = TestClient(module.app)
        self.addCleanup(client.close)
        self.addCleanup(service.stop)
        return module, client, service

    def photo(self, color=(180, 90, 50)):
        stream = io.BytesIO()
        Image.new('RGB', (38, 24), color).save(stream, 'JPEG')
        payload = stream.getvalue()
        response = self.client.post('/api/upload', headers=self.headers,
                                    data={'captured_at': '2020-09-08T14:15:16+08:00'},
                                    files={'file': ('测试原照片.jpg', payload, 'image/jpeg')})
        self.assertEqual(response.status_code, 200, response.text)
        return response.json(), payload

    def completed(self, mode='full'):
        response = self.client.post('/api/admin/backups', headers=self.headers, json={'mode': mode})
        self.assertEqual(response.status_code, 202, response.text)
        identity = response.json()['id']
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            item = self.service.get(identity)
            if item['status'] in ('completed', 'failed'):
                self.assertEqual(item['status'], 'completed', item['message'])
                return item
            time.sleep(.02)
        self.fail('备份任务没有在测试期限内完成')

    def test_full_backup_download_verify_and_independent_restore(self):
        photo, original = self.photo()
        child = self.client.get('/api/profile', headers=self.headers).json()['child_id']
        second = self.client.post('/api/children', headers=self.headers, json={'name': '另一个测试宝宝'}).json()['id']
        for identity, relationship in ((child, '爸爸'), (second, '叔叔')):
            changed = self.client.patch(f"/api/children/{identity}/members/{self.member['id']}",
                                        headers=self.headers, json={'relationship': relationship})
            self.assertEqual(changed.status_code, 200, changed.text)
        self.client.put('/api/media/' + photo['id'] + '/favorite', headers=self.headers, json={'favorite': True})
        self.client.post('/api/media/' + photo['id'] + '/comments', headers=self.headers, json={'text': '测试留言'})
        backup = self.completed()
        verified = self.client.post('/api/admin/backups/' + backup['id'] + '/verify', headers=self.headers)
        self.assertEqual(verified.status_code, 200, verified.text)
        self.assertTrue(verified.json()['verified'])
        preview = self.client.post('/api/admin/backups/' + backup['id'] + '/restore-preview', headers=self.headers).json()
        self.assertEqual((preview['photos'], preview['videos'], preview['members']), (1, 0, 1))
        download = self.client.get(backup['download_url'], headers=self.headers)
        self.assertEqual(download.status_code, 200)
        self.assertEqual(download.headers['cache-control'], 'no-store')
        with zipfile.ZipFile(io.BytesIO(download.content)) as archive:
            manifest = json.loads(archive.read('manifest.json'))
            self.assertEqual(archive.read(manifest['media'][0]['path']), original)
            self.assertIn('storage.key', archive.namelist())
        restored = self.client.post('/api/admin/backups/' + backup['id'] + '/restore', headers=self.headers,
                                    json={'current_password': 'synthetic-private-password'})
        self.assertEqual(restored.status_code, 200, restored.text)
        folder = Path(restored.json()['directory'])
        self.assertTrue(folder.is_relative_to(self.app.DATA / 'restored'))
        self.assertEqual(self.client.get(photo['file_url'], headers=self.headers).content, original)
        restored_env = dict(self.environment, ALBUM_DATA=str(folder / 'data'), ALBUM_LIBRARY=str(folder / 'library'))
        new_app, client, _ = self.load_album(restored_env)
        self.assertEqual(client.get('/api/me', headers=self.headers).status_code, 401)
        login = client.post('/api/login', json={'username': 'admin', 'password': 'synthetic-private-password'})
        self.assertEqual(login.status_code, 200, login.text)
        auth = {'Authorization': 'Bearer ' + login.json()['token']}
        result = client.get('/api/media/' + photo['id'], headers=auth).json()
        self.assertEqual(result['captured_at'], photo['captured_at'])
        self.assertEqual(result['uploader_id'], self.member['id'])
        self.assertTrue(result['favorite'])
        self.assertEqual(result['comments'][0]['text'], '测试留言')
        self.assertEqual(client.get(result['file_url'], headers=auth).content, original)
        for identity, relationship in ((child, '爸爸'), (second, '叔叔')):
            members = client.get(f'/api/children/{identity}/members', headers=auth).json()['items']
            self.assertEqual(next(item for item in members if item['id'] == self.member['id'])['relationship'], relationship)
        self.assertFalse(client.get('/api/status').json()['default_credentials'])
        self.assertEqual(client.get('/api/admin/backups', headers=auth).json()['items'], [])

    def test_metadata_backup_is_explicit_and_does_not_claim_originals(self):
        self.photo()
        backup = self.completed('metadata')
        self.assertIn('原照片仍在原存储', backup['message'])
        with zipfile.ZipFile(self.service._archive(backup['id'])) as archive:
            self.assertFalse(any(name.startswith('originals/') for name in archive.namelist()))
        result = self.service.restore(backup['id'], self.member, 'synthetic-private-password')
        self.assertIn('原照片仍需', result['message'])
        with sqlite_file(Path(result['directory']) / 'data/album.sqlite') as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM media').fetchone()[0], 1)
            self.assertEqual(db.execute('SELECT COUNT(*) FROM sessions').fetchone()[0], 0)

    def test_admin_only_default_password_gate_and_wrong_restore_password(self):
        self.assertEqual(self.client.get('/api/admin/backups').status_code, 401)
        invitation = self.client.post('/api/invitations', headers=self.headers, json={'role': 'viewer'}).json()
        joined = self.client.post('/api/invitations/accept', json={'code': invitation['code'], 'username': 'viewing.family',
                                  'password': 'synthetic-viewer-password', 'name': '测试家人'}).json()
        auth = {'Authorization': 'Bearer ' + joined['token']}
        self.assertEqual(self.client.get('/api/admin/backups', headers=auth).status_code, 403)
        self.assertEqual(self.client.post('/api/admin/backups', headers=auth, json={'mode': 'full'}).status_code, 403)
        backup = self.completed()
        self.assertEqual(self.client.post('/api/admin/backups/' + backup['id'] + '/restore', headers=self.headers,
                                         json={'current_password': 'incorrect-password'}).status_code, 401)
        self.assertEqual(list(self.service.restore_root.iterdir()), [])
        with self.app.database() as db:
            db.execute('UPDATE members SET password_change_required=1 WHERE id=?', (self.member['id'],))
        for method, path in [('GET', '/api/admin/backups'), ('POST', '/api/admin/backups'),
                             ('GET', backup['download_url']), ('PUT', '/api/admin/backups/plan')]:
            self.assertEqual(self.client.request(method, path, headers=self.headers, json={}).status_code, 403)

    def test_missing_original_or_changed_bytes_never_finishes(self):
        photo, _ = self.photo()
        with self.app.database() as db:
            row = dict(db.execute('SELECT * FROM media WHERE id=?', (photo['id'],)).fetchone())
        with self.app.storage_manager.materialize(row) as path:
            path.write_bytes(b'broken original')
        created = self.service.create(member_id=self.member['id'])
        deadline = time.monotonic() + 10
        while self.service.get(created['id'])['status'] not in ('failed', 'completed') and time.monotonic() < deadline:
            time.sleep(.02)
        self.assertEqual(self.service.get(created['id'])['status'], 'failed')
        self.assertFalse(self.service._archive(created['id']).exists())
        self.assertEqual(self.client.get('/api/admin/backups/' + created['id'] + '/download', headers=self.headers).status_code, 409)

    def test_cloud_and_legacy_files_use_storage_abstraction(self):
        first, data = self.photo()
        with self.app.database() as db:
            db.execute("UPDATE media SET storage_id='synthetic-cloud',root_kind='storage',object_key='photo.jpg' WHERE id=?", (first['id'],))
        reads = []

        def stream(row, first=0, last=None):
            reads.append(row['storage_id'])
            yield data

        with patch.object(self.app.storage_manager, 'open_media', side_effect=stream):
            backup = self.completed()
        self.assertEqual(reads, ['synthetic-cloud'])
        self.assertTrue(self.service.verify(backup['id'])['verified'])

    def test_corrupt_archive_and_path_traversal_are_rejected(self):
        self.photo()
        backup = self.completed()
        self.service.stop()
        path = self.service._archive(backup['id'])
        with zipfile.ZipFile(path) as archive:
            original = {name: archive.read(name) for name in archive.namelist()}
        with zipfile.ZipFile(path, 'w') as archive:
            for name, data in original.items():
                archive.writestr(name, data)
            archive.writestr('../outside.txt', b'not allowed')
        self.assertEqual(self.client.post('/api/admin/backups/' + backup['id'] + '/verify', headers=self.headers).status_code, 422)
        self.assertEqual(self.client.post('/api/admin/backups/' + backup['id'] + '/restore', headers=self.headers,
                                         json={'current_password': 'synthetic-private-password'}).status_code, 422)
        self.assertEqual(list(self.service.restore_root.iterdir()), [])
        self.assertFalse((self.root / 'outside.txt').exists())
        with zipfile.ZipFile(path, 'w') as archive:
            for name, data in original.items():
                archive.writestr(name, b'modified' if name == 'storage.key' else data)
        self.assertEqual(self.client.post('/api/admin/backups/' + backup['id'] + '/verify', headers=self.headers).status_code, 422)

    def test_symlink_archive_and_missing_files_are_rejected(self):
        backup = self.completed('metadata')
        self.service.stop()
        path = self.service._archive(backup['id'])
        with zipfile.ZipFile(path, 'w') as archive:
            item = zipfile.ZipInfo('storage.key')
            item.create_system = 3
            item.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(item, '/etc/passwd')
        with self.assertRaises(HTTPException):
            self.service.verify(backup['id'])
        path.unlink()
        self.assertEqual(self.client.get(backup['download_url'], headers=self.headers).status_code, 404)

    def test_plan_validation_next_date_and_server_side_schedule(self):
        daily = {'enabled': True, 'frequency': 'daily', 'time': '03:00', 'weekday': 0, 'mode': 'metadata', 'keep': 2}
        self.assertEqual(next_run(daily, datetime(2026, 11, 17, 4, tzinfo=ZONE)), '2026-11-18T03:00:00+08:00')
        self.assertEqual(next_run(dict(daily, frequency='weekly'), datetime(2026, 11, 17, 4, tzinfo=ZONE)), '2026-11-23T03:00:00+08:00')
        for bad in [dict(daily, time='25:10'), dict(daily, mode='unknown'), dict(daily, frequency='hourly')]:
            self.assertEqual(self.client.put('/api/admin/backups/plan', headers=self.headers, json=bad).status_code, 422)
        with patch.object(self.service, 'start'):
            response = self.client.put('/api/admin/backups/plan', headers=self.headers, json=daily)
        self.assertEqual(response.status_code, 200)
        plan = response.json()
        plan['next_run'] = '2026-01-01T03:00:00+08:00'
        with self.app.database() as db:
            db.execute('UPDATE admin_backup_plan SET value=?', (json.dumps(plan),))
        second = AdminBackupService(self.app)
        self.addCleanup(second.stop)
        self.service.start()
        second.start()
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            jobs = self.service.list_backups()['items']
            if jobs and jobs[0]['status'] == 'completed':
                break
            time.sleep(.02)
        self.assertEqual(len(self.service.list_backups()['items']), 1)
        self.assertEqual(jobs[0]['status'], 'completed')
        self.assertGreater(datetime.fromisoformat(self.service.plan()['next_run']), datetime.now(ZONE))

    def test_retention_only_removes_old_completed_backups(self):
        with patch.object(self.service, 'start'):
            self.service.save_plan({'enabled': False, 'keep': 2})
        ids = [self.completed('metadata')['id'] for _ in range(3)]
        deadline = time.monotonic() + 5
        while len(self.service.list_backups()['items']) > 2 and time.monotonic() < deadline:
            time.sleep(.02)
        self.assertEqual({item['id'] for item in self.service.list_backups()['items']}, set(ids[1:]))
        self.assertFalse(self.service._archive(ids[0]).exists())
        self.assertTrue(self.service._archive(ids[-1]).exists())

    def test_expired_worker_is_failed_and_queued_job_survives_restart(self):
        with patch.object(self.service, 'start'):
            queued = self.service.create('metadata', self.member['id'])
        with self.app.database() as db:
            old = uuid.uuid4().hex
            db.execute("INSERT INTO admin_backups(id,name,mode,status,created_at,owner) VALUES (?,?,?,'running',?,?)",
                       (old, '上次备份', 'full', '2026-01-01T01:00:00+08:00', 'expired-owner'))
            db.execute('UPDATE admin_backup_lease SET owner=?,expires_at=0', ('expired-owner',))
        partial = self.service._archive(old, partial=True)
        partial.write_bytes(b'incomplete archive')
        snapshot = self.service.directory / ('snapshot-' + old + '-test')
        snapshot.mkdir()
        (snapshot / 'album.sqlite').write_bytes(b'incomplete database')
        self.service.start()
        deadline = time.monotonic() + 10
        while self.service.get(queued['id'])['status'] not in ('completed', 'failed') and time.monotonic() < deadline:
            time.sleep(.02)
        self.assertEqual(self.service.get(old)['status'], 'failed')
        self.assertEqual(self.service.get(queued['id'])['status'], 'completed')
        self.assertFalse(partial.exists())
        self.assertFalse(snapshot.exists())


if __name__ == '__main__':
    unittest.main()
