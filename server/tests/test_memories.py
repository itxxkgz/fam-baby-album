"""Growth and storybook API regression tests with an isolated synthetic library."""

from concurrent.futures import ThreadPoolExecutor
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient


class MemoryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='album-memories-')
        cls.addClassCleanup(cls.temporary.cleanup)
        folder = Path(cls.temporary.name)
        server = Path(__file__).resolve().parents[1]
        sys.path.insert(0, str(server))
        name = '_synthetic_memories_app'
        spec = importlib.util.spec_from_file_location(name, server / 'app.py')
        cls.app = importlib.util.module_from_spec(spec)
        sys.modules[name] = cls.app
        cls.addClassCleanup(sys.modules.pop, name, None)
        env = {'ALBUM_DATA': str(folder / 'data'), 'ALBUM_LIBRARY': str(folder / 'library'),
               'ALBUM_PASSWORD': '', 'ALBUM_BOOTSTRAP_TOKEN': 'synthetic-memories-setup',
               'ALBUM_STORAGE_ROOTS': '', 'ALBUM_STORAGE_ALLOWED_ENDPOINTS': '', 'ALBUM_STORAGE_KEY': ''}
        with patch.dict(os.environ, env):
            spec.loader.exec_module(cls.app)
        cls.client = TestClient(cls.app.app)
        cls.addClassCleanup(cls.client.close)
        result = cls.client.post('/api/setup', json={'setup_code': env['ALBUM_BOOTSTRAP_TOKEN'],
            'username': 'memoryowner', 'password': 'synthetic-owner-password', 'name': '测试管理员',
            'birthday': '2020-02-29'})
        if result.status_code != 200:
            raise AssertionError('隔离测试相册初始化失败。')
        cls.admin = {'Authorization': 'Bearer ' + result.json()['token']}
        cls.admin_id = result.json()['member']['id']
        for role in ('contributor', 'viewer'):
            invite = cls.client.post('/api/invitations', headers=cls.admin, json={'role': role})
            accepted = cls.client.post('/api/invitations/accept', json={
                'code': invite.json()['code'], 'username': 'memory' + role,
                'password': 'synthetic-family-password', 'name': '测试上传家人' if role == 'contributor' else '测试查看家人'})
            if accepted.status_code != 200:
                raise AssertionError('隔离测试家人创建失败。')
            setattr(cls, role, {'Authorization': 'Bearer ' + accepted.json()['token']})
            setattr(cls, role + '_id', accepted.json()['member']['id'])

    def setUp(self):
        self.app.LOGIN_ATTEMPTS.clear()
        with self.app.database() as connection:
            for table in ('child_storybook_pages', 'child_storybooks', 'child_growth_selections', 'storybook_pages', 'storybooks', 'growth_selections', 'comments', 'favorites', 'activity', 'media'):
                connection.execute('DELETE FROM ' + table)
            connection.execute("UPDATE children SET birthday='2020-02-29'")
        fixtures = [
            ('prenatal', '2020-02-28T09:00:00+08:00', 'photo', ''),
            ('birth', '2020-02-29T09:00:00+08:00', 'photo', '出生那一天'),
            ('before-one', '2021-02-27T09:00:00+08:00', 'photo', ''),
            ('one', '2021-02-28T09:00:00+08:00', 'photo', ''),
            ('before-four', '2024-02-28T09:00:00+08:00', 'photo', ''),
            ('four', '2024-02-29T09:00:00+08:00', 'photo', ''),
            ('may-a', '2026-05-01T09:00:00+08:00', 'photo', '全家一起散步'),
            ('may-b', '2026-05-01T09:00:00+08:00', 'photo', '第二张的原始说明'),
            ('may-video', '2026-05-01T10:00:00+08:00', 'video', '今天学会的新歌'),
            ('may-later', '2026-05-17T09:00:00+08:00', 'photo', ''),
            ('april', '2026-04-01T09:00:00+08:00', 'photo', ''),
        ]
        self.seed(fixtures)

    def seed(self, fixtures):
        with self.app.database() as connection:
            for identity, captured, kind, story in fixtures:
                stamp = self.app.parse_time(captured)
                connection.execute("""INSERT INTO media
                    (id,kind,root_kind,relative_path,filename,mime_type,size_bytes,sha256,width,height,
                     duration,captured_at,album_date,sort_time,uploader_id,uploader_name,description,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", (
                    identity, kind, 'uploads', 'synthetic/' + identity,
                    '测试照片.jpg' if kind == 'photo' else '测试视频.mp4',
                    'image/jpeg' if kind == 'photo' else 'video/mp4', 100, '0' * 64, 800, 600,
                    15 if kind == 'video' else None, stamp.isoformat(), stamp.date().isoformat(), stamp.timestamp(),
                    self.admin_id, '测试管理员', story, self.app.now_text(), self.app.now_text()))
                connection.execute("INSERT INTO media_children(media_id,child_id,deleted_at,linked_by,created_at) SELECT ?,value,NULL,?,? FROM settings WHERE key='default_child_id'", (identity, self.admin_id, self.app.now_text()))

    def request(self, method, path, expected=200, headers=None, body=None, **params):
        result = self.client.request(method, '/api/' + path, headers=self.admin if headers is None else headers,
                                     json=body, params=params)
        self.assertEqual(expected, result.status_code, result.text)
        return result.json()

    def book_body(self, revision=0, ids=('may-a', 'may-video', 'may-later')):
        return {'title': '春天里的小日子', 'cover_media_id': ids[0], 'revision': revision,
                'pages': [{'media_id': identity, 'text': '家人写下的新故事' if index == 0 else ''}
                          for index, identity in enumerate(ids)]}

    @staticmethod
    def ids(result):
        return [item['id'] for item in result['items']]

    def test_growth_full_year_boundaries_and_leap_day(self):
        result = self.request('GET', 'growth')
        self.assertEqual([0, 1, 3, 4, 6], [entry['year'] for entry in result['years']])
        self.assertEqual('出生至1岁', result['years'][0]['label'])
        self.assertEqual(['before-one', 'birth'], self.ids(self.request('GET', 'growth/0/photos')))
        self.assertEqual(['one'], self.ids(self.request('GET', 'growth/1/photos')))
        self.assertEqual(['before-four'], self.ids(self.request('GET', 'growth/3/photos')))
        self.assertEqual(['four'], self.ids(self.request('GET', 'growth/4/photos')))
        self.assertNotIn('prenatal', [entry['selected']['id'] for entry in result['years']])

    def test_growth_missing_birthday_and_empty_library(self):
        with self.app.database() as connection:
            connection.execute("UPDATE children SET birthday=''")
        result = self.request('GET', 'growth')
        self.assertEqual([], result['years'])
        self.assertTrue(result['message'])
        self.request('GET', 'growth/1/photos', expected=422)
        self.request('PUT', 'growth/1', expected=422, body={'media_id': 'one'})
        with self.app.database() as connection:
            connection.execute("UPDATE children SET birthday='2020-02-29'")
            connection.execute('DELETE FROM media')
        self.assertEqual([], self.request('GET', 'growth')['years'])

    def test_growth_photo_only_pagination_preserves_subseconds(self):
        with self.app.database() as connection:
            connection.execute("UPDATE media SET sort_time=sort_time+.9 WHERE id='may-a'")
            connection.execute("UPDATE media SET sort_time=sort_time+.1 WHERE id='may-b'")
        first = self.request('GET', 'growth/6/photos', limit=2)
        second = self.request('GET', 'growth/6/photos', limit=2, cursor=first['next_cursor'])
        self.assertEqual(['may-later', 'may-b', 'may-a', 'april'], self.ids(first) + self.ids(second))
        self.assertIsNone(second['next_cursor'])
        self.assertEqual(4, first['total'])
        self.assertNotIn('may-video', self.ids(first) + self.ids(second))

    def test_growth_selections_shared_persistent_and_permission_checked(self):
        result = self.request('PUT', 'growth/6', headers=self.contributor, body={'media_id': 'may-a'})
        self.assertEqual('may-a', result['selected']['id'])
        self.assertTrue(result['customized'])
        reader = self.request('GET', 'growth', headers=self.viewer)
        self.assertFalse(reader['can_edit'])
        self.assertEqual('may-a', reader['years'][-1]['selected']['id'])
        self.request('PUT', 'growth/6', headers=self.viewer, expected=403, body={'media_id': 'may-b'})
        with self.app.database() as connection:
            self.assertEqual('may-a', connection.execute('SELECT media_id FROM child_growth_selections WHERE year=6').fetchone()[0])

    def test_growth_rejects_video_wrong_year_and_invalid_year(self):
        for identity in ('may-video', 'birth', 'missing'):
            self.request('PUT', 'growth/6', expected=422, body={'media_id': identity})
        self.request('GET', 'growth/-1/photos', expected=422)
        self.request('GET', 'growth/400/photos', expected=422)
        self.request('GET', 'growth/6/photos', expected=422, cursor='not-a-position')

    def test_growth_stale_selection_falls_back_without_mutation(self):
        self.request('PUT', 'growth/6', body={'media_id': 'may-a'})
        with self.app.database() as connection:
            connection.execute("UPDATE media_children SET deleted_at=? WHERE media_id='may-a'", (self.app.now_text(),))
        selected = self.request('GET', 'growth')['years'][-1]
        self.assertEqual('may-later', selected['selected']['id'])
        self.assertFalse(selected['customized'])
        with self.app.database() as connection:
            self.assertEqual('may-a', connection.execute('SELECT media_id FROM child_growth_selections WHERE year=6').fetchone()[0])
        self.request('PUT', 'growth/6', body={'media_id': 'may-b'})
        with self.app.database() as connection:
            connection.execute("UPDATE media SET album_date='2020-03-01' WHERE id='may-b'")
        self.assertEqual('may-later', self.request('GET', 'growth')['years'][-1]['selected']['id'])

    def test_growth_regroups_after_birthday_change(self):
        self.request('PUT', 'growth/6', body={'media_id': 'may-later'})
        with self.app.database() as connection:
            connection.execute("UPDATE children SET birthday='2019-02-28'")
        years = self.request('GET', 'growth')['years']
        self.assertEqual(7, years[-1]['year'])
        self.assertFalse(years[-1]['customized'])
        self.assertNotIn('may-later', self.ids(self.request('GET', 'growth/6/photos')))

    def test_story_draft_is_bounded_read_only_and_uses_real_descriptions(self):
        self.seed([(f'june-{day}-{kind}', f'2026-06-{day:02d}T{(8 if kind == "photo" else 9):02d}:00:00+08:00', kind, '')
                   for day in range(1, 31) for kind in ('photo', 'video')])
        result = self.request('GET', 'storybooks/2026-05')
        self.assertFalse(result['saved'])
        self.assertEqual(0, result['revision'])
        self.assertEqual(['may-a', 'may-video', 'may-later'], [page['media']['id'] for page in result['pages']])
        self.assertEqual(['全家一起散步', '今天学会的新歌', ''], [page['text'] for page in result['pages']])
        june = self.request('GET', 'storybooks/2026-06')
        self.assertEqual(24, len(june['pages']))
        self.assertEqual('2026-06-01', june['pages'][0]['media']['album_date'])
        self.assertEqual('2026-06-30', june['pages'][-1]['media']['album_date'])
        self.assertEqual(24, len({page['media']['id'] for page in june['pages']}))
        with self.app.database() as connection:
            self.assertEqual(0, connection.execute('SELECT COUNT(*) FROM child_storybooks').fetchone()[0])
            self.assertEqual(0, connection.execute('SELECT COUNT(*) FROM storybook_pages').fetchone()[0])

    def test_story_catalog_sorted_and_covers_match_pages(self):
        result = self.request('GET', 'storybooks')
        months = [book['month'] for book in result['months']]
        self.assertEqual(sorted(months, reverse=True), months)
        may = next(book for book in result['months'] if book['month'] == '2026-05')
        self.assertEqual((3, 1, 4, 3), (may['photos'], may['videos'], may['total'], may['page_count']))
        self.assertEqual('may-a', may['cover']['id'])
        self.assertFalse(may['saved'])

    def test_story_save_order_cover_and_text_are_separate_from_media(self):
        body = self.book_body(ids=('may-later', 'may-a', 'may-video'))
        body['cover_media_id'] = 'may-a'
        saved = self.request('PUT', 'storybooks/2026-05', body=body, headers=self.contributor)
        self.assertEqual(1, saved['revision'])
        self.assertTrue(saved['saved'])
        self.assertEqual('may-a', saved['cover']['id'])
        self.assertEqual(['may-later', 'may-a', 'may-video'], [page['media']['id'] for page in saved['pages']])
        self.assertEqual('测试上传家人', saved['pages'][0]['author_name'])
        self.assertEqual(saved, self.request('GET', 'storybooks/2026-05', headers=self.contributor))
        with self.app.database() as connection:
            self.assertEqual('全家一起散步', connection.execute("SELECT description FROM media WHERE id='may-a'").fetchone()[0])
            self.assertEqual('', connection.execute("SELECT description FROM media WHERE id='may-later'").fetchone()[0])

    def test_story_page_author_retained_until_text_changes(self):
        body = self.book_body(ids=('may-a', 'may-b'))
        body['pages'][0]['text'] = '全家一起散步'
        saved = self.request('PUT', 'storybooks/2026-05', body=body, headers=self.contributor)
        self.assertEqual('', saved['pages'][0]['author_name'])
        body['revision'] = saved['revision']
        body['title'] = '只改封面标题'
        saved = self.request('PUT', 'storybooks/2026-05', body=body, headers=self.contributor)
        self.assertEqual('', saved['pages'][0]['author_name'])
        body['revision'] = saved['revision']
        body['pages'][0]['text'] = '家人补充的一句话'
        saved = self.request('PUT', 'storybooks/2026-05', body=body, headers=self.contributor)
        self.assertEqual('测试上传家人', saved['pages'][0]['author_name'])

    def test_story_revision_conflict_leaves_existing_book_intact(self):
        original = self.book_body()
        saved = self.request('PUT', 'storybooks/2026-05', body=original)
        stale = self.book_body()
        stale['title'] = '不能覆盖'
        self.request('PUT', 'storybooks/2026-05', body=stale, expected=409)
        self.assertEqual(saved, self.request('GET', 'storybooks/2026-05'))
        fresh = self.book_body(revision=1, ids=('may-video',))
        updated = self.request('PUT', 'storybooks/2026-05', body=fresh)
        self.assertEqual(2, updated['revision'])
        self.assertEqual(['may-video'], [page['media']['id'] for page in updated['pages']])

    def test_story_viewer_reads_but_cannot_save(self):
        draft = self.request('GET', 'storybooks/2026-05', headers=self.viewer)
        self.assertFalse(draft['can_edit'])
        self.assertFalse(self.request('GET', 'storybooks', headers=self.viewer)['can_edit'])
        self.request('PUT', 'storybooks/2026-05', body=self.book_body(), headers=self.viewer, expected=403)
        self.request('GET', 'storybooks', headers={}, expected=401)
        self.request('GET', 'growth', headers={}, expected=401)

    def test_story_concurrent_edit_allows_only_one_matching_revision(self):
        barrier = threading.Barrier(2)

        def save(title):
            body = self.book_body()
            body['title'] = title
            barrier.wait(timeout=5)
            response = self.client.put('/api/storybooks/2026-05', headers=self.admin, json=body)
            return response.status_code, title

        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(save, ('家人的第一份修改', '家人的第二份修改')))
        self.assertEqual([200, 409], sorted(status for status, _ in results))
        winner = next(title for status, title in results if status == 200)
        stored = self.request('GET', 'storybooks/2026-05')
        self.assertEqual(winner, stored['title'])
        self.assertEqual(1, stored['revision'])

    def test_story_validation_is_atomic(self):
        saved = self.request('PUT', 'storybooks/2026-05', body=self.book_body())
        for ids in [('may-a', 'may-a'), ('may-a', 'april'), ('may-a', 'missing')]:
            self.request('PUT', 'storybooks/2026-05', body=self.book_body(1, ids), expected=422)
        body = self.book_body(1)
        body['cover_media_id'] = 'may-b'
        self.request('PUT', 'storybooks/2026-05', body=body, expected=422)
        body = self.book_body(1)
        body['title'] = '   '
        self.request('PUT', 'storybooks/2026-05', body=body, expected=422)
        body['title'] = 'x' * 121
        self.request('PUT', 'storybooks/2026-05', body=body, expected=422)
        body = self.book_body(1)
        body['pages'][0]['text'] = 'x' * 5001
        self.request('PUT', 'storybooks/2026-05', body=body, expected=422)
        body = self.book_body(1)
        body['pages'] = []
        self.request('PUT', 'storybooks/2026-05', body=body, expected=422)
        body['pages'] = [{'media_id': 'may-a', 'text': ''}] * 81
        self.request('PUT', 'storybooks/2026-05', body=body, expected=422)
        self.assertEqual(saved, self.request('GET', 'storybooks/2026-05'))

    def test_story_handles_deleted_and_moved_media_and_empty_saved_book(self):
        body = self.book_body()
        self.request('PUT', 'storybooks/2026-05', body=body)
        with self.app.database() as connection:
            connection.execute("UPDATE media_children SET deleted_at=? WHERE media_id='may-a'", (self.app.now_text(),))
            connection.execute("UPDATE media SET album_date='2026-04-30' WHERE id='may-video'")
        current = self.request('GET', 'storybooks/2026-05')
        self.assertEqual(['may-later'], [page['media']['id'] for page in current['pages']])
        self.assertEqual('may-later', current['cover']['id'])
        self.assertEqual(1, current['revision'])
        body['revision'] = 1
        self.request('PUT', 'storybooks/2026-05', body=body, expected=422)
        with self.app.database() as connection:
            connection.execute("UPDATE media_children SET deleted_at=? WHERE media_id='may-later'", (self.app.now_text(),))
        empty = self.request('GET', 'storybooks/2026-05')
        self.assertTrue(empty['saved'])
        self.assertEqual([], empty['pages'])
        self.assertIsNone(empty['cover'])
        self.assertIn('2026-05', [book['month'] for book in self.request('GET', 'storybooks')['months']])

    def test_story_invalid_months_and_empty_month(self):
        for value in ('2026-13', '2026-2', 'invalid', '1800-01', '2026-01-01'):
            self.request('GET', 'storybooks/' + value, expected=422)
        empty = self.request('GET', 'storybooks/2025-01')
        self.assertEqual([], empty['pages'])
        self.assertIsNone(empty['cover'])
        self.assertEqual(0, empty['total'])

    def test_initialization_is_idempotent_and_keeps_feature_data(self):
        self.request('PUT', 'growth/6', body={'media_id': 'may-a'})
        saved = self.request('PUT', 'storybooks/2026-05', body=self.book_body())
        self.app.initialize_database()
        self.app.initialize_database()
        self.assertEqual(saved, self.request('GET', 'storybooks/2026-05'))
        self.assertEqual('may-a', self.request('GET', 'growth')['years'][-1]['selected']['id'])


if __name__ == '__main__':
    unittest.main()
