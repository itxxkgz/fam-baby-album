"""Short invitation codes and durable guess limits, using synthetic albums only."""
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import uuid

from fastapi import HTTPException
from fastapi.testclient import TestClient
from starlette.requests import Request


class InvitationCodeTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='fam-invitation-codes-')
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        server = Path(__file__).resolve().parents[1]
        sys.path.insert(0, str(server))
        name = '_invitation_codes_' + uuid.uuid4().hex
        spec = importlib.util.spec_from_file_location(name, server / 'app.py')
        self.app = importlib.util.module_from_spec(spec)
        sys.modules[name] = self.app
        self.addCleanup(sys.modules.pop, name, None)
        with patch.dict(os.environ, {
            'ALBUM_DATA': str(root / 'data'), 'ALBUM_LIBRARY': str(root / 'library'),
            'ALBUM_PASSWORD': '', 'ALBUM_BOOTSTRAP_TOKEN': '', 'ALBUM_STORAGE_KEY': '',
            'ALBUM_STORAGE_ROOTS': '[]', 'ALBUM_IMPORT_ROOTS': '[]', 'ALBUM_STORAGE_ALLOWED_ENDPOINTS': '',
        }):
            spec.loader.exec_module(self.app)
        self.client = TestClient(self.app.app)
        self.addCleanup(self.client.close)
        self.addCleanup(self.app.admin_imports.stop)
        self.addCleanup(self.app.admin_backups.stop)
        response = self.client.post('/api/login', json={'username': 'admin', 'password': 'admin'})
        self.assertEqual(response.status_code, 200, response.text)
        self.admin = {'Authorization': 'Bearer ' + response.json()['token']}
        response = self.client.post('/api/me/password', headers=self.admin, json={
            'current_password': 'admin', 'new_password': 'family-private-password'})
        self.assertEqual(response.status_code, 200, response.text)
        self.token = response.json()['token']
        self.admin = {'Authorization': 'Bearer ' + self.token}
        self.service = self.app.identities
        self.now = time.time()

    def invite(self, **body):
        response = self.client.post('/api/invitations', headers=self.admin, json={'name': '测试奶奶', **body})
        self.assertEqual(response.status_code, 200, response.text)
        return response.json()

    def accept(self, code, **extra):
        return self.client.post('/api/invitations/accept', json={
            'code': code, 'username': 'family-' + uuid.uuid4().hex[:12],
            'password': 'member-private-password', **extra})

    def join(self, code, headers=None):
        return self.client.post('/api/invitations/join', headers=headers or self.admin, json={'code': code})

    @staticmethod
    def request(address):
        return Request({'type': 'http', 'client': (address, 10000), 'headers': []})

    def test_new_code_is_six_ascii_digits_with_leading_zeros_and_session_stays_long(self):
        with patch('identity.secrets.randbelow', return_value=7) as random:
            invitation = self.invite()
        self.assertEqual(invitation['code'], '000007')
        random.assert_called_once_with(1_000_000)
        self.assertIsInstance(invitation['code'], str)
        self.assertRegex(invitation['code'], r'^[0-9]{6}$')
        self.assertGreaterEqual(len(self.token), 40)
        response = self.accept('  000007\n')
        self.assertEqual(response.status_code, 200, response.text)
        self.assertGreaterEqual(len(response.json()['token']), 40)
        self.assertEqual(response.json()['member']['name'], '测试奶奶')
        self.assertFalse(response.json()['member']['is_family_admin'])
        self.assertEqual(self.accept('000007').status_code, 401)

    def test_collision_checks_active_used_revoked_and_expired_history(self):
        for number, state in ((1, 'active'), (2, 'used'), (3, 'revoked'), (4, 'expired')):
            with patch('identity.secrets.randbelow', return_value=number):
                invitation = self.invite()
            with self.app.database() as db:
                if state == 'used':
                    db.execute('UPDATE invitations SET used_at=? WHERE id=?', (self.app.now_text(), invitation['id']))
                elif state == 'revoked':
                    db.execute('UPDATE invitations SET revoked_at=? WHERE id=?', (self.app.now_text(), invitation['id']))
                elif state == 'expired':
                    db.execute('UPDATE invitations SET expires_at=0 WHERE id=?', (invitation['id'],))
        with patch('identity.secrets.randbelow', side_effect=[1, 2, 3, 4, 5]) as random:
            self.assertEqual(self.invite()['code'], '000005')
        self.assertEqual(random.call_count, 5)
        for code in ('000002', '000003', '000004'):
            self.assertEqual(self.accept(code).status_code, 401)
        with self.app.database() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM invitations').fetchone()[0], 5)

    def test_collision_limit_rolls_back_without_revoking_old_bound_invitation(self):
        with self.app.database() as db:
            db.execute('INSERT INTO members(id,name,role,joined_at) VALUES (?,?,?,?)',
                       ('original-person', '原上传家人', '家人', self.app.now_text()))
        with patch('identity.secrets.randbelow', return_value=0):
            original = self.invite(member_id='original-person')
        with patch('identity.secrets.randbelow', return_value=0) as random:
            response = self.client.post('/api/invitations', headers=self.admin,
                                        json={'name': '原上传家人', 'member_id': 'original-person'})
        self.assertEqual(response.status_code, 503, response.text)
        self.assertEqual(random.call_count, 32)
        self.assertIn('稍后重试', response.json()['detail'])
        with self.app.database() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM invitations').fetchone()[0], 1)
            row = db.execute('SELECT used_at,revoked_at FROM invitations WHERE id=?', (original['id'],)).fetchone()
            self.assertEqual(tuple(row), (None, None))
        self.assertEqual(self.accept('000000').status_code, 200)

    def test_parallel_generation_serializes_collisions(self):
        def create():
            response = self.client.post('/api/invitations', headers=self.admin, json={'name': '并发邀请'})
            return response.status_code, response.json()
        with patch('identity.secrets.randbelow', side_effect=[42, 42, 43]):
            with ThreadPoolExecutor(max_workers=2) as pool:
                results = list(pool.map(lambda _: create(), range(2)))
        self.assertTrue(all(status == 200 for status, _ in results), results)
        self.assertEqual({body['code'] for _, body in results}, {'000042', '000043'})

    def test_existing_long_codes_still_work_for_new_and_existing_accounts(self):
        old = 'Legacy_Invitation_With-a-LongCode1'
        invitation = self.invite()
        with self.app.database() as db:
            db.execute('UPDATE invitations SET code_hash=? WHERE id=?', (self.service.code_hash(old), invitation['id']))
        response = self.accept(' ' + old + ' ')
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(self.accept(old).status_code, 401)
        second = self.invite()
        other = 'Legacy_Invitation_With-a-LongCode2'
        with self.app.database() as db:
            db.execute('UPDATE invitations SET code_hash=? WHERE id=?', (self.service.code_hash(other), second['id']))
        self.assertEqual(self.join(' ' + other + ' ').status_code, 200)
        self.assertEqual(self.join(other).status_code, 401)

    def test_accept_and_join_share_success_and_failure_budget_without_locking_login(self):
        with patch('identity.time.time', return_value=self.now):
            first = self.invite()
            self.assertEqual(self.accept(first['code']).status_code, 200)
            for number in range(8):
                response = self.accept('not-a-real-code') if number % 2 else self.join('not-a-real-code')
                self.assertEqual(response.status_code, 401, response.text)
            second = self.invite()
            self.assertEqual(self.join(second['code']).status_code, 200)
            third = self.invite()
            for response in (self.accept(third['code']), self.join(third['code'])):
                self.assertEqual(response.status_code, 429, response.text)
                self.assertEqual(response.json()['detail'], '邀请码尝试太频繁，请稍后再试。')
                self.assertEqual(response.headers['retry-after'], '900')
            login = self.client.post('/api/login', json={'username': 'admin', 'password': 'family-private-password'})
            self.assertEqual(login.status_code, 200, login.text)
            with self.app.database() as db:
                self.assertEqual(db.execute('SELECT COUNT(*) FROM invitation_attempts').fetchone()[0], 10)
                self.assertIsNone(db.execute('SELECT used_at FROM invitations WHERE id=?', (third['id'],)).fetchone()[0])
        with patch('identity.time.time', return_value=self.now + 901):
            response = self.accept(third['code'])
        self.assertEqual(response.status_code, 200, response.text)

    def test_untrusted_forwarded_headers_do_not_reset_source_budget(self):
        with patch('identity.time.time', return_value=self.now):
            for number in range(10):
                response = self.client.post('/api/invitations/accept', headers={
                    'X-Forwarded-For': f'198.51.100.{number}', 'X-Real-IP': f'203.0.113.{number}'},
                    json={'code': 'not-real', 'username': 'someone', 'password': 'private-password'})
                self.assertEqual(response.status_code, 401)
            self.assertEqual(self.join('not-real').status_code, 429)
        with self.app.database() as db:
            self.assertEqual(db.execute('SELECT COUNT(DISTINCT source_hash) FROM invitation_attempts').fetchone()[0], 1)
            self.assertEqual([row[1] for row in db.execute('PRAGMA table_info(invitation_attempts)')], ['source_hash', 'attempted_at'])
            self.assertNotEqual(db.execute('SELECT source_hash FROM invitation_attempts LIMIT 1').fetchone()[0], 'testclient')

    def test_global_limits_cover_source_rotation_and_daily_guessing(self):
        with patch('identity.time.time', return_value=self.now):
            for number in range(30):
                self.service.limit_invitation_attempts(self.request(f'198.51.100.{number}'))
            with self.assertRaises(HTTPException) as error:
                self.service.limit_invitation_attempts(self.request('203.0.113.1'))
            self.assertEqual(error.exception.status_code, 429)
        # Each next group is outside the short window but still inside one day.
        for group, count in ((1, 30), (2, 30), (3, 10)):
            with patch('identity.time.time', return_value=self.now + group * 901):
                for number in range(count):
                    self.service.limit_invitation_attempts(self.request(f'192.0.2.{number}'))
        with patch('identity.time.time', return_value=self.now + 4 * 901):
            with self.assertRaises(HTTPException) as error:
                self.service.limit_invitation_attempts(self.request('203.0.113.2'))
            self.assertEqual(error.exception.status_code, 429)
            self.assertGreater(int(error.exception.headers['Retry-After']), 80000)
        with patch('identity.time.time', return_value=self.now + 86401):
            self.service.limit_invitation_attempts(self.request('203.0.113.2'))

    def test_limits_are_atomic_and_persist_for_a_new_service_instance(self):
        from identity import IdentityService
        other = IdentityService(self.app.database, self.app.DATA)
        request = self.request('2001:db8::1')
        def attempt(index):
            try:
                (self.service if index % 2 else other).limit_invitation_attempts(request)
                return 200
            except HTTPException as error:
                return error.status_code
        with patch('identity.time.time', return_value=self.now):
            with ThreadPoolExecutor(max_workers=8) as pool:
                outcomes = list(pool.map(attempt, range(20)))
            self.assertEqual(outcomes.count(200), 10)
            self.assertEqual(outcomes.count(429), 10)
            restarted = IdentityService(self.app.database, self.app.DATA)
            with self.app.database() as db:
                restarted.initialize(db)
            with self.assertRaises(HTTPException) as error:
                restarted.limit_invitation_attempts(self.request('2001:0db8:0:0:0:0:0:1'))
            self.assertEqual(error.exception.status_code, 429)
        with self.app.database() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM invitation_attempts').fetchone()[0], 10)
        script = '''
from contextlib import contextmanager
from pathlib import Path
import sqlite3, sys
from fastapi import HTTPException
from starlette.requests import Request
from identity import IdentityService
folder = Path(sys.argv[1])
@contextmanager
def database():
    connection = sqlite3.connect(folder / 'album.sqlite', timeout=10)
    connection.row_factory = sqlite3.Row
    try:
        yield connection
        connection.commit()
    except BaseException:
        connection.rollback()
        raise
    finally:
        connection.close()
service = IdentityService(database, folder)
request = Request({'type': 'http', 'client': ('2001:db8::1', 10000), 'headers': []})
try:
    service.limit_invitation_attempts(request)
    print(200)
except HTTPException as error:
    print(error.status_code)
'''
        restarted = subprocess.run([sys.executable, '-X', 'utf8', '-c', script, str(self.app.DATA)],
                                   cwd=Path(__file__).resolve().parents[1], capture_output=True,
                                   text=True, encoding='utf-8', timeout=15)
        self.assertEqual(restarted.returncode, 0, restarted.stderr)
        self.assertEqual(restarted.stdout.strip(), '429')


if __name__ == '__main__':
    unittest.main()
