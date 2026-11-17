"""Initial account and username changes using isolated, synthetic albums."""
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import io
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid

from fastapi.testclient import TestClient
from PIL import Image


class AccountOnboardingTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='fam-account-onboarding-')
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)
        self.environment = {
            'ALBUM_DATA': str(self.folder / 'data'),
            'ALBUM_LIBRARY': str(self.folder / 'library'),
            'ALBUM_PASSWORD': '', 'ALBUM_BOOTSTRAP_TOKEN': '',
            'ALBUM_STORAGE_ROOTS': '', 'ALBUM_STORAGE_ALLOWED_ENDPOINTS': '',
            'ALBUM_STORAGE_KEY': '',
        }

    def load_album(self):
        server = Path(__file__).resolve().parents[1]
        sys.path.insert(0, str(server))
        name = '_synthetic_onboarding_' + uuid.uuid4().hex
        spec = importlib.util.spec_from_file_location(name, server / 'app.py')
        module = importlib.util.module_from_spec(spec)
        sys.modules[name] = module
        self.addCleanup(sys.modules.pop, name, None)
        with patch.dict(os.environ, self.environment):
            spec.loader.exec_module(module)
        client = TestClient(module.app)
        self.addCleanup(client.close)
        return module, client

    def login(self, client, username='admin', password='admin'):
        response = client.post('/api/login', json={'username': username, 'password': password})
        self.assertEqual(response.status_code, 200, response.text)
        return response.json(), {'Authorization': 'Bearer ' + response.json()['token']}

    def unlock(self, client, password='my-personal-password'):
        _, initial = self.login(client)
        response = client.post('/api/me/password', headers=initial,
                               json={'current_password': 'admin', 'new_password': password})
        self.assertEqual(response.status_code, 200, response.text)
        return response.json(), {'Authorization': 'Bearer ' + response.json()['token']}

    def test_default_login_has_no_album_access_until_password_changes(self):
        app, client = self.load_album()
        status = client.get('/api/status').json()
        self.assertTrue(status['ready'])
        self.assertFalse(status['setup_required'])
        self.assertTrue(status['default_credentials'])
        first, limited = self.login(client)
        _, other = self.login(client)
        self.assertTrue(first['password_change_required'])
        self.assertTrue(first['member']['password_change_required'])
        self.assertFalse(any(first['member']['permissions'].values()))
        self.assertNotIn('profile', first)
        self.assertNotIn('family', first)
        self.assertTrue(client.get('/api/me', headers=limited).json()['password_change_required'])
        protected = [
            ('GET', '/api/profile'), ('GET', '/api/family'), ('GET', '/api/family/avatar'),
            ('GET', '/api/members'), ('GET', '/api/media'), ('GET', '/api/media/test/file'),
            ('HEAD', '/api/media/test/file'), ('GET', '/api/media/test/thumbnail'),
            ('GET', '/api/media-window'), ('GET', '/api/storage'), ('GET', '/api/trash'),
            ('GET', '/api/growth'), ('GET', '/api/storybooks'), ('GET', '/api/activity'),
            ('GET', '/api/invitations'), ('POST', '/api/invitations'),
            ('POST', '/api/me/username'), ('PUT', '/api/profile'),
            ('PUT', '/api/media/test/favorite'), ('POST', '/api/media/test/comments'),
            ('POST', '/api/upload'), ('POST', '/api/media'),
        ]
        for method, path in protected:
            with self.subTest(method=method, path=path):
                response = client.request(method, path, headers=limited, content=b'invalid body')
                self.assertEqual(response.status_code, 403, response.text)
                if method != 'HEAD':
                    self.assertTrue(response.json()['password_change_required'])
        # Weak defaults are exceptional only at creation, never at registration/change.
        for new_password in ('admin', 'short'):
            self.assertEqual(client.post('/api/me/password', headers=limited, json={
                'current_password': 'admin', 'new_password': new_password}).status_code, 422)
        self.assertEqual(client.post('/api/me/password', headers=limited, json={
            'current_password': 'incorrect', 'new_password': 'new-private-password'}).status_code, 401)
        changed = client.post('/api/me/password', headers=limited, json={
            'current_password': 'admin', 'new_password': 'new-private-password'})
        self.assertEqual(changed.status_code, 200, changed.text)
        session = changed.json()
        self.assertFalse(session['password_change_required'])
        self.assertFalse(session['member']['password_change_required'])
        self.assertIn('profile', session)
        self.assertTrue(session['member']['permissions']['can_upload'])
        self.assertFalse(client.get('/api/status').json()['default_credentials'])
        for stale in (limited, other):
            self.assertEqual(client.get('/api/me', headers=stale).status_code, 401)
        self.assertEqual(client.post('/api/login', json={'username': 'admin', 'password': 'admin'}).status_code, 401)
        current = {'Authorization': 'Bearer ' + session['token']}
        self.assertEqual(client.get('/api/profile', headers=current).status_code, 200)
        self.assertFalse((app.DATA / 'setup-code.txt').exists())

    def test_limited_session_can_log_out_and_restart_keeps_restriction(self):
        _, client = self.load_album()
        _, headers = self.login(client)
        _, restarted = self.load_album()
        self.assertTrue(restarted.get('/api/me', headers=headers).json()['password_change_required'])
        self.assertEqual(restarted.get('/api/profile', headers=headers).status_code, 403)
        self.assertEqual(restarted.post('/api/logout', headers=headers).status_code, 200)
        self.assertEqual(restarted.get('/api/me', headers=headers).status_code, 401)

    def test_existing_account_upgrade_preserves_credentials_and_member(self):
        app, client = self.load_album()
        session, headers = self.unlock(client)
        changed = client.post('/api/me/username', headers=headers, json={
            'current_password': 'my-personal-password', 'new_username': 'private.owner'})
        self.assertEqual(changed.status_code, 200)
        existing = changed.json()
        with app.database() as connection:
            # Reproduce the previous schema, before the new flag existed.
            connection.execute('ALTER TABLE members DROP COLUMN password_change_required')
            before = dict(connection.execute('SELECT * FROM members').fetchone())
        self.environment['ALBUM_PASSWORD'] = 'must-not-reset-password'
        _, restarted = self.load_album()
        existing_headers = {'Authorization': 'Bearer ' + existing['token']}
        self.assertEqual(restarted.get('/api/me', headers=existing_headers).status_code, 200)
        restored, _ = self.login(restarted, 'private.owner', 'my-personal-password')
        self.assertEqual(restored['member']['id'], existing['member']['id'])
        self.assertFalse(restored['password_change_required'])
        self.assertFalse(restarted.get('/api/status').json()['default_credentials'])
        self.assertEqual(restarted.post('/api/login', json={'username': 'admin', 'password': 'admin'}).status_code, 401)
        with app.database() as connection:
            after = dict(connection.execute('SELECT * FROM members').fetchone())
        self.assertEqual({key: after[key] for key in before}, before)

    def test_existing_empty_database_is_not_reinitialized(self):
        data = Path(self.environment['ALBUM_DATA'])
        data.mkdir()
        # Even an existing zero-byte database must not silently gain a default admin.
        (data / 'album.sqlite').touch()
        app, client = self.load_album()
        status = client.get('/api/status').json()
        self.assertTrue(status['setup_required'])
        self.assertFalse(status['default_credentials'])
        with app.database() as connection:
            self.assertEqual(connection.execute('SELECT COUNT(*) FROM members').fetchone()[0], 0)
        self.assertTrue((data / 'setup-code.txt').exists())

    def test_disabled_or_removed_admin_is_not_recreated(self):
        app, client = self.load_album()
        self.unlock(client)
        with app.database() as connection:
            connection.execute('UPDATE members SET disabled=1')
        _, restarted = self.load_album()
        self.assertFalse(restarted.get('/api/status').json()['default_credentials'])
        with app.database() as connection:
            self.assertEqual(connection.execute('SELECT COUNT(*) FROM members').fetchone()[0], 1)
            connection.execute('DELETE FROM sessions')
            connection.execute('DELETE FROM child_members')
            connection.execute('DELETE FROM members')
        _, empty_restart = self.load_album()
        self.assertTrue(empty_restart.get('/api/status').json()['setup_required'])
        self.assertFalse(empty_restart.get('/api/status').json()['default_credentials'])
        with app.database() as connection:
            self.assertEqual(connection.execute('SELECT COUNT(*) FROM members').fetchone()[0], 0)

    def test_explicit_setup_code_remains_supported(self):
        self.environment['ALBUM_BOOTSTRAP_TOKEN'] = 'synthetic-setup-only'
        _, client = self.load_album()
        self.assertTrue(client.get('/api/status').json()['setup_required'])
        self.assertFalse(client.get('/api/status').json()['default_credentials'])
        self.assertEqual(client.post('/api/setup', json={
            'setup_code': 'synthetic-setup-only', 'username': 'admin', 'password': 'admin',
            'name': '测试管理员'}).status_code, 422)
        setup = client.post('/api/setup', json={
            'setup_code': 'synthetic-setup-only', 'username': 'myowner',
            'password': 'chosen-private-password', 'name': '测试管理员'})
        self.assertEqual(setup.status_code, 200, setup.text)
        self.assertFalse(setup.json()['password_change_required'])

    def test_username_change_preserves_media_favorites_and_revokes_sessions(self):
        app, client = self.load_album()
        identity, headers = self.unlock(client)
        _, second = self.login(client, password='my-personal-password')
        image = io.BytesIO()
        Image.new('RGB', (16, 16), (100, 80, 60)).save(image, 'PNG')
        uploaded = client.post('/api/upload', headers=headers, files={
            'file': ('synthetic-photo.png', image.getvalue(), 'image/png')})
        self.assertEqual(uploaded.status_code, 200, uploaded.text)
        media_id = uploaded.json()['id']
        self.assertEqual(client.put('/api/media/' + media_id + '/favorite', headers=headers,
                                    json={'favorite': True}).status_code, 200)
        self.assertEqual(client.post('/api/media/' + media_id + '/comments', headers=headers,
                                     json={'text': '测试留言'}).status_code, 200)
        with app.database() as connection:
            before = {table: [tuple(row) for row in connection.execute('SELECT * FROM ' + table)]
                      for table in ('media', 'favorites', 'comments')}
        renamed = client.post('/api/me/username', headers=headers, json={
            'current_password': 'my-personal-password', 'new_username': '  My.Owner  '})
        self.assertEqual(renamed.status_code, 200, renamed.text)
        self.assertEqual(renamed.json()['member']['username'], 'my.owner')
        self.assertEqual(renamed.json()['member']['id'], identity['member']['id'])
        self.assertEqual(renamed.json()['member']['name'], identity['member']['name'])
        current = {'Authorization': 'Bearer ' + renamed.json()['token']}
        for stale in (headers, second):
            self.assertEqual(client.get('/api/me', headers=stale).status_code, 401)
        self.assertEqual(client.post('/api/login', json={
            'username': 'admin', 'password': 'my-personal-password'}).status_code, 401)
        self.login(client, 'MY.OWNER', 'my-personal-password')
        self.assertEqual(client.get('/api/media?favorite=true', headers=current).json()['items'][0]['id'], media_id)
        with app.database() as connection:
            after = {table: [tuple(row) for row in connection.execute('SELECT * FROM ' + table)] for table in before}
        self.assertEqual(after, before)
        _, restarted = self.load_album()
        self.login(restarted, 'my.owner', 'my-personal-password')
        self.assertFalse(restarted.get('/api/status').json()['default_credentials'])

    def test_username_validation_collision_and_non_admin_change(self):
        _, client = self.load_album()
        _, owner = self.unlock(client)
        invitation = client.post('/api/invitations', headers=owner, json={'role': 'viewer'}).json()
        accepted = client.post('/api/invitations/accept', json={
            'code': invitation['code'], 'username': 'familyviewer',
            'password': 'family-private-password', 'name': '测试家人'}).json()
        viewer = {'Authorization': 'Bearer ' + accepted['token']}
        for username, password, expected in [
            ('valid.name', 'wrong-password', 401), ('ADMIN', 'family-private-password', 409),
            ('a', 'family-private-password', 422), ('a b', 'family-private-password', 422),
            ('中文账号', 'family-private-password', 422), ('x' * 33, 'family-private-password', 422),
            ('FAMILYVIEWER', 'family-private-password', 422),
        ]:
            with self.subTest(username=username):
                result = client.post('/api/me/username', headers=viewer,
                                     json={'current_password': password, 'new_username': username})
                self.assertEqual(result.status_code, expected, result.text)
                self.assertEqual(client.get('/api/me', headers=viewer).status_code, 200)
        renamed = client.post('/api/me/username', headers=viewer, json={
            'current_password': 'family-private-password', 'new_username': 'grandparent'})
        self.assertEqual(renamed.status_code, 200, renamed.text)
        self.assertEqual(renamed.json()['member']['role'], 'viewer')
        self.assertEqual(renamed.json()['member']['id'], accepted['member']['id'])

    def test_concurrent_first_password_change_only_one_succeeds(self):
        _, client = self.load_album()
        _, first = self.login(client)
        _, second = self.login(client)

        def change(pair):
            headers, password = pair
            return client.post('/api/me/password', headers=headers, json={
                'current_password': 'admin', 'new_password': password})

        with ThreadPoolExecutor(max_workers=2) as pool:
            responses = list(pool.map(change, [(first, 'first-private-password'),
                                               (second, 'second-private-password')]))
        self.assertEqual(sorted(response.status_code for response in responses), [200, 401])
        successful = next(response.json() for response in responses if response.status_code == 200)
        self.assertFalse(successful['password_change_required'])
        self.assertEqual(client.get('/api/me', headers=first).status_code, 401)
        self.assertEqual(client.get('/api/me', headers=second).status_code, 401)
        self.assertEqual(client.get('/api/profile', headers={
            'Authorization': 'Bearer ' + successful['token']}).status_code, 200)

    def test_concurrent_username_claim_has_one_owner(self):
        _, client = self.load_album()
        _, owner = self.unlock(client)
        people = []
        for username in ('firstviewer', 'secondviewer'):
            invite = client.post('/api/invitations', headers=owner, json={'role': 'viewer'}).json()
            result = client.post('/api/invitations/accept', json={
                'code': invite['code'], 'username': username,
                'password': 'private-family-password', 'name': '测试家人'}).json()
            people.append({'Authorization': 'Bearer ' + result['token']})

        def rename(headers):
            return client.post('/api/me/username', headers=headers, json={
                'current_password': 'private-family-password', 'new_username': 'same.name'})

        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(rename, people))
        self.assertEqual(sorted(result.status_code for result in results), [200, 409])
        self.login(client, 'same.name', 'private-family-password')

    def test_concurrent_first_start_creates_only_one_default_admin(self):
        server = Path(__file__).resolve().parents[1]
        environment = os.environ.copy()
        environment.update(self.environment)

        def start(_):
            return subprocess.run([sys.executable, '-X', 'utf8', '-c', 'import app'], cwd=server,
                                  env=environment, capture_output=True, text=True, encoding='utf-8', timeout=30)

        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(start, range(2)))
        for result in results:
            self.assertEqual(result.returncode, 0, result.stderr)
        app, client = self.load_album()
        with app.database() as connection:
            self.assertEqual(connection.execute('SELECT COUNT(*) FROM members').fetchone()[0], 1)
        self.assertTrue(self.login(client)[0]['password_change_required'])


if __name__ == '__main__':
    unittest.main()
