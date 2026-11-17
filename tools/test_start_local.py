"""Check connection guidance without starting a server or reading private data."""

import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import start_local


class ConnectionInstructionsTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='fam-connection-guide-')
        self.addCleanup(self.temporary.cleanup)
        self.settings = {'data': self.temporary.name, 'password': 'synthetic-legacy-secret'}
        self.address = 'http://127.0.0.1:8765'

    def guide(self, status):
        return start_local.connection_instructions(self.settings, self.address, status)

    def test_new_album_explains_default_and_required_change(self):
        result = self.guide({'ready': True, 'default_credentials': True})
        self.assertIn('首次登录账号：admin', result)
        self.assertIn('首次登录密码：admin', result)
        self.assertIn('必须设置自己的新密码', result)
        self.assertNotIn(self.settings['password'], result)

    def test_existing_album_does_not_reveal_or_restore_legacy_secret(self):
        result = self.guide({'ready': True, 'default_credentials': False})
        self.assertIn('当前的用户名和密码', result)
        self.assertNotIn('首次登录密码', result)
        self.assertNotIn(self.settings['password'], result)
        self.assertNotIn('owner', result)

    def test_stale_setup_file_is_not_shown_for_existing_album(self):
        (Path(self.temporary.name) / 'setup-code.txt').write_text('synthetic-setup-secret', encoding='utf-8')
        result = self.guide({'ready': True, 'setup_required': False})
        self.assertNotIn('synthetic-setup-secret', result)

    def test_opted_in_setup_album_keeps_private_setup_instructions(self):
        (Path(self.temporary.name) / 'setup-code.txt').write_text('synthetic-setup-secret', encoding='utf-8')
        result = self.guide({'ready': False, 'setup_required': True})
        self.assertIn('synthetic-setup-secret', result)
        self.assertIn('创建管理员账号', result)
        self.assertNotIn('首次登录密码：admin', result)

    def test_missing_setup_file_explains_next_step(self):
        result = self.guide({'ready': False, 'setup_required': True})
        self.assertIn('在服务端查看首次设置口令', result)
        self.assertNotIn('首次登录密码：admin', result)

    def test_unavailable_status_does_not_guess_default_password(self):
        result = self.guide({})
        self.assertNotIn('首次登录密码：admin', result)
        self.assertNotIn(self.settings['password'], result)

    def test_status_accepts_current_and_legacy_server_names_only(self):
        for name, expected in [('FAM宝贝相册', True), ('小家时光', True), ('其他服务', False)]:
            response = io.BytesIO(json.dumps({'app_name': name, 'ready': True}).encode())
            with patch.object(start_local, 'urlopen', return_value=response):
                self.assertEqual(start_local.is_running(self.address), expected)

    def test_broken_status_does_not_look_like_ready_album(self):
        for content in [b'not json', b'[]']:
            with patch.object(start_local, 'urlopen', return_value=io.BytesIO(content)):
                self.assertEqual(start_local.server_status(self.address), {})


if __name__ == '__main__':
    unittest.main()
