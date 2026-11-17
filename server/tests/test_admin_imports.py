"""Real queue and upload checks using only temporary synthetic family albums."""
import asyncio
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import uuid

from fastapi import HTTPException
from fastapi.testclient import TestClient
from PIL import Image


class AdminImportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='fam-admin-imports-')
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.library = root / 'library'
        self.library.mkdir()
        server = Path(__file__).resolve().parents[1]
        sys.path.insert(0, str(server))
        name = '_import_test_' + uuid.uuid4().hex
        spec = importlib.util.spec_from_file_location(name, server / 'app.py')
        self.app = importlib.util.module_from_spec(spec)
        sys.modules[name] = self.app
        self.addCleanup(sys.modules.pop, name, None)
        with patch.dict(os.environ, {'ALBUM_DATA': str(root / 'data'), 'ALBUM_LIBRARY': str(self.library),
                                    'ALBUM_PASSWORD': '', 'ALBUM_BOOTSTRAP_TOKEN': '', 'ALBUM_STORAGE_KEY': '',
                                    'ALBUM_IMPORT_ROOTS': '[]', 'ALBUM_STORAGE_ROOTS': '[]',
                                    'ALBUM_STORAGE_ALLOWED_ENDPOINTS': ''}):
            spec.loader.exec_module(self.app)
            from admin_imports import AdminImportService
            self.service = getattr(self.app, 'admin_imports', None)
            if self.service is None:
                self.service = AdminImportService(self.app)
                self.app.app.include_router(self.service.router)
        self.addCleanup(self.service.stop)
        self.client = TestClient(self.app.app)
        self.addCleanup(self.client.close)
        initial = self.client.post('/api/login', json={'username': 'admin', 'password': 'admin'}).json()
        self.pending_headers = {'Authorization': 'Bearer ' + initial['token']}
        self.headers = None

    def unlock(self):
        response = self.client.post('/api/me/password', headers=self.pending_headers,
                                    json={'current_password': 'admin', 'new_password': 'synthetic-admin-password'})
        self.assertEqual(response.status_code, 200, response.text)
        self.headers = {'Authorization': 'Bearer ' + response.json()['token']}

    def photo(self, color=(180, 90, 62), exif=False):
        buffer = io.BytesIO()
        image = Image.new('RGB', (40, 30), color)
        data = Image.Exif()
        if exif:
            data[36867] = '2021:04:05 10:20:30'
        image.save(buffer, 'JPEG', exif=data)
        return buffer.getvalue()

    def create(self, name='测试导入'):
        response = self.client.post('/api/admin/imports', headers=self.headers,
                                    json={'name': name, 'source': 'upload'})
        self.assertEqual(response.status_code, 200, response.text)
        return response.json()['id']

    def upload(self, job, data=None, filename='测试照片.jpg', fallback='2025-05-06T12:13:14+08:00'):
        response = self.client.post('/api/admin/imports/' + job + '/files', headers=self.headers,
                                    files={'file': (filename, self.photo() if data is None else data, 'image/jpeg')},
                                    data={'fallback_captured_at': fallback})
        self.assertEqual(response.status_code, 200, response.text)
        return response.json()

    def action(self, job, name):
        response = self.client.post('/api/admin/imports/' + job + '/' + name, headers=self.headers)
        self.assertEqual(response.status_code, 200, response.text)
        return response.json()

    def wait(self, job, statuses=('completed', 'failed'), predicate=None):
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            result = self.service.get_job(job)
            if result['status'] in statuses and (predicate is None or predicate(result)):
                return result
            time.sleep(0.04)
        self.fail('Import did not reach expected state: ' + repr(self.service.get_job(job)))

    def test_admin_only_and_first_password_gate(self):
        self.assertEqual(self.client.get('/api/admin/imports').status_code, 401)
        self.assertEqual(self.client.get('/api/admin/imports', headers=self.pending_headers).status_code, 403)
        self.unlock()
        invitation = self.client.post('/api/invitations', headers=self.headers, json={'role': 'viewer'}).json()
        viewer = self.client.post('/api/invitations/accept', json={'code': invitation['code'], 'name': '测试家人',
                                  'username': 'testviewer', 'password': 'synthetic-viewer-password'}).json()
        headers = {'Authorization': 'Bearer ' + viewer['token']}
        for method, path in [('GET', ''), ('GET', '/roots'), ('POST', '/scan'), ('POST', '')]:
            self.assertEqual(self.client.request(method, '/api/admin/imports' + path, headers=headers,
                                                json={'root_id': 'library'}).status_code, 403)

    def test_upload_process_preserves_original_and_embedded_time(self):
        self.unlock()
        data = self.photo(exif=True)
        job = self.create()
        receiving = self.upload(job, data)
        self.assertEqual(receiving['status'], 'receiving')
        self.assertEqual(receiving['total'], 1)
        self.action(job, 'start')
        result = self.wait(job)
        self.assertEqual((result['status'], result['imported'], result['failed']), ('completed', 1, 0))
        listing = self.client.get('/api/media', headers=self.headers).json()['items']
        self.assertEqual(len(listing), 1)
        self.assertTrue(listing[0]['captured_at'].startswith('2021-04-05T10:20:30'))
        self.assertEqual(self.client.get(listing[0]['file_url'], headers=self.headers).content, data)
        self.assertEqual(len(list(self.service.staging.glob('*.jpg'))), 0)
        response = self.client.get('/api/admin/imports/' + job, headers=self.headers)
        self.assertNotIn('sha256', response.text)
        self.assertNotIn('staged_path', response.text)
        self.assertNotIn('source_path', response.text)

    def test_exact_duplicates_and_trashed_original_are_not_reimported(self):
        self.unlock()
        data, job = self.photo(), self.create()
        self.upload(job, data)
        self.upload(job, data, '重复文件.jpg')
        self.action(job, 'start')
        finished = self.wait(job)
        self.assertEqual((finished['imported'], finished['duplicates']), (1, 1))
        media = self.client.get('/api/media', headers=self.headers).json()['items'][0]
        self.client.delete('/api/media/' + media['id'], headers=self.headers)
        second = self.create()
        self.upload(second, data)
        self.action(second, 'start')
        result = self.wait(second)
        self.assertEqual(result['duplicates'], 1)
        self.assertIn('回收站', result['items'][0]['message'])
        with self.app.database() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM media').fetchone()[0], 1)

    def test_generated_video_import_keeps_capture_time_and_range_reads(self):
        self.unlock()
        video = Path(self.temp.name) / 'generated.mp4'
        subprocess.run([self.app._ffmpeg_path(), '-hide_banner', '-loglevel', 'error', '-nostdin',
                        '-f', 'lavfi', '-i', 'color=c=orange:s=64x48:d=0.5', '-c:v', 'libx264',
                        '-pix_fmt', 'yuv420p', '-movflags', 'use_metadata_tags',
                        '-metadata', 'com.apple.quicktime.creationdate=2022-01-02T03:04:05Z',
                        '-y', str(video)], check=True, capture_output=True, timeout=30,
                       creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        job = self.create('测试视频导入')
        self.upload(job, video.read_bytes(), '回忆.mp4')
        self.action(job, 'start')
        result = self.wait(job)
        self.assertEqual((result['status'], result['imported']), ('completed', 1))
        item = self.client.get('/api/media', headers=self.headers).json()['items'][0]
        self.assertEqual(item['kind'], 'video')
        self.assertTrue(item['captured_at'].startswith('2022-01-02T11:04:05'))
        part = self.client.get(item['file_url'], headers=self.headers | {'Range': 'bytes=0-63'})
        self.assertEqual(part.status_code, 206)
        self.assertEqual(part.content, video.read_bytes()[:64])

    def test_folder_scan_import_and_path_boundaries(self):
        self.unlock()
        folder = self.library / '春游'
        folder.mkdir()
        data = self.photo((100, 120, 140))
        original = folder / '公园.jpg'
        original.write_bytes(data)
        (folder / '重复.jpg').write_bytes(data)
        (folder / '.隐藏.jpg').write_bytes(data)
        (folder / '说明.txt').write_text('不是照片', encoding='utf-8')
        timestamp = 1617589230
        os.utime(original, (timestamp, timestamp))
        roots = self.client.get('/api/admin/imports/roots', headers=self.headers).json()['items']
        self.assertEqual(roots[0]['name'], '服务器图库')
        body = {'root_id': 'library', 'path': '春游', 'name': '公园照片'}
        scan = self.client.post('/api/admin/imports/scan', headers=self.headers, json=body)
        self.assertEqual(scan.status_code, 200, scan.text)
        self.assertEqual((scan.json()['count'], scan.json()['duplicates'], scan.json()['unsupported']), (2, 1, 2))
        for path in ('../', '/tmp', 'C:/Windows', '.hidden', '春游/../../'):
            self.assertEqual(self.client.post('/api/admin/imports/scan', headers=self.headers,
                                              json={'root_id': 'library', 'path': path}).status_code, 422)
        outside = Path(self.temp.name) / 'outside'
        outside.mkdir()
        (outside / 'private.jpg').write_bytes(data)
        try:
            (self.library / 'linked').symlink_to(outside, target_is_directory=True)
        except OSError:
            pass
        else:
            self.assertEqual(self.client.post('/api/admin/imports/scan', headers=self.headers,
                                              json={'root_id': 'library', 'path': 'linked'}).status_code, 422)
        created = self.client.post('/api/admin/imports/directory', headers=self.headers, json=body)
        self.assertEqual(created.status_code, 200, created.text)
        result = self.wait(created.json()['id'])
        self.assertEqual((result['imported'], result['duplicates']), (1, 1))
        self.assertEqual(original.read_bytes(), data)
        self.assertEqual(original.stat().st_mtime, timestamp)

    def test_file_limits_and_failed_import_retry(self):
        self.unlock()
        job = self.create()
        forbidden = self.client.post('/api/admin/imports/' + job + '/files', headers=self.headers,
                                      files={'file': ('secret.txt', b'not media', 'text/plain')})
        self.assertEqual(forbidden.status_code, 422)
        with patch.object(self.service, '_limit', return_value=20):
            too_big = self.client.post('/api/admin/imports/' + job + '/files', headers=self.headers,
                                       files={'file': ('photo.jpg', self.photo(), 'image/jpeg')})
        self.assertEqual(too_big.status_code, 413)
        self.assertEqual(len(list(self.service.staging.glob('*.jpg'))), 0)
        self.upload(job)
        original_upload = self.service.ctx.upload_media

        async def fail(**kwargs):
            await kwargs['file'].close()
            raise HTTPException(503, '存储暂时无法连接，请稍后重试。')

        self.service.ctx.upload_media = fail
        self.action(job, 'start')
        failed = self.wait(job)
        self.assertEqual((failed['status'], failed['failed']), ('failed', 1))
        self.assertIn('暂时无法连接', failed['items'][0]['message'])
        self.service.ctx.upload_media = original_upload
        self.action(job, 'retry')
        result = self.wait(job)
        self.assertEqual((result['status'], result['imported'], result['failed']), ('completed', 1, 0))

    def test_broken_media_reports_failure_without_creating_photo(self):
        self.unlock()
        job = self.create()
        self.upload(job, b'not a JPEG file')
        self.action(job, 'start')
        result = self.wait(job)
        self.assertEqual(result['failed'], 1)
        self.assertNotIn('Traceback', result['items'][0]['message'])
        with self.app.database() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM media').fetchone()[0], 0)

    def _blocked_upload(self):
        entered, release = threading.Event(), threading.Event()
        original_upload = self.service.ctx.upload_media

        async def block(**kwargs):
            entered.set()
            await asyncio.to_thread(release.wait, 6)
            return await original_upload(**kwargs)

        self.service.ctx.upload_media = block
        self.addCleanup(release.set)
        return entered, release

    def test_pause_then_resume_finishes_current_file_only(self):
        self.unlock()
        job = self.create()
        self.upload(job, self.photo((10, 20, 30)))
        self.upload(job, self.photo((40, 50, 60)))
        entered, release = self._blocked_upload()
        self.action(job, 'start')
        self.assertTrue(entered.wait(4))
        self.action(job, 'pause')
        release.set()
        paused = self.wait(job, ('paused',), lambda j: j['processed'] == 1)
        self.assertEqual(paused['imported'], 1)
        self.action(job, 'resume')
        self.assertEqual(self.wait(job)['imported'], 2)

    def test_cancel_keeps_completed_file_and_discards_pending_staging(self):
        self.unlock()
        job = self.create()
        self.upload(job, self.photo((10, 20, 30)))
        self.upload(job, self.photo((40, 50, 60)))
        entered, release = self._blocked_upload()
        self.action(job, 'start')
        self.assertTrue(entered.wait(4))
        self.action(job, 'cancel')
        release.set()
        result = self.wait(job, ('cancelled',), lambda j: j['processed'] == 2)
        self.assertEqual((result['imported'], result['cancelled']), (1, 1))
        self.assertEqual(len(list(self.service.staging.glob('*.jpg'))), 0)

    def test_restart_recovers_interrupted_job_without_losing_original(self):
        self.unlock()
        job = self.create()
        self.upload(job)
        with self.app.database() as db:
            db.execute("UPDATE admin_import_jobs SET status='running' WHERE id=?", (job,))
            db.execute("UPDATE admin_import_items SET status='processing' WHERE job_id=?", (job,))
        self.service.start()
        recovered = self.wait(job, ('paused',))
        self.assertEqual(recovered['total'], 1)
        self.assertIn('重新启动', recovered['message'])
        self.action(job, 'resume')
        self.assertEqual(self.wait(job)['imported'], 1)

    def test_second_worker_cannot_double_import_same_queue(self):
        self.unlock()
        from admin_imports import AdminImportService
        with patch.dict(os.environ, {'ALBUM_IMPORT_ROOTS': '[]'}):
            second = AdminImportService(self.service.ctx)
        self.addCleanup(second.stop)
        job = self.create()
        for number in range(5):
            self.upload(job, self.photo((number * 30, 80, 100)), str(number) + '.jpg')
        self.action(job, 'start')
        second.start()
        result = self.wait(job)
        self.assertEqual((result['imported'], result['duplicates'], result['failed']), (5, 0, 0))
        with self.app.database() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM media').fetchone()[0], 5)


if __name__ == '__main__':
    unittest.main()
