"""Album navigation using isolated, synthetic children and media only."""

import base64
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from PIL import Image


class AlbumLocatorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='fam-album-locator-')
        cls.addClassCleanup(cls.temporary.cleanup)
        folder = Path(cls.temporary.name)
        server = Path(__file__).resolve().parents[1]
        sys.path.insert(0, str(server))
        name = '_synthetic_album_locator_app'
        spec = importlib.util.spec_from_file_location(name, server / 'app.py')
        cls.app = importlib.util.module_from_spec(spec)
        sys.modules[name] = cls.app
        cls.addClassCleanup(sys.modules.pop, name, None)
        environment = {
            'ALBUM_DATA': str(folder / 'data'), 'ALBUM_LIBRARY': str(folder / 'library'),
            'ALBUM_PASSWORD': '', 'ALBUM_BOOTSTRAP_TOKEN': 'synthetic-locator-setup',
            'ALBUM_STORAGE_ROOTS': '[]', 'ALBUM_IMPORT_ROOTS': '[]',
            'ALBUM_STORAGE_ALLOWED_ENDPOINTS': '', 'ALBUM_STORAGE_KEY': '',
        }
        with patch.dict(os.environ, environment):
            spec.loader.exec_module(cls.app)
        cls.client = TestClient(cls.app.app)
        cls.addClassCleanup(cls.client.close)
        cls.addClassCleanup(cls.app.admin_imports.stop)
        cls.addClassCleanup(cls.app.admin_backups.stop)
        response = cls.client.post('/api/setup', json={
            'setup_code': environment['ALBUM_BOOTSTRAP_TOKEN'], 'username': 'locatorowner',
            'password': 'synthetic-locator-password', 'name': '测试管理员', 'birthday': '2020-02-29',
        })
        if response.status_code != 200:
            raise AssertionError(response.text)
        cls.owner = response.json()['member']['id']
        cls.admin = {'Authorization': 'Bearer ' + response.json()['token']}
        cls.first = cls.client.get('/api/children', headers=cls.admin).json()['items'][0]['id']
        created = cls.client.post('/api/children', headers=cls.admin, json={'name': '测试妹妹', 'birthday': None})
        if created.status_code != 200:
            raise AssertionError(created.text)
        cls.second = created.json()['id']
        invited = cls.client.post('/api/invitations', headers=cls.admin,
                                  json={'role': 'viewer', 'child_ids': [cls.first]})
        accepted = cls.client.post('/api/invitations/accept', json={
            'code': invited.json()['code'], 'username': 'locatorviewer',
            'password': 'synthetic-viewer-password', 'name': '测试家人',
        })
        if accepted.status_code != 200:
            raise AssertionError(accepted.text)
        cls.viewer_id = accepted.json()['member']['id']
        cls.viewer = {'Authorization': 'Bearer ' + accepted.json()['token']}
        Image.new('RGB', (12, 12), 'orange').save(cls.app.DATA / 'uploads' / '合成测试照片.jpg')

    def setUp(self):
        self.app.LOGIN_ATTEMPTS.clear()
        with self.app.database() as connection:
            for table in ('comments', 'favorites', 'activity', 'media'):
                connection.execute('DELETE FROM ' + table)
            connection.execute('UPDATE children SET birthday=? WHERE id=?', ('2020-02-29', self.first))
            connection.execute('UPDATE children SET birthday=? WHERE id=?', ('', self.second))
            connection.execute('INSERT OR REPLACE INTO child_members(child_id,member_id,role) VALUES (?,?,?)',
                               (self.first, self.viewer_id, 'viewer'))

    def scope(self, child=None, headers=None):
        return dict(self.admin if headers is None else headers, **{'X-FAM-Child': child or self.first})

    def get(self, path='/api/album-locator', expected=200, headers=None, **query):
        response = self.client.get(path, headers=headers if headers is not None else self.scope(), params=query)
        self.assertEqual(response.status_code, expected, response.text)
        return response.json()

    @staticmethod
    def ids(result):
        return [row['id'] for row in result['items']]

    def seed(self, fixtures, child=None):
        """Each tuple is (id, local capture time, kind, in recycle bin)."""
        with self.app.database() as connection:
            for identity, captured, kind, deleted in fixtures:
                stamp = self.app.parse_time(captured)
                connection.execute('''INSERT INTO media
                    (id,kind,root_kind,relative_path,filename,mime_type,size_bytes,sha256,
                     width,height,duration,captured_at,album_date,sort_time,uploader_id,uploader_name,
                     created_at,updated_at,deleted_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)''',
                    (identity, kind, 'uploads', '合成测试照片.jpg', '合成测试照片.jpg',
                     'image/jpeg' if kind == 'photo' else 'video/mp4', 100, '0' * 64,
                     12, 12, 24 if kind == 'video' else None, stamp.isoformat(), stamp.date().isoformat(),
                     stamp.timestamp(), self.owner, '测试家人', self.app.now_text(), self.app.now_text(),
                     self.app.now_text() if deleted else None))
                connection.execute('INSERT INTO media_children(media_id,child_id,deleted_at,linked_by,created_at) VALUES (?,?,?,?,?)',
                                   (identity, child or self.first, self.app.now_text() if deleted else None,
                                    self.owner, self.app.now_text()))

    def favorite(self, identities, member=None):
        with self.app.database() as connection:
            connection.executemany('INSERT INTO favorites(member_id,media_id,created_at) VALUES (?,?,?)',
                                   [(member or self.owner, identity, self.app.now_text()) for identity in identities])

    def test_empty_album_and_login_required(self):
        self.assertEqual(self.get(), {'items': [], 'total': 0, 'has_birthday': True})
        self.assertEqual(self.get('/api/media'), {'items': [], 'total': 0, 'next_cursor': None,
                                                'previous_cursor': None, 'has_previous': False})
        self.get(expected=401, headers={})

    def test_locator_filters_match_media_and_personal_favorites(self):
        self.seed([
            ('a', '2026-05-17T08:00:00', 'photo', False), ('b', '2026-05-17T09:00:00', 'video', False),
            ('c', '2026-05-16', 'photo', False), ('d', '2026-04-02', 'video', False),
            ('trash', '2026-05-18', 'photo', True),
        ])
        self.favorite(['a', 'b', 'd'])
        self.favorite(['c'], self.viewer_id)
        result = self.get()
        self.assertEqual([(r['date'], r['count']) for r in result['items']],
                         [('2026-05-17', 2), ('2026-05-16', 1), ('2026-04-02', 1)])
        for query in ({'kind': 'photo'}, {'kind': 'video'}, {'month': '2026-05'},
                      {'date': '2026-05-17'}, {'favorite': True},
                      {'kind': 'video', 'month': '2026-05', 'date': '2026-05-17', 'favorite': True}):
            with self.subTest(query=query):
                index = self.get(**query)
                media = self.get('/api/media', **query)
                self.assertEqual(index['total'], media['total'])
                self.assertEqual(sum(row['count'] for row in index['items']), len(media['items']))
                self.assertEqual({row['date'] for row in index['items']}, {row['album_date'] for row in media['items']})
        viewer = self.get(headers=self.scope(headers=self.viewer), favorite=True)
        self.assertEqual([(row['date'], row['count']) for row in viewer['items']], [('2026-05-16', 1)])

    def test_birthday_leap_day_birth_and_before_birth(self):
        days = ['2020-02-28', '2020-02-29', '2020-03-28', '2020-03-29', '2021-02-27',
                '2021-02-28', '2024-02-28', '2024-02-29', '2026-05-29']
        self.seed([(str(i), day, 'photo', False) for i, day in enumerate(days)])
        result = {row['date']: row for row in self.get()['items']}
        expected = {
            '2020-02-28': (-1, '出生前', -1, '出生前'),
            '2020-02-29': (0, '未满1岁', 0, '出生'),
            '2020-03-28': (0, '未满1岁', 0, '出生'),
            '2020-03-29': (0, '未满1岁', 1, '1个月'),
            '2021-02-27': (0, '未满1岁', 11, '11个月'),
            '2021-02-28': (1, '1岁', 12, '1岁'),
            '2024-02-28': (3, '3岁', 47, '3岁11个月'),
            '2024-02-29': (4, '4岁', 48, '4岁'),
            '2026-05-29': (6, '6岁', 75, '6岁3个月'),
        }
        for day, fields in expected.items():
            self.assertEqual(tuple(result[day][key] for key in ('year', 'label', 'age_months', 'month_label')), fields)
            self.assertEqual(result[day]['age'], self.app.age_label('2020-02-29', self.app.parse_time(day).date()))
        self.assertEqual(result['2020-02-29']['age'], '出生当天')
        self.assertEqual(result['2021-02-28']['age'], '1岁')

    def test_month_end_and_missing_birthday_labels(self):
        with self.app.database() as connection:
            connection.execute('UPDATE children SET birthday=? WHERE id=?', ('2023-01-31', self.first))
        self.seed([('a', '2023-02-27', 'photo', False), ('b', '2023-02-28', 'photo', False),
                   ('c', '2023-03-30', 'photo', False), ('d', '2023-03-31', 'photo', False)])
        result = self.get()
        self.assertEqual([(r['age_months'], r['month_label']) for r in result['items']],
                         [(2, '2个月'), (1, '1个月'), (1, '1个月'), (0, '出生')])
        self.seed([('other', '2026-05-17', 'photo', False)], self.second)
        no_birthday = self.get(headers=self.scope(self.second))
        self.assertFalse(no_birthday['has_birthday'])
        row = no_birthday['items'][0]
        self.assertEqual((row['year'], row['label'], row['age_months'], row['month_label']),
                         (2026, '2026年', None, '2026年5月'))

    def test_child_scope_shared_recycle_bin_and_revoked_access(self):
        self.seed([('own', '2024-06-01', 'photo', False), ('shared', '2024-05-01', 'photo', True)])
        self.seed([('other', '2025-06-01', 'photo', False)], self.second)
        with self.app.database() as connection:
            connection.execute('INSERT INTO media_children(media_id,child_id,linked_by,created_at) VALUES (?,?,?,?)',
                               ('shared', self.second, self.owner, self.app.now_text()))
        viewer = self.scope(headers=self.viewer)
        self.assertEqual([r['date'] for r in self.get(headers=viewer)['items']], ['2024-06-01'])
        self.assertEqual([r['date'] for r in self.get(headers=self.scope(self.second))['items']],
                         ['2025-06-01', '2024-05-01'])
        self.get(headers=self.scope(self.second, self.viewer), expected=403)
        self.get('/api/media', headers=viewer, start_date='2025-06-01', expected=404)
        self.get('/api/media', headers=viewer, start_date='2024-05-01', expected=404)
        with self.app.database() as connection:
            connection.execute('DELETE FROM child_members WHERE member_id=?', (self.viewer_id,))
        self.get(headers=viewer, expected=403)
        self.get('/api/media', headers=viewer, start_date='2024-06-01', expected=403)

    def test_jump_to_distant_day_and_return_towards_newer_dates(self):
        self.seed([
            ('a', '2026-05-17', 'photo', False), ('b', '2025-05-17', 'photo', False),
            ('c', '2022-05-17T08:00:00', 'photo', False), ('d', '2022-05-17T09:00:00', 'photo', False),
            ('e', '2020-05-17', 'photo', False), ('f', '2019-05-17', 'photo', False),
        ])
        page = self.get('/api/media', start_date='2022-05-17', limit=2)
        self.assertEqual(self.ids(page), ['c', 'd'])
        self.assertEqual(page['total'], 6)
        self.assertTrue(page['has_previous'])
        self.assertEqual(self.app.decode_cursor(page['previous_cursor'])[2], 'c')
        older = self.get('/api/media', cursor=page['next_cursor'], limit=2)
        self.assertEqual(self.ids(older), ['e', 'f'])
        self.assertIsNone(older['next_cursor'])
        newer = self.get('/api/media', newer_cursor=page['previous_cursor'], limit=2)
        self.assertEqual(self.ids(newer), ['a', 'b'])
        self.assertFalse(newer['has_previous'])
        self.assertEqual(self.ids(self.get('/api/media', cursor=newer['next_cursor'], limit=2)), ['c', 'd'])

    def test_large_single_day_forward_and_reverse_have_no_gaps_or_repeats(self):
        identities = [f'photo-{i:04}' for i in range(317)]
        self.seed([(identity, '2024-06-01T08:00:00', 'photo', False) for identity in identities])
        self.assertEqual(self.get()['items'][0]['count'], 317)
        page = self.get('/api/media', start_date='2024-06-01', limit=37)
        collected = self.ids(page)
        while page['next_cursor']:
            page = self.get('/api/media', cursor=page['next_cursor'], limit=37)
            self.assertEqual(page['total'], 317)
            collected.extend(self.ids(page))
        self.assertEqual(collected, identities)
        reverse_collected = self.ids(page)
        while page['has_previous']:
            page = self.get('/api/media', newer_cursor=page['previous_cursor'], limit=37)
            reverse_collected = self.ids(page) + reverse_collected
        self.assertEqual(reverse_collected, identities)
        self.assertIsNotNone(page['next_cursor'])
        self.assertEqual(self.ids(self.get('/api/media', cursor=page['next_cursor'], limit=37)), identities[37:74])

    def test_reverse_nearest_neighbours_preserve_filters_and_subseconds(self):
        self.seed([('a', '2026-05-17', 'photo', False), ('b', '2026-05-17', 'photo', False),
                   ('skip', '2026-05-16', 'video', False), ('c', '2025-05-17', 'photo', False),
                   ('d', '2024-05-17', 'photo', False), ('e', '2023-05-17', 'photo', False)])
        stamp = self.app.parse_time('2026-05-17').timestamp()
        with self.app.database() as connection:
            connection.execute('UPDATE media SET sort_time=? WHERE id=?', (stamp + .8, 'a'))
            connection.execute('UPDATE media SET sort_time=? WHERE id=?', (stamp + .2, 'b'))
        self.favorite(['a', 'b', 'c', 'd', 'e', 'skip'])
        page = self.get('/api/media', kind='photo', favorite=True, start_date='2023-05-17', limit=2)
        before = self.get('/api/media', kind='photo', favorite=True, newer_cursor=page['previous_cursor'], limit=2)
        self.assertEqual(self.ids(before), ['c', 'd'])
        self.assertTrue(before['has_previous'])
        first = self.get('/api/media', kind='photo', favorite=True, newer_cursor=before['previous_cursor'], limit=2)
        self.assertEqual(self.ids(first), ['b', 'a'])
        self.assertFalse(first['has_previous'])
        self.assertEqual(first['total'], 5)
        self.assertEqual(self.ids(self.get('/api/media', kind='photo', favorite=True, cursor=first['next_cursor'], limit=2)), ['c', 'd'])

    def test_invalid_queries_and_target_outside_current_filter(self):
        self.seed([('photo', '2024-05-17', 'photo', False)])
        cursor = self.get('/api/media')['previous_cursor']
        for path, query in [
            ('/api/media', {'start_date': '2024-05-17', 'cursor': cursor}),
            ('/api/media', {'newer_cursor': cursor, 'cursor': cursor}),
            ('/api/media', {'newer_cursor': cursor, 'start_date': '2024-05-17'}),
            ('/api/media', {'newer_cursor': 'bad'}), ('/api/media', {'newer_cursor': ''}),
            ('/api/media', {'start_date': '2024-02-30'}), ('/api/media', {'start_date': '20240517'}),
            ('/api/media', {'start_date': '2024-05-17T08:00:00'}),
            ('/api/album-locator', {'kind': 'unknown'}), ('/api/album-locator', {'month': '2024-13'}),
            ('/api/album-locator', {'date': 'not-a-date'}), ('/api/album-locator', {'favorite': 'nope'}),
        ]:
            with self.subTest(path=path, query=query):
                error = self.get(path, expected=422, **query)
                self.assertIsInstance(error['detail'], str)
                self.assertTrue(any('\u4e00' <= c <= '\u9fff' for c in error['detail']))
        for value in (float('nan'), float('inf'), True, 10 ** 1000):
            malformed = base64.urlsafe_b64encode(json.dumps(['2024-05-17', value, 'photo']).encode()).decode()
            self.get('/api/media', newer_cursor=malformed, expected=422)
        for query in ({'kind': 'video'}, {'favorite': True}, {'month': '2024-06'}, {'date': '2024-05-18'}):
            self.get('/api/media', start_date='2024-05-17', expected=404, **query)
        self.get('/api/media', start_date='2024-05-18', expected=404)

    def test_index_and_page_read_a_consistent_snapshot_during_changes(self):
        self.seed([('a', '2024-05-17', 'photo', False), ('b', '2024-05-16', 'photo', False)])
        original_birthday = self.app.child_birthday
        modified = False
        def concurrent_change(connection, member):
            nonlocal modified
            birthday = original_birthday(connection, member)
            if not modified:
                modified = True
                self.seed([('new', '2026-05-17', 'photo', False)])
                with self.app.database() as other:
                    other.execute('UPDATE media SET description=? WHERE id=?', ('后来修改的说明', 'a'))
            return birthday
        with patch.object(self.app, 'child_birthday', side_effect=concurrent_change):
            page = self.get('/api/media', limit=1)
        self.assertEqual((self.ids(page), page['total'], page['has_previous']), (['a'], 2, False))
        self.assertNotEqual(page['items'][0]['description'], '后来修改的说明')
        self.assertEqual(self.get('/api/media')['total'], 3)
        modified = False
        with self.app.database() as connection:
            connection.execute("DELETE FROM media WHERE id='new'")
        with patch.object(self.app, 'child_birthday', side_effect=concurrent_change):
            index = self.get()
        self.assertEqual(index['total'], 2)
        self.assertEqual([row['date'] for row in index['items']], ['2024-05-17', '2024-05-16'])
        self.assertEqual(self.get()['total'], 3)


if __name__ == '__main__':
    unittest.main()
