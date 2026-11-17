from __future__ import annotations

from datetime import date
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import uuid

from PIL import Image
from fastapi.testclient import TestClient


TEMP = tempfile.TemporaryDirectory(prefix='xiaojia-api-test-')
ROOT = Path(TEMP.name)
os.environ['ALBUM_DATA'] = str(ROOT / 'data')
os.environ['ALBUM_LIBRARY'] = str(ROOT / 'library')
os.environ['ALBUM_PASSWORD'] = 'test-family-password-2026'
os.environ['ALBUM_BOOTSTRAP_TOKEN'] = 'test-bootstrap-secret'
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import app


class AlbumApiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        app.LIBRARY.mkdir(parents=True)
        cls.client = TestClient(app.app)
        response = cls.client.post('/api/setup', json={'setup_code': os.environ['ALBUM_BOOTSTRAP_TOKEN'],
                                   'username': 'testowner', 'password': os.environ['ALBUM_PASSWORD'], 'name': '测试家人'})
        if response.status_code != 200:
            raise AssertionError(response.text)
        cls.headers = {'Authorization': 'Bearer ' + response.json()['token']}
        image = Image.new('RGB', (40, 30), (30, 130, 60))
        buffer = io.BytesIO()
        image.save(buffer, 'JPEG')
        cls.photo_bytes = buffer.getvalue()

    def setUp(self):
        app.LOGIN_ATTEMPTS.clear()

    @classmethod
    def tearDownClass(cls):
        cls.client.close()
        TEMP.cleanup()

    def upload(self, day='2020-06-08T12:30:00+08:00'):
        response = self.client.post('/api/upload', headers=self.headers,
                                    data={'captured_at': day, 'description': '测试成长记录'},
                                    files={'file': ('测试照片.jpg', self.photo_bytes, 'image/jpeg')})
        self.assertEqual(response.status_code, 200, response.text)
        return response.json()

    def invite_user(self, role='contributor', member_id=None):
        response = self.client.post('/api/invitations', headers=self.headers,
                                    json={'role': role, 'member_id': member_id})
        self.assertEqual(response.status_code, 200, response.text)
        invitation = response.json()
        username = 'user' + uuid.uuid4().hex[:12]
        response = self.client.post('/api/invitations/accept', json={
            'code': invitation['code'], 'username': username, 'password': 'member-password-2026',
            'name': '测试新家人', 'role': 'admin'})
        self.assertEqual(response.status_code, 200, response.text)
        value = response.json()
        return {'Authorization': 'Bearer ' + value['token']}, value['member'], invitation, username

    def test_independent_permissions_and_personal_favorites(self):
        contributor_headers, contributor, _, _ = self.invite_user()
        viewer_headers, viewer, _, _ = self.invite_user('viewer')
        self.assertEqual(contributor['role'], 'contributor')
        self.assertEqual(viewer['role'], 'viewer')
        self.assertFalse(viewer['permissions']['can_upload'])
        self.assertTrue(viewer['permissions']['can_favorite'])
        parent_media = self.upload()
        media_path = '/api/media/' + parent_media['id']
        response = self.client.get(media_path, headers=contributor_headers)
        self.assertFalse(response.json()['can_edit'])
        self.assertEqual(self.client.put(media_path, headers=contributor_headers, json={'description': '越权修改'}).status_code, 403)
        self.assertEqual(self.client.delete(media_path, headers=viewer_headers).status_code, 403)
        self.assertEqual(self.client.put('/api/profile', headers=contributor_headers, json={'child_name': '越权修改'}).status_code, 403)
        self.assertEqual(self.client.post('/api/invitations', headers=contributor_headers, json={}).status_code, 403)
        # A forbidden viewer upload is rejected before even parsing a malformed multipart body.
        forbidden = self.client.post('/api/upload', headers=viewer_headers | {'Content-Type': 'multipart/form-data; boundary=missing'}, content=b'not a valid upload')
        self.assertEqual(forbidden.status_code, 403)
        self.assertEqual(self.client.post(media_path + '/comments', headers=viewer_headers, json={'text': '不可留言'}).status_code, 403)
        self.assertEqual(self.client.put(media_path + '/favorite', headers=viewer_headers, json={'favorite': True}).status_code, 200)
        self.assertTrue(self.client.get(media_path, headers=viewer_headers).json()['favorite'])
        self.assertFalse(self.client.get(media_path, headers=self.headers).json()['favorite'])
        favorites = self.client.get('/api/media?favorite=true', headers=viewer_headers).json()['items']
        self.assertEqual([item['id'] for item in favorites], [parent_media['id']])
        response = self.client.post('/api/upload', headers=contributor_headers,
                                    files={'file': ('自己上传的照片.jpg', self.photo_bytes, 'image/jpeg')})
        self.assertEqual(response.status_code, 200, response.text)
        owned_path = '/api/media/' + response.json()['id']
        self.assertTrue(response.json()['can_edit'])
        self.assertEqual(self.client.put(owned_path, headers=contributor_headers, json={'description': '自己的说明'}).status_code, 200)
        comment = self.client.post(media_path + '/comments', headers=contributor_headers, json={'text': '自己的留言'}).json()
        self.assertEqual(self.client.delete(media_path + '/comments/' + comment['id'], headers=viewer_headers).status_code, 403)
        self.assertEqual(self.client.delete(media_path + '/comments/' + comment['id'], headers=contributor_headers).status_code, 200)
        self.assertEqual(self.client.delete(owned_path, headers=contributor_headers).status_code, 200)
        self.assertEqual(self.client.get(owned_path, headers=viewer_headers).status_code, 404)
        self.assertEqual(self.client.get(owned_path + '/file', headers=viewer_headers).status_code, 404)
        self.assertEqual(self.client.get('/api/trash', headers=viewer_headers).status_code, 403)
        self.assertEqual(self.client.post(owned_path + '/restore', headers=contributor_headers).status_code, 200)
        response = self.client.get('/api/members', headers=self.headers)
        self.assertNotIn('password_hash', response.text)
        self.assertNotIn('password_salt', response.text)

    def test_invitation_claim_revoke_expiry_and_no_impersonation(self):
        pending_id = uuid.uuid4().hex
        with app.database() as connection:
            connection.execute('INSERT INTO members(id,name,role,joined_at) VALUES (?,?,?,?)',
                               (pending_id, '待认领家人', '家人', app.now_text()))
            count_before = connection.execute('SELECT COUNT(*) FROM members').fetchone()[0]
        headers, member, invitation, username = self.invite_user('viewer', pending_id)
        self.assertEqual(member['id'], pending_id)
        self.assertEqual(member['name'], '待认领家人')
        self.assertEqual(member['role'], 'viewer')
        with app.database() as connection:
            self.assertEqual(connection.execute('SELECT COUNT(*) FROM members').fetchone()[0], count_before)
            row = connection.execute('SELECT * FROM invitations WHERE id=?', (invitation['id'],)).fetchone()
            self.assertNotEqual(row['code_hash'], invitation['code'])
            self.assertIsNotNone(row['used_at'])
        self.assertEqual(self.client.post('/api/invitations/accept', json={'code': invitation['code'], 'username': 'reusecode', 'password': 'longpassword', 'name': '冒名者'}).status_code, 401)
        self.assertEqual(self.client.post('/api/login', json={'name': '待认领家人', 'password': os.environ['ALBUM_PASSWORD']}).status_code, 422)
        self.assertEqual(self.client.post('/api/login', json={'username': username, 'password': os.environ['ALBUM_PASSWORD']}).status_code, 401)
        for expire in (False, True):
            response = self.client.post('/api/invitations', headers=self.headers, json={})
            invite = response.json()
            if expire:
                with app.database() as connection:
                    connection.execute('UPDATE invitations SET expires_at=0 WHERE id=?', (invite['id'],))
            else:
                self.assertEqual(self.client.delete('/api/invitations/' + invite['id'], headers=self.headers).status_code, 200)
            self.assertEqual(self.client.post('/api/invitations/accept', json={'code': invite['code'], 'username': 'cannotjoin', 'password': 'longpassword', 'name': '测试家人'}).status_code, 401)
        invitations = self.client.get('/api/invitations', headers=self.headers).json()['items']
        self.assertTrue(all('code' not in item and 'code_hash' not in item for item in invitations))

    def test_password_rotation_disable_and_admin_lockout_guards(self):
        headers, member, _, username = self.invite_user()
        second = self.client.post('/api/login', json={'username': username.upper(), 'password': 'member-password-2026'}).json()
        second_headers = {'Authorization': 'Bearer ' + second['token']}
        rotated = self.client.post('/api/me/password', headers=headers, json={
            'current_password': 'member-password-2026', 'new_password': 'changed-password-2026'})
        self.assertEqual(rotated.status_code, 200, rotated.text)
        new_headers = {'Authorization': 'Bearer ' + rotated.json()['token']}
        self.assertEqual(self.client.get('/api/me', headers=headers).status_code, 401)
        self.assertEqual(self.client.get('/api/me', headers=second_headers).status_code, 401)
        self.assertEqual(self.client.get('/api/me', headers=new_headers).status_code, 200)
        self.assertEqual(self.client.patch('/api/members/' + member['id'], headers=self.headers, json={'role': 'viewer'}).status_code, 200)
        self.assertFalse(self.client.get('/api/me', headers=new_headers).json()['permissions']['can_upload'])
        self.assertEqual(self.client.patch('/api/members/' + member['id'], headers=self.headers, json={'disabled': True}).status_code, 200)
        self.assertEqual(self.client.get('/api/me', headers=new_headers).status_code, 401)
        self.assertEqual(self.client.post('/api/login', json={'username': username, 'password': 'changed-password-2026'}).status_code, 401)
        self.assertEqual(self.client.patch('/api/members/' + member['id'], headers=self.headers, json={'disabled': False}).status_code, 200)
        self.assertEqual(self.client.get('/api/me', headers=new_headers).status_code, 401)
        self.assertEqual(self.client.post('/api/login', json={'username': username, 'password': 'changed-password-2026'}).status_code, 200)
        owner = self.client.get('/api/me', headers=self.headers).json()
        self.assertEqual(self.client.patch('/api/members/' + owner['id'], headers=self.headers, json={'disabled': True}).status_code, 409)
        self.assertEqual(self.client.patch('/api/members/' + owner['id'], headers=self.headers, json={'role': 'viewer'}).status_code, 409)

    def test_storage_admin_scope_switch_and_existing_file_reads(self):
        viewer_headers, _, _, _ = self.invite_user('viewer')
        self.assertEqual(self.client.get('/api/storage', headers=viewer_headers).status_code, 403)
        self.assertEqual(self.client.post('/api/storage', headers=viewer_headers, json={}).status_code, 403)
        old_media = self.upload()
        configurations = self.client.get('/api/storage', headers=self.headers).json()['items']
        original_default = next(item['id'] for item in configurations if item['is_default'])
        second_path = app.DATA / 'uploads' / 'another-allowed-volume'
        created = self.client.post('/api/storage', headers=self.headers, json={
            'name': '测试第二个存储位置', 'provider': 'local', 'local_path': str(second_path)})
        self.assertEqual(created.status_code, 201, created.text)
        storage_id = created.json()['id']
        self.assertEqual(self.client.post(f'/api/storage/{storage_id}/test', headers=self.headers).status_code, 200)
        self.assertEqual(self.client.post(f'/api/storage/{storage_id}/default', headers=self.headers).status_code, 200)
        new_media = self.upload()
        with app.database() as connection:
            new_row = connection.execute('SELECT storage_id,object_key FROM media WHERE id=?', (new_media['id'],)).fetchone()
            self.assertEqual(new_row['storage_id'], storage_id)
            self.assertEqual((second_path / new_row['object_key']).read_bytes(), self.photo_bytes)
        self.assertEqual(self.client.get(old_media['file_url'], headers=self.headers).content, self.photo_bytes)
        self.assertEqual(self.client.get(new_media['file_url'], headers=self.headers | {'Range': 'bytes=3-17'}).content, self.photo_bytes[3:18])
        self.assertEqual(self.client.get(new_media['thumbnail_url'], headers=self.headers).status_code, 200)
        self.assertEqual(self.client.put(f'/api/storage/{storage_id}', headers=self.headers,
                                        json={'local_path': str(second_path / 'moved')}).status_code, 409)
        self.assertEqual(self.client.post(f'/api/storage/{original_default}/test', headers=self.headers).status_code, 200)
        self.assertEqual(self.client.post(f'/api/storage/{original_default}/default', headers=self.headers).status_code, 200)
        self.assertEqual(self.client.delete(f'/api/storage/{storage_id}', headers=self.headers).status_code, 409)
        invalid = self.client.post('/api/storage', headers=self.headers, json={
            'name': '测试', 'secret_access_key': 'never-show-this-secret', 'unknown': 'private-value'})
        self.assertEqual(invalid.status_code, 422)
        self.assertNotIn('never-show-this-secret', invalid.text)
        self.assertNotIn('private-value', invalid.text)

    def test_authorization_is_required_for_library_and_files(self):
        self.assertEqual(self.client.get('/api/status').status_code, 200)
        self.assertEqual(self.client.get('/api/media').status_code, 401)
        self.assertEqual(self.client.post('/api/login', json={'username': 'testowner', 'password': 'wrong'}).status_code, 401)
        media = self.upload()
        self.assertEqual(self.client.get(media['file_url']).status_code, 401)
        self.assertEqual(self.client.get(media['thumbnail_url']).status_code, 401)

    def test_upload_range_thumbnail_comments_edit_and_trash(self):
        media = self.upload()
        media_id = media['id']
        self.assertEqual(media['album_date'], '2020-06-08')
        response = self.client.get(media['file_url'], headers=self.headers)
        self.assertEqual(response.content, self.photo_bytes)
        response = self.client.get(media['file_url'], headers=self.headers | {'Range': 'bytes=10-29'})
        self.assertEqual(response.status_code, 206)
        self.assertEqual(response.content, self.photo_bytes[10:30])
        self.assertEqual(response.headers['Content-Length'], '20')
        response = self.client.get(media['file_url'], headers=self.headers | {'Range': 'bytes=-12'})
        self.assertEqual(response.content, self.photo_bytes[-12:])
        self.assertEqual(self.client.get(media['file_url'], headers=self.headers | {'Range': 'bytes=999999-'}).status_code, 416)
        response = self.client.head(media['file_url'], headers=self.headers)
        self.assertEqual(len(response.content), 0)
        self.assertEqual(int(response.headers['Content-Length']), len(self.photo_bytes))
        response = self.client.get(media['thumbnail_url'], headers=self.headers)
        self.assertEqual(response.status_code, 200)
        with Image.open(io.BytesIO(response.content)) as thumbnail:
            self.assertEqual(thumbnail.size, (40, 30))
        response = self.client.post(f'/api/media/{media_id}/comments', headers=self.headers, json={'text': '生日快乐'})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(self.client.get(f'/api/media/{media_id}/comments', headers=self.headers).json()['items'][0]['text'], '生日快乐')
        self.assertEqual(self.client.put(f'/api/media/{media_id}/favorite', headers=self.headers, json={'favorite': True}).json()['favorite'], True)
        response = self.client.patch(f'/api/media/{media_id}', headers=self.headers,
                                     json={'captured_at': '2021-02-03T08:09:10+08:00', 'description': '新的说明'})
        self.assertEqual(response.json()['album_date'], '2021-02-03')
        self.assertEqual(response.json()['description'], '新的说明')
        self.assertEqual(self.client.delete(f'/api/media/{media_id}', headers=self.headers).status_code, 200)
        self.assertIn(media_id, [item['id'] for item in self.client.get('/api/trash', headers=self.headers).json()['items']])
        self.assertEqual(self.client.post(f'/api/media/{media_id}/restore', headers=self.headers).json()['deleted_at'], None)

    def test_pagination_keeps_same_day_ascending_without_duplicates(self):
        desired = [self.upload(f'2026-01-02T{hour:02d}:00:00+08:00')['id'] for hour in (10, 8, 9)]
        first = self.client.get('/api/media?date=2026-01-02&limit=2', headers=self.headers).json()
        self.assertEqual([item['id'] for item in first['items']], [desired[1], desired[2]])
        second = self.client.get('/api/media', params={'date': '2026-01-02', 'limit': 2, 'cursor': first['next_cursor']}, headers=self.headers).json()
        self.assertEqual([item['id'] for item in second['items']], [desired[0]])
        self.assertIsNone(second['next_cursor'])
        timeline = self.client.get('/api/timeline?month=2026-01', headers=self.headers).json()
        self.assertEqual([item['id'] for item in timeline['groups'][0]['items']], [desired[1], desired[2], desired[0]])
        self.assertEqual(self.client.get('/api/media?cursor=not-a-cursor', headers=self.headers).status_code, 422)

    def test_avatar_profile_age_and_path_boundaries(self):
        media = self.upload()
        response = self.client.put('/api/profile', headers=self.headers,
                                   json={'child_name': '测试宝宝', 'birthday': '2020-02-03', 'avatar_media_id': media['id']})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['avatar_url'], media['thumbnail_url'])
        self.assertEqual(app.age_label('2020-02-03', date(2026, 5, 17)), '6岁3个月14天')
        self.assertEqual(app.age_label('2020-01-31', date(2020, 3, 1)), '1个月1天')
        self.assertEqual(self.client.put('/api/profile', headers=self.headers, json={'birthday': '2020-99-01'}).status_code, 422)
        with app.database() as connection:
            connection.execute('UPDATE media SET root_kind=?,relative_path=?,storage_id=NULL,object_key=NULL WHERE id=?',
                               ('library', '../outside.jpg', media['id']))
        (ROOT / 'outside.jpg').write_bytes(self.photo_bytes)
        self.assertIn(self.client.get(media['file_url'], headers=self.headers).status_code, (400, 404))

    def test_generated_video_poster_and_streaming(self):
        sample = ROOT / 'synthetic-video.mp4'
        command = [app._ffmpeg_path(), '-hide_banner', '-loglevel', 'error', '-nostdin',
                   '-f', 'lavfi', '-i', 'color=c=green:s=96x64:r=12', '-t', '0.5',
                   '-an', '-c:v', 'libx264', '-pix_fmt', 'yuv420p',
                   '-metadata', 'creation_time=2022-01-02T03:04:05Z', '-y', str(sample)]
        generated = subprocess.run(command, capture_output=True, timeout=30,
                                   creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        self.assertEqual(generated.returncode, 0, '无法生成合成测试视频，请检查 FFmpeg。')
        with sample.open('rb') as stream:
            response = self.client.post('/api/upload', headers=self.headers,
                                        files={'file': ('测试视频.mp4', stream, 'video/mp4')})
        self.assertEqual(response.status_code, 200, response.text[:100])
        identity = response.json()['id']
        self.assertEqual(response.json()['kind'], 'video')
        self.assertEqual(response.json()['captured_at'], '2022-01-02T11:04:05+08:00')
        response = self.client.get(f'/api/media/{identity}/thumbnail', headers=self.headers)
        self.assertEqual(response.status_code, 200, response.text[:100])
        with Image.open(io.BytesIO(response.content)) as picture:
            self.assertLessEqual(max(picture.size), 640)
        response = self.client.get(f'/api/media/{identity}/file', headers=self.headers | {'Range': 'bytes=0-1023'})
        self.assertEqual(response.status_code, 206)
        self.assertEqual(len(response.content), 1024)

    def test_upload_original_timezone_precedes_phone_fallback(self):
        photo = Image.new('RGB', (40, 30), (45, 120, 75))
        exif = Image.Exif()
        exif[34665] = {36867: '2022:08:09 23:15:16', 36881: '-05:00'}
        buffer = io.BytesIO()
        photo.save(buffer, 'JPEG', exif=exif)
        original_bytes = buffer.getvalue()
        wrong_fallback = '2025-01-01T09:00:00+08:00'

        def send(content, **fields):
            return self.client.post('/api/upload', headers=self.headers, data=fields,
                                    files={'file': ('时区测试照片.jpg', content, 'image/jpeg')})

        response = send(original_bytes, fallback_captured_at=wrong_fallback)
        self.assertEqual(response.status_code, 200, response.text)
        original = response.json()
        self.assertEqual(original['captured_at'], '2022-08-10T12:15:16+08:00')
        self.assertEqual(original['album_date'], '2022-08-10')
        self.assertEqual(self.client.get(original['file_url'], headers=self.headers).content, original_bytes)

        response = send(self.photo_bytes, fallback_captured_at=wrong_fallback)
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json()['captured_at'], wrong_fallback)

        explicit = '2024-03-04T05:06:07+08:00'
        response = send(original_bytes, captured_at=explicit, fallback_captured_at=wrong_fallback)
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json()['captured_at'], explicit)
        self.assertEqual(send(original_bytes, fallback_captured_at='invalid-date').status_code, 422)


if __name__ == '__main__':
    unittest.main()
