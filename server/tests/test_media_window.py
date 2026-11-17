"""Continuous browsing against a temporary album, without real media or services."""

from datetime import timedelta
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient


class MediaWindowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='album-media-window-')
        cls.addClassCleanup(cls.temporary.cleanup)
        folder = Path(cls.temporary.name)
        server = Path(__file__).resolve().parents[1]
        sys.path.insert(0, str(server))
        module_name = '_synthetic_media_window_app'
        specification = importlib.util.spec_from_file_location(module_name, server / 'app.py')
        cls.server = importlib.util.module_from_spec(specification)
        sys.modules[module_name] = cls.server
        cls.addClassCleanup(sys.modules.pop, module_name, None)
        environment = {
            'ALBUM_DATA': str(folder / 'data'), 'ALBUM_LIBRARY': str(folder / 'library'),
            'ALBUM_PASSWORD': '', 'ALBUM_BOOTSTRAP_TOKEN': 'synthetic-window-setup',
            'ALBUM_STORAGE_ROOTS': '', 'ALBUM_STORAGE_ALLOWED_ENDPOINTS': '', 'ALBUM_STORAGE_KEY': '',
        }
        with patch.dict(os.environ, environment):
            specification.loader.exec_module(cls.server)
        cls.client = TestClient(cls.server.app)
        cls.addClassCleanup(cls.client.close)
        initialized = cls.client.post('/api/setup', json={
            'setup_code': environment['ALBUM_BOOTSTRAP_TOKEN'], 'username': 'windowowner',
            'password': 'synthetic-window-password', 'name': '测试管理员', 'birthday': '2020-02-03',
        })
        if initialized.status_code != 200:
            raise AssertionError('独立浏览测试的管理员没有创建成功。')
        cls.admin_id = initialized.json()['member']['id']
        cls.admin = {'Authorization': 'Bearer ' + initialized.json()['token']}
        for role in ('contributor', 'viewer'):
            invitation = cls.client.post('/api/invitations', headers=cls.admin, json={'role': role})
            if invitation.status_code != 200:
                raise AssertionError('独立浏览测试的家人邀请没有创建成功。')
            accepted = cls.client.post('/api/invitations/accept', json={
                'code': invitation.json()['code'], 'username': 'window' + role,
                'password': 'synthetic-member-password', 'name': '测试家人',
            })
            if accepted.status_code != 200:
                raise AssertionError('独立浏览测试的家人没有加入成功。')
            setattr(cls, role, {'Authorization': 'Bearer ' + accepted.json()['token']})
            setattr(cls, role + '_id', accepted.json()['member']['id'])

    def setUp(self):
        app = self.server
        app.LOGIN_ATTEMPTS.clear()
        with app.database() as connection:
            for table in ('comments', 'favorites', 'activity', 'media'):
                connection.execute('DELETE FROM ' + table)
        fixtures = [
            ('a', '2026-05-18T08:00:00+08:00', 'photo', self.admin_id, False),
            ('b', '2026-05-18T08:00:00+08:00', 'photo', self.contributor_id, False),
            ('c', '2026-05-18T12:00:00+08:00', 'video', self.admin_id, False),
            ('d', '2026-05-17T09:00:00+08:00', 'photo', self.contributor_id, False),
            ('e', '2026-05-17T15:00:00+08:00', 'photo', self.admin_id, False),
            ('f', '2025-12-31T10:00:00+08:00', 'video', self.admin_id, False),
            ('g', '2024-01-01T08:00:00+08:00', 'photo', self.contributor_id, False),
            ('trash-admin', '2026-05-19T10:00:00+08:00', 'photo', self.admin_id, True),
            ('trash-own-a', '2026-05-18T10:00:00+08:00', 'photo', self.contributor_id, True),
            ('trash-own-b', '2026-05-17T10:00:00+08:00', 'video', self.contributor_id, True),
        ]
        self.seed(fixtures)
        with app.database() as connection:
            connection.executemany('INSERT INTO favorites(member_id,media_id,created_at) VALUES (?,?,?)',
                [(self.admin_id, identity, app.now_text()) for identity in ('a', 'c', 'e')] +
                [(self.viewer_id, identity, app.now_text()) for identity in ('b', 'd')])

    def seed(self, fixtures):
        app = self.server
        with app.database() as connection:
            for identity, captured, kind, owner, deleted in fixtures:
                stamp = app.parse_time(captured)
                connection.execute('''INSERT INTO media
                    (id,kind,root_kind,relative_path,filename,mime_type,size_bytes,sha256,
                     width,height,duration,captured_at,album_date,sort_time,uploader_id,uploader_name,
                     created_at,updated_at,deleted_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)''',
                    (identity, kind, 'uploads', 'synthetic/' + identity,
                     '合成测试照片.jpg' if kind == 'photo' else '合成测试视频.mp4',
                     'image/jpeg' if kind == 'photo' else 'video/mp4', 100, '0' * 64,
                     800, 600, 24 if kind == 'video' else None,
                     stamp.isoformat(), stamp.date().isoformat(), stamp.timestamp(), owner, '测试家人',
                     app.now_text(), app.now_text(), app.now_text() if deleted else None))
                connection.execute("INSERT INTO media_children(media_id,child_id,deleted_at,linked_by,created_at) SELECT ?,value,?,?,? FROM settings WHERE key='default_child_id'", (identity, app.now_text() if deleted else None, owner, app.now_text()))

    def request(self, anchor, headers=None, expected=200, **query):
        response = self.client.get('/api/media-window',
                                   params={'anchor': anchor, **query},
                                   headers=self.admin if headers is None else headers)
        self.assertEqual(response.status_code, expected, response.text)
        return response.json()

    @staticmethod
    def ids(result):
        return [item['id'] for item in result['items']]

    def test_cross_day_order_and_same_time_tiebreaker(self):
        result = self.request('c', before=2, after=2)
        self.assertEqual(self.ids(result), ['a', 'b', 'c', 'd', 'e'])
        self.assertEqual((result['index'], result['position'], result['total']), (2, 2, 7))
        self.assertFalse(result['has_before'])
        self.assertTrue(result['has_after'])
        reference = self.client.get('/api/media', headers=self.admin).json()
        self.assertEqual(self.ids(self.request('d', before=100, after=100)), self.ids(reference))

    def test_subsecond_sort_time_matches_window_position(self):
        second = self.server.parse_time('2026-05-18T08:00:00+08:00').timestamp()
        expected_times = {'a': second + .8, 'b': second + .2}
        with self.server.database() as connection:
            connection.executemany('UPDATE media SET sort_time=? WHERE id=?',
                                   [(value, identity) for identity, value in expected_times.items()])
        reference = self.client.get('/api/media', headers=self.admin).json()
        self.assertEqual(self.ids(reference)[:2], ['b', 'a'])
        first, second_item = reference['items'][:2]
        self.assertEqual(first['captured_at'], second_item['captured_at'])
        self.assertEqual(first['album_date'], second_item['album_date'])
        self.assertEqual(first['sort_time'], expected_times['b'])
        self.assertEqual(second_item['sort_time'], expected_times['a'])
        for position, identity in enumerate(('b', 'a')):
            result = self.request(identity, before=1, after=1)
            self.assertEqual(self.ids(result)[:2], ['b', 'a'])
            self.assertEqual(result['position'], position)
            self.assertEqual(result['index'], position)
            current = result['items'][result['index']]
            self.assertEqual(current['id'], identity)
            self.assertEqual(current['sort_time'], expected_times[identity])

    def test_edges_and_anchor_only_window(self):
        first = self.request('a', before=2, after=2)
        self.assertEqual(self.ids(first), ['a', 'b', 'c'])
        self.assertEqual((first['index'], first['position']), (0, 0))
        self.assertFalse(first['has_before'])
        self.assertTrue(first['has_after'])
        last = self.request('g', before=2, after=2)
        self.assertEqual(self.ids(last), ['e', 'f', 'g'])
        self.assertEqual((last['index'], last['position']), (2, 6))
        self.assertTrue(last['has_before'])
        self.assertFalse(last['has_after'])
        only = self.request('d', before=0, after=0)
        self.assertEqual(self.ids(only), ['d'])
        self.assertEqual((only['index'], only['position']), (0, 3))
        self.assertTrue(only['has_before'])
        self.assertTrue(only['has_after'])

    def test_append_and_prepend_windows_without_gaps_or_duplicates(self):
        result = self.request('a', before=0, after=2)
        forward = self.ids(result)
        while result['has_after']:
            result = self.request(forward[-1], before=0, after=2)
            self.assertEqual(result['index'], 0)
            self.assertEqual(result['items'][0]['id'], forward[-1])
            forward.extend(self.ids(result)[1:])
        self.assertEqual(forward, list('abcdefg'))
        result = self.request('g', before=2, after=0)
        backward = self.ids(result)
        while result['has_before']:
            result = self.request(backward[0], before=2, after=0)
            self.assertEqual(result['index'], len(result['items']) - 1)
            self.assertEqual(result['items'][-1]['id'], backward[0])
            backward = self.ids(result)[:-1] + backward
        self.assertEqual(backward, forward)
        self.assertEqual(len(set(backward)), len(backward))

    def test_kind_month_and_explicit_date_filters(self):
        photo = self.request('d', kind='photo', before=2, after=2)
        self.assertEqual(self.ids(photo), ['a', 'b', 'd', 'e', 'g'])
        self.assertEqual((photo['position'], photo['total']), (2, 5))
        month = self.request('d', month='2026-05', before=100, after=100)
        self.assertEqual(self.ids(month), list('abcde'))
        exact = self.request('b', date='2026-05-18', before=100, after=100)
        self.assertEqual(self.ids(exact), list('abc'))
        mixed = self.request('b', kind='photo', month='2026-05', date='2026-05-18')
        self.assertEqual(self.ids(mixed), ['a', 'b'])
        self.assertEqual((mixed['position'], mixed['total']), (1, 2))
        for options in ({'kind': 'video'}, {'month': '2025-12'}, {'date': '2026-05-17'}):
            self.request('a', expected=404, **options)

    def test_personal_favorites_and_per_item_permissions(self):
        owner = self.request('c', favorite=True)
        self.assertEqual(self.ids(owner), ['a', 'c', 'e'])
        self.assertEqual((owner['position'], owner['total']), (1, 3))
        self.assertTrue(all(item['favorite'] and item['can_edit'] for item in owner['items']))
        viewer = self.request('d', headers=self.viewer, favorite=True)
        self.assertEqual(self.ids(viewer), ['b', 'd'])
        self.assertEqual((viewer['position'], viewer['total']), (1, 2))
        self.assertTrue(all(item['favorite'] and not item['can_edit'] for item in viewer['items']))
        self.request('a', headers=self.viewer, favorite=True, expected=404)
        contributor = self.request('b', headers=self.contributor)
        self.assertEqual({item['id'] for item in contributor['items'] if item['can_edit']}, {'b', 'd', 'g'})
        self.assertTrue(all(not item['favorite'] for item in contributor['items']))
        for item in contributor['items']:
            self.assertNotIn('relative_path', item)
            self.assertNotIn('metadata_json', item)
            self.assertNotIn('sha256', item)

    def test_trash_visibility_and_unavailable_anchors(self):
        self.request('a', headers=self.viewer, deleted=True, expected=403)
        self.request('trash-admin', headers=self.viewer, expected=404)
        owner = self.request('trash-own-a', deleted=True)
        self.assertEqual(self.ids(owner), ['trash-admin', 'trash-own-a', 'trash-own-b'])
        self.assertTrue(all(item['can_restore'] and not item['can_edit'] for item in owner['items']))
        contributor = self.request('trash-own-a', headers=self.contributor, deleted=True)
        self.assertEqual(self.ids(contributor), ['trash-own-a', 'trash-own-b'])
        self.assertEqual((contributor['position'], contributor['total']), (0, 2))
        self.assertFalse(contributor['has_before'])
        self.assertFalse(contributor['has_after'])
        self.request('trash-admin', headers=self.contributor, deleted=True, expected=404)
        unavailable = self.request('missing-photo', expected=404)
        outside = self.request('trash-admin', expected=404)
        self.assertEqual(unavailable, outside)
        self.request('a', deleted=True, expected=404)

    def test_bounded_window_and_request_validation(self):
        beginning = self.server.parse_time('2022-01-01T08:00:00+08:00')
        self.seed([(f'bulk-{index:03}', (beginning + timedelta(seconds=index)).isoformat(),
                    'photo', self.admin_id, False) for index in range(250)])
        result = self.request('bulk-125', before=10000, after=10000)
        self.assertEqual(len(result['items']), 201)
        self.assertEqual((result['index'], result['position'], result['total']), (100, 132, 257))
        self.assertTrue(result['has_before'])
        self.assertTrue(result['has_after'])
        self.assertEqual(self.ids(self.request('a', before=-2, after=-9)), ['a'])
        self.request('a', before='not-a-number', expected=422)
        self.request('a', month='2026-99', expected=422)
        self.request('a', kind='unknown', expected=422)
        self.request('a', headers={}, expected=401)
        self.assertEqual(self.client.get('/api/media-window', headers=self.admin).status_code, 422)

    def test_changed_anchor_and_updated_order_do_not_return_stale_content(self):
        self.assertEqual(self.request('d', before=0, after=0)['position'], 3)
        moved = self.client.patch('/api/media/d', headers=self.admin,
                                  json={'captured_at': '2026-06-01T08:00:00+08:00'})
        self.assertEqual(moved.status_code, 200)
        result = self.request('d', before=1, after=1)
        self.assertEqual(self.ids(result), ['d', 'a'])
        self.assertEqual(result['position'], 0)
        self.assertFalse(result['has_before'])
        self.assertEqual(self.client.delete('/api/media/d', headers=self.admin).status_code, 200)
        self.request('d', expected=404)
        self.assertEqual(self.request('e', before=0, after=0)['total'], 6)


if __name__ == '__main__':
    unittest.main()
