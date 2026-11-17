"""Cross-feature child isolation, shared originals, durable tasks and restores."""
import importlib.util
import io
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import uuid

from fastapi.testclient import TestClient
from PIL import Image


class ChildFeatureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='fam-child-features-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.app, self.client = self.load(self.root)
        initial = self.client.post('/api/login', json={'username': 'admin', 'password': 'admin'}).json()
        result = self.client.post('/api/me/password', headers={'Authorization': 'Bearer ' + initial['token']},
                                  json={'current_password': 'admin', 'new_password': 'synthetic-family-password'})
        self.assertEqual(result.status_code, 200, result.text)
        self.auth = {'Authorization': 'Bearer ' + result.json()['token']}
        self.child_a = self.call('GET', '/api/children')['items'][0]['id']
        self.call('PATCH', '/api/children/' + self.child_a, body={'name': '测试姐姐', 'birthday': '2020-01-01'})
        self.child_b = self.call('POST', '/api/children', body={'name': '测试弟弟', 'birthday': '2022-01-01'})['id']
        self.a = {**self.auth, 'X-FAM-Child': self.child_a}
        self.b = {**self.auth, 'X-FAM-Child': self.child_b}

    def load(self, folder):
        server = Path(__file__).resolve().parents[1]
        sys.path.insert(0, str(server))
        name = '_child_features_' + uuid.uuid4().hex
        spec = importlib.util.spec_from_file_location(name, server / 'app.py')
        app = importlib.util.module_from_spec(spec)
        sys.modules[name] = app
        self.addCleanup(sys.modules.pop, name, None)
        env = {'ALBUM_DATA': str(folder / 'data'), 'ALBUM_LIBRARY': str(folder / 'library'),
               'ALBUM_PASSWORD': '', 'ALBUM_BOOTSTRAP_TOKEN': '', 'ALBUM_STORAGE_KEY': '',
               'ALBUM_STORAGE_ROOTS': '[]', 'ALBUM_IMPORT_ROOTS': '[]', 'ALBUM_STORAGE_ALLOWED_ENDPOINTS': ''}
        with patch.dict(os.environ, env):
            spec.loader.exec_module(app)
        client = TestClient(app.app)
        self.addCleanup(client.close)
        self.addCleanup(app.admin_imports.stop)
        self.addCleanup(app.admin_backups.stop)
        return app, client

    def call(self, method, path, body=None, headers=None, expected=200):
        response = self.client.request(method, path, headers=self.auth if headers is None else headers, json=body)
        self.assertEqual(response.status_code, expected, response.text)
        return response.json()

    def photo(self, headers=None, color='tomato', targets=None):
        buffer = io.BytesIO()
        picture = Image.new('RGB', (25, 20), color)
        exif = Image.Exif(); exif[36867] = '2026:05:17 10:20:30'
        picture.save(buffer, 'JPEG', exif=exif)
        data = {'child_ids': json.dumps(targets)} if targets else {}
        response = self.client.post('/api/upload', headers=headers or self.a, data=data,
                                    files={'file': ('孩子的合照.jpg', buffer.getvalue(), 'image/jpeg')})
        self.assertEqual(response.status_code, 200, response.text)
        return response.json(), buffer.getvalue()

    @staticmethod
    def book(photo, title):
        return {'title': title, 'cover_media_id': photo['id'], 'revision': 0,
                'pages': [{'media_id': photo['id'], 'text': title + '的故事'}]}

    def wait_job(self, service, identity, field='status'):
        until = time.monotonic() + 15
        while time.monotonic() < until:
            item = service(identity)
            if item[field] in ('completed', 'failed', 'paused'):
                self.assertEqual(item[field], 'completed', item.get('message'))
                return item
            time.sleep(.025)
        self.fail('测试任务未在期限内完成')

    def test_growth_books_comments_and_activity_are_separate_for_shared_photo(self):
        photo, original = self.photo(targets=[self.child_a, self.child_b])
        self.call('PUT', '/api/growth/6', {'media_id': photo['id']}, self.a)
        self.call('PUT', '/api/growth/4', {'media_id': photo['id']}, self.b)
        self.assertEqual([6], [r['year'] for r in self.call('GET', '/api/growth', headers=self.a)['years']])
        self.assertEqual([4], [r['year'] for r in self.call('GET', '/api/growth', headers=self.b)['years']])
        self.call('PUT', '/api/storybooks/2026-05', self.book(photo, '姐姐的五月'), self.a)
        self.call('PUT', '/api/storybooks/2026-05', self.book(photo, '弟弟的五月'), self.b)
        self.assertEqual('姐姐的五月', self.call('GET', '/api/storybooks/2026-05', headers=self.a)['title'])
        self.assertEqual('弟弟的五月', self.call('GET', '/api/storybooks/2026-05', headers=self.b)['title'])
        comment = self.call('POST', f"/api/media/{photo['id']}/comments", {'text': '姐姐的留言'}, self.a)
        self.call('POST', f"/api/media/{photo['id']}/comments", {'text': '弟弟的留言'}, self.b)
        self.call('POST', f"/api/media/{photo['id']}/comments", {'text': '不能串回复', 'reply_to_id': comment['id']}, self.b, 409)
        for headers, text in ((self.a, '姐姐的留言'), (self.b, '弟弟的留言')):
            detail = self.call('GET', f"/api/media/{photo['id']}", headers=headers)
            self.assertEqual([text], [c['text'] for c in detail['comments']])
            feed = self.call('GET', '/api/activity-feed?kind=comment', headers=headers)
            self.assertEqual([text], [i['text'] for i in feed['items']])
        group = self.call('GET', '/api/activity-feed?kind=media', headers=self.a)['items'][0]['id']
        self.call('GET', '/api/activity-feed/' + group + '/media', headers=self.b, expected=422)
        self.call('DELETE', '/api/media/' + photo['id'], headers=self.a)
        self.assertEqual([], self.call('GET', '/api/media', headers=self.a)['items'])
        self.assertEqual(1, len(self.call('GET', '/api/media', headers=self.b)['items']))
        self.assertEqual(1, self.call('GET', '/api/storybooks/2026-05', headers=self.b)['total'])
        self.assertEqual(0, self.call('GET', '/api/storybooks/2026-05', headers=self.a)['total'])
        self.assertEqual(original, self.client.get('/api/media/' + photo['id'] + '/file', headers=self.b).content)
        with self.app.database() as db:
            self.assertEqual(1, db.execute('SELECT COUNT(*) FROM media').fetchone()[0])
            self.assertEqual(2, db.execute('SELECT COUNT(*) FROM media_children').fetchone()[0])

    def test_admin_batch_cannot_mix_scopes_and_cookie_overview_is_scoped(self):
        first, _ = self.photo()
        second, _ = self.photo(self.b, 'teal')
        self.call('POST', '/api/admin/media/batch', {'action': 'trash', 'ids': [first['id'], second['id']]}, self.a, 409)
        self.assertEqual(1, self.call('GET', '/api/admin/media', headers=self.a)['total'])
        self.call('POST', '/api/admin/media/batch', {'action': 'link', 'ids': [first['id']], 'child_ids': [self.child_b]}, self.a)
        self.assertEqual(2, self.call('GET', '/api/admin/media', headers=self.b)['total'])
        login = self.client.post('/api/admin/session', json={'username': 'admin', 'password': 'synthetic-family-password'})
        self.assertEqual(login.status_code, 200)
        cookie_b = {'X-FAM-Child': self.child_b, 'X-FAM-CSRF': login.json()['csrf']}
        summary = self.call('GET', '/api/admin/overview', headers=cookie_b)
        self.assertEqual(self.child_b, summary['profile']['child_id'])
        self.assertEqual(2, summary['profile']['counts']['photos'])
        self.call('POST', '/api/admin/media/batch', {'action': 'trash', 'ids': [first['id']]}, cookie_b)
        self.assertEqual(1, self.call('GET', '/api/admin/media', headers=self.a)['total'])
        self.assertEqual(1, self.call('GET', '/api/admin/media?deleted=true', headers=cookie_b)['total'])

    def test_import_snapshot_reuses_original_without_changing_other_album(self):
        first, original = self.photo()
        job = self.call('POST', '/api/admin/imports', {'name': '弟弟的导入', 'child_ids': [self.child_b]}, self.a)
        response = self.client.post(f"/api/admin/imports/{job['id']}/files", headers=self.a,
                                    files={'file': ('同一张合照.jpg', original, 'image/jpeg')})
        self.assertEqual(response.status_code, 200)
        self.call('POST', f"/api/admin/imports/{job['id']}/start", {}, self.a)
        finished = self.wait_job(self.app.admin_imports.get_job, job['id'])
        self.assertEqual((finished['imported'], finished['duplicates'], finished['child_ids']), (1, 0, [self.child_b]))
        self.assertEqual([], self.call('GET', '/api/admin/imports', headers=self.a)['items'])
        self.assertEqual(1, len(self.call('GET', '/api/admin/imports', headers=self.b)['items']))
        a = self.call('GET', '/api/media', headers=self.a)['items']
        b = self.call('GET', '/api/media', headers=self.b)['items']
        self.assertEqual(a[0]['id'], b[0]['id'])
        self.assertEqual(a[0]['captured_at'], b[0]['captured_at'])
        self.assertEqual(1, len(self.call('GET', '/api/activity-feed?kind=media', headers=self.b)['items']))
        self.call('PATCH', '/api/children/' + self.child_b, {'archived': True})
        self.call('POST', '/api/admin/imports', {'child_ids': [self.child_b]}, self.a, 409)
        self.call('POST', '/api/admin/media/batch', {'action': 'edit', 'ids': [first['id']], 'description': '不能改归档共用资料'}, self.a, 403)

    def test_full_backup_restores_both_children_links_roles_and_separate_books(self):
        photo, original = self.photo(targets=[self.child_a, self.child_b])
        self.call('PUT', '/api/storybooks/2026-05', self.book(photo, '姐姐的故事'), self.a)
        self.call('PUT', '/api/storybooks/2026-05', self.book(photo, '弟弟的故事'), self.b)
        invitation = self.call('POST', '/api/invitations', {'name': '只看姐姐的家人', 'role': 'viewer', 'child_ids': [self.child_a]})
        joined = self.call('POST', '/api/invitations/accept', {'code': invitation['code'], 'username': 'restricted',
                           'password': 'synthetic-reader-password', 'name': '只看姐姐的家人'})
        backup = self.call('POST', '/api/admin/backups', {'mode': 'full'}, expected=202)
        self.wait_job(self.app.admin_backups.get, backup['id'])
        restored = self.call('POST', '/api/admin/backups/' + backup['id'] + '/restore',
                             {'current_password': 'synthetic-family-password'})
        restored_app, client = self.load(Path(restored['directory']))
        login = client.post('/api/login', json={'username': 'admin', 'password': 'synthetic-family-password'}).json()
        headers = {'Authorization': 'Bearer ' + login['token']}
        self.assertEqual(2, len(client.get('/api/children', headers=headers).json()['items']))
        for child, title in ((self.child_a, '姐姐的故事'), (self.child_b, '弟弟的故事')):
            scoped = {**headers, 'X-FAM-Child': child}
            self.assertEqual(title, client.get('/api/storybooks/2026-05', headers=scoped).json()['title'])
            self.assertEqual(original, client.get('/api/media/' + photo['id'] + '/file', headers=scoped).content)
        reader = client.post('/api/login', json={'username': 'restricted', 'password': 'synthetic-reader-password'}).json()
        restricted = {'Authorization': 'Bearer ' + reader['token']}
        self.assertEqual([self.child_a], [i['id'] for i in client.get('/api/children', headers=restricted).json()['items']])
        self.assertEqual(403, client.get('/api/media', headers={**restricted, 'X-FAM-Child': self.child_b}).status_code)
        with restored_app.database() as db:
            self.assertEqual(1, db.execute('SELECT COUNT(*) FROM media').fetchone()[0])
            self.assertEqual(2, db.execute('SELECT COUNT(*) FROM child_storybooks').fetchone()[0])

    def test_import_skips_identical_bytes_already_linked_under_another_record(self):
        first, original = self.photo()
        second, _ = self.photo(self.b)
        self.assertNotEqual(first['id'], second['id'])
        job = self.call('POST', '/api/admin/imports', {'name': '检查已有合照', 'child_ids': [self.child_a, self.child_b]}, self.b)
        response = self.client.post(f"/api/admin/imports/{job['id']}/files", headers=self.b,
                                    files={'file': ('已有合照.jpg', original, 'image/jpeg')})
        self.assertEqual(response.status_code, 200)
        self.call('POST', f"/api/admin/imports/{job['id']}/start", {}, self.b)
        finished = self.wait_job(self.app.admin_imports.get_job, job['id'])
        self.assertEqual((finished['duplicates'], finished['imported']), (1, 0))
        self.assertEqual([second['id']], [r['id'] for r in self.call('GET', '/api/media', headers=self.b)['items']])
        with self.app.database() as db:
            self.assertEqual(2, db.execute('SELECT COUNT(*) FROM media_children').fetchone()[0])

    def test_added_shared_photo_activity_names_the_person_who_added_it(self):
        invitation = self.call('POST', '/api/invitations', {'name': '原上传家人', 'role': 'contributor', 'child_ids': [self.child_a]})
        member = self.call('POST', '/api/invitations/accept', {'code': invitation['code'], 'username': 'photographer',
                           'password': 'synthetic-uploader-password', 'name': '原上传家人'})
        own = {'Authorization': 'Bearer ' + member['token'], 'X-FAM-Child': self.child_a}
        photo, _ = self.photo(own)
        self.call('POST', '/api/media/' + photo['id'] + '/children', {'child_ids': [self.child_b]}, self.a)
        owner = self.call('GET', '/api/me', headers=self.a)
        event = self.call('GET', '/api/activity-feed?kind=media', headers=self.b)['items'][0]
        self.assertEqual(owner['id'], event['actor']['id'])
        self.assertEqual(member['member']['id'], event['preview'][0]['uploader_id'])


if __name__ == '__main__':
    unittest.main()
