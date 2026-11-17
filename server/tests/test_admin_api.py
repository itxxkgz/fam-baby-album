"""Web administration uses a separate synthetic album for every scenario."""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid
import zipfile

from fastapi.testclient import TestClient
from PIL import Image


class AdminWebTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='fam-web-test-')
        self.addCleanup(self.temporary.cleanup)
        folder = Path(self.temporary.name)
        self.folder = folder
        server = Path(__file__).resolve().parents[1]
        sys.path.insert(0, str(server))
        name = 'fam_web_test_' + uuid.uuid4().hex
        spec = importlib.util.spec_from_file_location(name, server / 'app.py')
        self.app = importlib.util.module_from_spec(spec)
        sys.modules[name] = self.app
        self.addCleanup(sys.modules.pop, name, None)
        with patch.dict(os.environ, {
                'ALBUM_DATA': str(folder / 'data'), 'ALBUM_LIBRARY': str(folder / 'library'),
                'ALBUM_PASSWORD': '', 'ALBUM_BOOTSTRAP_TOKEN': '', 'ALBUM_STORAGE_KEY': '',
                'ALBUM_STORAGE_ROOTS': '[]', 'ALBUM_STORAGE_ALLOWED_ENDPOINTS': '',
                'ALBUM_IMPORT_ROOTS': '[]'}):
            spec.loader.exec_module(self.app)
        self.client = TestClient(self.app.app)
        self.addCleanup(self.client.close)
        self.addCleanup(self.app.admin_imports.stop)
        self.addCleanup(self.app.admin_backups.stop)
        buffer = io.BytesIO()
        Image.new('RGB', (30, 20), (185, 90, 63)).save(buffer, 'PNG')
        self.picture = buffer.getvalue()

    def login(self, unlock=True):
        response = self.client.post('/api/admin/session', json={'username': 'admin', 'password': 'admin'})
        self.assertEqual(response.status_code, 200, response.text)
        session = response.json()
        self.headers = {'X-FAM-CSRF': session['csrf']}
        if unlock:
            response = self.client.post('/api/admin/account', headers=self.headers,
                                        json={'current_password': 'admin', 'new_password': 'chosen-family-password'})
            self.assertEqual(response.status_code, 200, response.text)
            session = response.json()
            self.headers = {'X-FAM-CSRF': session['csrf']}
        return session

    def upload(self, filename='公园照片.png'):
        response = self.client.post('/api/upload', headers=self.headers,
                                    data={'captured_at': '2022-05-20T11:22:33+08:00', 'description': '第一次去公园'},
                                    files={'file': (filename, self.picture, 'image/png')})
        self.assertEqual(response.status_code, 200, response.text)
        return response.json()

    def test_first_login_cookie_restricted_change_and_logout(self):
        self.assertEqual(self.client.get('/api/admin/session').status_code, 401)
        session = self.login(False)
        self.assertNotIn('token', session)
        self.assertTrue(session['password_change_required'])
        self.assertTrue(self.client.get('/api/admin/session').json()['password_change_required'])
        for path in ('/api/admin/overview', '/api/admin/media', '/api/storage', '/api/admin/backups', '/api/admin/imports'):
            self.assertEqual(self.client.get(path).status_code, 403, path)
        blocked = self.client.post('/api/admin/account', headers=self.headers,
                                   json={'current_password': 'admin', 'new_username': 'new.name'})
        self.assertEqual(blocked.status_code, 403)
        old_cookie = self.client.cookies.get('fam_admin_session')
        unlocked = self.client.post('/api/admin/account', headers=self.headers,
                                    json={'current_password': 'admin', 'new_password': 'chosen-family-password'})
        self.assertEqual(unlocked.status_code, 200)
        self.assertFalse(unlocked.json()['password_change_required'])
        self.assertNotEqual(old_cookie, self.client.cookies.get('fam_admin_session'))
        self.headers = {'X-FAM-CSRF': unlocked.json()['csrf']}
        self.assertEqual(self.client.get('/api/admin/overview').status_code, 200)
        self.assertEqual(self.client.delete('/api/admin/session', headers=self.headers).status_code, 200)
        self.assertEqual(self.client.get('/api/admin/overview').status_code, 401)
        self.assertFalse(self.client.cookies.get('fam_admin_session'))

    def test_csrf_cross_origin_and_cookie_flags(self):
        response = self.client.post('/api/admin/session', json={'username': 'admin', 'password': 'admin'},
                                    headers={'Origin': 'https://unrelated.example'})
        self.assertEqual(response.status_code, 403)
        self.login()
        response = self.client.post('/api/admin/session', json={'username': 'admin', 'password': 'chosen-family-password'})
        self.assertIn('HttpOnly', response.headers['set-cookie'])
        self.assertIn('SameSite=strict', response.headers['set-cookie'])
        csrf = response.json()['csrf']
        payload = {'name': '不该修改的名字'}
        self.assertEqual(self.client.put('/api/profile', json=payload).status_code, 403)
        self.assertEqual(self.client.put('/api/profile', headers={'X-FAM-CSRF': 'wrong'}, json=payload).status_code, 403)
        self.assertEqual(self.client.put('/api/profile', headers={'X-FAM-CSRF': csrf, 'Origin': 'https://unrelated.example'}, json=payload).status_code, 403)
        self.assertEqual(self.client.put('/api/profile', headers={'X-FAM-CSRF': csrf, 'Origin': 'http://testserver'}, json={'name': '测试家庭'}).status_code, 200)
        self.assertEqual(self.client.get('/api/admin/session').headers['cache-control'], 'no-store')
        with TestClient(self.app.app, base_url='https://secure.example') as secure:
            response = secure.post('/api/admin/session', json={'username': 'admin', 'password': 'chosen-family-password'})
            self.assertIn('Secure', response.headers['set-cookie'])

    def test_non_admin_cannot_enter_web_and_android_still_works(self):
        self.login()
        invite = self.client.post('/api/invitations', headers=self.headers, json={'role': 'viewer'}).json()
        accepted = self.client.post('/api/invitations/accept', json={'code': invite['code'], 'username': 'grandma',
                                                                  'password': 'family-viewer-password', 'name': '测试外婆'}).json()
        viewer = {'Authorization': 'Bearer ' + accepted['token']}
        self.assertEqual(self.client.get('/api/media', headers=viewer).status_code, 200)
        self.assertEqual(self.client.get('/api/admin/media', headers=viewer).status_code, 403)
        guest = TestClient(self.app.app)
        self.addCleanup(guest.close)
        self.assertEqual(guest.post('/api/admin/session', json={'username': 'grandma', 'password': 'family-viewer-password'}).status_code, 403)
        self.assertFalse(guest.cookies.get('fam_admin_session'))

    def test_cookie_media_range_filters_and_batch_are_real(self):
        self.login()
        first, second = self.upload(), self.upload('旅行照片.png')
        response = self.client.get(first['file_url'], headers={'Range': 'bytes=0-15'})
        self.assertEqual(response.status_code, 206)
        self.assertEqual(response.content, self.picture[:16])
        self.assertEqual(self.client.get(first['thumbnail_url']).status_code, 200)
        search = self.client.get('/api/admin/media', params={'search': '旅行', 'date_from': '2022-05-20', 'date_to': '2022-05-20'}).json()
        self.assertEqual(search['total'], 1)
        self.assertEqual(search['items'][0]['id'], second['id'])
        page = self.client.get('/api/admin/media', params={'limit': 1}).json()
        self.assertTrue(page['has_more'])
        self.assertEqual(self.client.get('/api/admin/media', params={'limit': 0}).status_code, 422)
        self.assertEqual(self.client.get('/api/admin/media', params={'date_from': 'bad-date'}).status_code, 422)
        self.assertEqual(self.client.get('/api/admin/media', params={'search': "' OR 1=1 --"}).json()['total'], 0)
        invalid = self.client.post('/api/admin/media/batch', headers=self.headers,
                                   json={'ids': [first['id'], 'missing'], 'action': 'trash'})
        self.assertEqual(invalid.status_code, 409)
        self.assertEqual(self.client.get('/api/admin/media').json()['total'], 2)
        changed = self.client.post('/api/admin/media/batch', headers=self.headers, json={
            'ids': [first['id'], second['id']], 'action': 'edit', 'captured_at': '2021-03-04T05:06:07+08:00',
            'description': '<script>测试文字</script>'})
        self.assertEqual(changed.status_code, 200)
        edited = self.client.get('/api/admin/media').json()['items']
        self.assertTrue(all(item['album_date'] == '2021-03-04' for item in edited))
        self.assertEqual(self.client.get(first['file_url']).content, self.picture)
        for action, count in [('trash', 0), ('restore', 2)]:
            response = self.client.post('/api/admin/media/batch', headers=self.headers,
                                        json={'ids': [first['id'], second['id']], 'action': action})
            self.assertEqual(response.status_code, 200)
            self.assertEqual(self.client.get('/api/admin/media').json()['total'], count)

    def test_batch_download_preserves_bytes_time_and_never_exposes_internal_ids(self):
        self.login()
        photo = self.upload('测试照片.png')
        response = self.client.post('/api/admin/media/download', headers=self.headers, json={'ids': [photo['id']]})
        self.assertEqual(response.status_code, 200, response.text[:100] if response.status_code != 200 else '')
        with zipfile.ZipFile(io.BytesIO(response.content)) as archive:
            self.assertEqual(archive.read('001-测试照片.png'), self.picture)
            self.assertEqual(archive.getinfo('001-测试照片.png').date_time[:5], (2022, 5, 20, 11, 22))
            metadata = json.loads(archive.read('照片资料.json'))
            self.assertEqual(metadata[0]['拍摄时间'], '2022年05月20日 11:22:33')
            self.assertNotIn(photo['id'], str(metadata))
        self.assertFalse(list((self.app.DATA / 'temporary').glob('family-download-*')))

    def test_upload_limit_and_account_change_survive_reload(self):
        self.login()
        self.assertEqual(self.client.put('/api/admin/settings', headers=self.headers,
                                        json={'max_upload_bytes': 1024 * 1024}).status_code, 200)
        self.assertEqual(self.app.effective_max_upload(), 1024 * 1024)
        too_large = self.client.post('/api/upload', headers=self.headers,
                                     files={'file': ('too-large.jpg', b'x' * (1024 * 1024 + 1), 'image/jpeg')})
        self.assertEqual(too_large.status_code, 413)
        self.assertEqual(self.client.put('/api/admin/settings', headers=self.headers,
                                        json={'max_upload_bytes': self.app.MAX_UPLOAD + 1}).status_code, 422)
        before = self.client.get('/api/admin/session').json()['member']
        changed = self.client.post('/api/admin/account', headers=self.headers,
                                   json={'current_password': 'chosen-family-password', 'new_username': 'family.owner'})
        self.assertEqual(changed.status_code, 200)
        self.assertEqual(changed.json()['member']['id'], before['id'])
        self.assertEqual(self.client.get('/api/admin/session').json()['member']['username'], 'family.owner')
        self.assertEqual(self.client.get('/api/admin/records').json()['items'][0]['title'], '账号信息已更新')
        with self.app.database() as db:
            self.assertEqual(db.execute("SELECT value FROM settings WHERE key='admin_max_upload_bytes'").fetchone()[0], str(1024 * 1024))

    def test_shell_is_public_but_data_is_protected(self):
        response = self.client.get('/admin/')
        self.assertEqual(response.status_code, 200)
        self.assertIn('FAM', response.text)
        self.assertIn("script-src 'self'", response.headers['content-security-policy'])
        for filename in ('admin.js', 'utils.mjs'):
            script = self.client.get('/admin/' + filename)
            self.assertEqual(script.status_code, 200)
            self.assertIn('javascript', script.headers['content-type'])
        self.assertEqual(self.client.get('/api/admin/overview').status_code, 401)
        self.assertEqual(self.client.get('/admin/../../data/album.sqlite').status_code, 404)


if __name__ == '__main__':
    unittest.main()
