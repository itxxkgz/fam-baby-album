"""Grouped activity and quick replies against an isolated synthetic database."""
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient


class ActivityFeedTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.folder = tempfile.TemporaryDirectory(prefix='album-activity-feed-')
        cls.addClassCleanup(cls.folder.cleanup)
        folder = Path(cls.folder.name)
        server = Path(__file__).resolve().parents[1]
        sys.path.insert(0, str(server))
        name = '_synthetic_activity_feed_app'
        spec = importlib.util.spec_from_file_location(name, server / 'app.py')
        cls.app = importlib.util.module_from_spec(spec)
        sys.modules[name] = cls.app
        cls.addClassCleanup(sys.modules.pop, name, None)
        environment = {'ALBUM_DATA': str(folder / 'data'), 'ALBUM_LIBRARY': str(folder / 'library'),
                       'ALBUM_PASSWORD': '', 'ALBUM_BOOTSTRAP_TOKEN': 'synthetic-feed-setup',
                       'ALBUM_STORAGE_ROOTS': '', 'ALBUM_STORAGE_ALLOWED_ENDPOINTS': '', 'ALBUM_STORAGE_KEY': ''}
        with patch.dict(os.environ, environment):
            spec.loader.exec_module(cls.app)
        cls.client = TestClient(cls.app.app)
        cls.addClassCleanup(cls.client.close)
        result = cls.client.post('/api/setup', json={'setup_code': environment['ALBUM_BOOTSTRAP_TOKEN'],
            'username': 'feedowner', 'password': 'synthetic-owner-password', 'name': '测试家人',
            'birthday': '2020-02-29'})
        if result.status_code != 200:
            raise AssertionError('测试相册创建失败。')
        cls.admin = {'Authorization': 'Bearer ' + result.json()['token']}
        cls.admin_id = result.json()['member']['id']
        for role in ('contributor', 'viewer'):
            invitation = cls.client.post('/api/invitations', headers=cls.admin, json={'role': role}).json()
            accepted = cls.client.post('/api/invitations/accept', json={'code': invitation['code'],
                'username': 'feed' + role, 'password': 'synthetic-family-password',
                'name': '测试家人' if role == 'contributor' else '测试外婆'})
            if accepted.status_code != 200:
                raise AssertionError('测试家人创建失败。')
            setattr(cls, role, {'Authorization': 'Bearer ' + accepted.json()['token']})
            setattr(cls, role + '_id', accepted.json()['member']['id'])

    def setUp(self):
        self.app.LOGIN_ATTEMPTS.clear()
        with self.app.database() as connection:
            for table in ('comment_submissions', 'comment_replies', 'activity_feed_entries',
                          'comments', 'activity', 'favorites', 'media'):
                connection.execute('DELETE FROM ' + table)

    def request(self, method, path, expected=200, body=None, headers=None, **params):
        response = self.client.request(method, '/api/' + path,
            headers=self.admin if headers is None else headers, json=body, params=params)
        self.assertEqual(expected, response.status_code, response.text)
        return response.json()

    def seed(self, identity, created='2026-05-17T10:00:00+08:00', kind='photo', owner=None, event=True):
        owner = self.admin_id if owner is None else owner
        stamp = self.app.parse_time(created)
        with self.app.database() as connection:
            connection.execute("""INSERT INTO media
                (id,kind,root_kind,relative_path,filename,mime_type,size_bytes,sha256,width,height,duration,
                 captured_at,album_date,sort_time,uploader_id,uploader_name,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", (
                identity, kind, 'uploads', 'synthetic/' + identity, '测试照片.jpg' if kind == 'photo' else '测试视频.mp4',
                'image/jpeg' if kind == 'photo' else 'video/mp4', 100, '0' * 64, 800, 600,
                15 if kind == 'video' else None, stamp.isoformat(), stamp.date().isoformat(), stamp.timestamp(),
                owner or None, '测试家人', created, created))
            child = connection.execute("SELECT value FROM settings WHERE key='default_child_id'").fetchone()[0]
            connection.execute("INSERT INTO media_children VALUES (?,?,NULL,?,?)", (identity, child, owner or None, created))
            if event:
                connection.execute('INSERT INTO activity(id,media_id,type,member_name,text,created_at,child_id) VALUES (?,?,?,?,?,?,?)',
                    ('event-' + identity, identity, 'upload', '测试家人', '旧的上传说明', created, child))

    def add(self, media='a', text='一起记录今天', key=None, parent=None, headers=None, expected=200):
        body = {'text': text}
        if key is not None:
            body['request_id'] = key
        if parent is not None:
            body['reply_to_id'] = parent
        return self.request('POST', 'media/' + media + '/comments', body=body,
                            headers=headers, expected=expected)

    def rows(self, path='activity-feed', **params):
        return self.request('GET', path, **params)['items']

    @staticmethod
    def media_ids(result):
        return [row['id'] for row in result['items']]

    def test_empty_feed_and_original_rows_not_rewritten(self):
        self.assertEqual([], self.rows())
        self.seed('a')
        with self.app.database() as connection:
            before = {name: connection.execute('SELECT * FROM ' + name).fetchall()
                      for name in ('media', 'activity', 'comments')}
        self.rows()
        self.rows()
        with self.app.database() as connection:
            for name, rows in before.items():
                self.assertEqual([tuple(row) for row in rows],
                                 [tuple(row) for row in connection.execute('SELECT * FROM ' + name)])
            self.assertEqual(1, connection.execute('SELECT COUNT(*) FROM activity_feed_entries').fetchone()[0])

    def test_group_mixed_media_with_preview_and_real_actor(self):
        for index in range(6):
            self.seed(str(index), f'2026-05-17T10:{index:02d}:00+08:00', 'video' if index == 5 else 'photo')
        group, = self.rows()
        self.assertEqual((6, 5, 1), (group['media_count'], group['photos'], group['videos']))
        self.assertEqual('分享了5张照片和1段视频', group['text'])
        self.assertEqual(self.admin_id, group['actor']['id'])
        self.assertEqual(3, len(group['preview']))
        self.assertEqual('0', group['response_media']['id'])
        self.assertIn('file_url', group['preview'][0])
        self.assertTrue(group['can_respond'])

    def test_same_name_different_people_and_intervening_upload_not_merged(self):
        self.seed('a', owner=self.admin_id)
        self.seed('b', '2026-05-17T10:01:00+08:00', owner=self.contributor_id)
        self.seed('c', '2026-05-17T10:02:00+08:00', owner=self.admin_id)
        groups = self.rows()
        self.assertEqual([1, 1, 1], [row['media_count'] for row in groups])
        self.assertEqual([self.admin_id, self.contributor_id, self.admin_id], [row['actor']['id'] for row in groups])

    def test_legacy_without_known_actor_is_not_merged_by_name(self):
        self.seed('a', owner='')
        self.seed('b', '2026-05-17T10:01:00+08:00', owner='')
        rows = self.rows()
        self.assertEqual(2, len(rows))
        self.assertTrue(all(row['actor']['id'] == '' for row in rows))

    def test_same_day_and_bounded_thirty_minute_span(self):
        for identity, stamp in [('a', '2026-05-17T23:50:00+08:00'), ('b', '2026-05-18T00:01:00+08:00'),
                                ('c', '2026-05-18T10:00:00+08:00'), ('d', '2026-05-18T10:30:00+08:00'),
                                ('e', '2026-05-18T10:31:00+08:00')]:
            self.seed(identity, stamp)
        self.assertEqual([1, 2, 1, 1], [row['media_count'] for row in self.rows()])

    def test_timezone_normalized_before_grouping(self):
        self.seed('a', '2026-05-17T16:05:00Z')
        self.seed('b', '2026-05-18T00:06:00+08:00')
        group, = self.rows()
        self.assertEqual('2026-05-18', group['day'])
        self.assertEqual(2, group['media_count'])

    def test_filter_after_grouping_and_old_activity_compatibility(self):
        self.seed('a')
        self.seed('b', '2026-05-17T10:01:00+08:00')
        comment = self.add()
        self.assertEqual(2, len(self.rows()))
        self.assertEqual([2], [row['media_count'] for row in self.rows(kind='media')])
        self.assertEqual(comment['id'], self.rows(kind='comment')[0]['comment']['id'])
        self.assertEqual(3, len(self.rows('activity')))
        self.request('GET', 'activity-feed', expected=422, kind='unknown')

    def test_same_timestamp_feed_pages_do_not_drop_or_repeat(self):
        for index in range(8):
            self.seed(str(index), owner=self.admin_id if index % 2 == 0 else self.contributor_id)
        cursor, collected = None, []
        while True:
            page = self.request('GET', 'activity-feed', limit=2, **({'cursor': cursor} if cursor else {}))
            collected += [row['id'] for row in page['items']]
            cursor = page['next_cursor']
            if not cursor:
                break
        self.assertEqual(8, len(collected))
        self.assertEqual(8, len(set(collected)))

    def test_snapshot_excludes_new_backdated_events_until_refresh(self):
        for index in range(5):
            self.seed(str(index), f'2026-05-{index+1:02d}T10:00:00+08:00')
        first = self.request('GET', 'activity-feed', limit=2)
        stable = {item['event_key'] for item in first['items']}
        self.seed('new', '2026-05-01T09:00:00+08:00', owner=self.contributor_id)
        second = self.request('GET', 'activity-feed', limit=20, cursor=first['next_cursor'])
        self.assertEqual(3, len(second['items']))
        self.assertEqual(6, len(self.rows()))
        self.assertTrue(stable.issubset({item['event_key'] for item in self.rows()}))

    def test_comment_snapshot_survives_newest_row_deletion(self):
        self.seed('a')
        for index in range(3):
            self.add(text='旧留言' + str(index))
        first = self.request('GET', 'activity-feed', kind='comment', limit=1)
        deleted = first['items'][0]['comment']['id']
        self.request('DELETE', 'media/a/comments/' + deleted)
        new = self.add(text='新留言')
        later = self.request('GET', 'activity-feed', kind='comment', cursor=first['next_cursor'])
        self.assertNotIn(new['id'], [row['comment']['id'] for row in later['items']])

    def test_group_media_paging_large_batch_and_snapshot_scope(self):
        for index in range(72):
            self.seed(f'{index:03d}')
        group, = self.rows()
        first = self.request('GET', 'activity-feed/' + group['id'] + '/media', limit=60)
        self.seed('new-photo', '2026-05-17T10:01:00+08:00')
        second = self.request('GET', 'activity-feed/' + group['id'] + '/media', limit=60, cursor=first['next_cursor'])
        ids = self.media_ids(first) + self.media_ids(second)
        self.assertEqual(72, len(ids))
        self.assertEqual(72, len(set(ids)))
        self.assertNotIn('new-photo', ids)
        self.assertEqual(73, self.rows()[0]['media_count'])

    def test_group_page_continues_after_last_item_moved_to_trash(self):
        for identity in ('a', 'b', 'c'):
            self.seed(identity)
        group, = self.rows()
        path = 'activity-feed/' + group['id'] + '/media'
        first = self.request('GET', path, limit=1)
        self.request('DELETE', 'media/' + first['items'][0]['id'])
        second = self.request('GET', path, limit=1, cursor=first['next_cursor'])
        self.assertEqual(['b'], self.media_ids(second))
        self.assertEqual(2, second['total'])

    def test_deleted_media_never_leaks_through_feed_even_to_admin(self):
        self.seed('a')
        comment = self.add()
        groups = self.rows()
        self.request('DELETE', 'media/a')
        self.assertEqual([], self.rows())
        for group in groups:
            self.request('GET', 'activity-feed/' + group['id'] + '/media', expected=404)
        self.add(parent=comment['id'], expected=409)

    def test_invalid_cursor_and_cross_filter_or_group_cursor_rejected(self):
        self.seed('a')
        self.seed('b')
        self.seed('c', '2026-05-16T10:00:00+08:00')
        self.add()
        first = self.request('GET', 'activity-feed', limit=1)
        self.request('GET', 'activity-feed', kind='media', cursor=first['next_cursor'], expected=422)
        for value in ('nonsense', 'e30', 'a' * 3000):
            self.request('GET', 'activity-feed', cursor=value, expected=422)
        groups = self.rows(kind='media')
        first = self.request('GET', 'activity-feed/' + groups[0]['id'] + '/media', limit=1)
        self.request('GET', 'activity-feed/' + groups[1]['id'] + '/media', cursor=first['next_cursor'], expected=422)

    def test_orphan_old_comment_activity_is_not_shown(self):
        self.seed('a')
        with self.app.database() as connection:
            connection.execute('INSERT INTO activity(id,media_id,type,member_name,text,created_at,child_id) VALUES (?,?,?,?,?,?,?)',
                ('orphan', 'a', 'comment', '已经删除的家人', '不应出现的旧文字', self.app.now_text(), connection.execute("SELECT value FROM settings WHERE key='default_child_id'").fetchone()[0]))
        self.assertEqual([], self.rows(kind='comment'))

    def test_reply_is_bound_to_same_media_and_contains_real_parent(self):
        self.seed('a')
        self.seed('b')
        parent = self.add(text='妈妈写下的话', headers=self.contributor)
        reply = self.add(text='我们都看到了', parent=parent['id'])
        self.assertEqual({'id': parent['id'], 'name': '测试家人', 'text': '妈妈写下的话'}, reply['reply_to'])
        self.add(media='b', parent=parent['id'], expected=409)
        self.add(parent='missing-comment', expected=409)
        feed = self.rows(kind='comment')
        event = next(item for item in feed if item['comment']['id'] == reply['id'])
        self.assertEqual(parent['id'], event['comment']['reply_to']['id'])

    def test_deleted_parent_is_not_quoted_or_resurrected(self):
        self.seed('a')
        parent = self.add(text='之后删除的文字')
        reply = self.add(text='保留下来的回应', parent=parent['id'], key='reply-request-1')
        self.request('DELETE', 'media/a/comments/' + parent['id'])
        feed = self.rows(kind='comment')
        self.assertEqual(1, len(feed))
        self.assertIsNone(feed[0]['comment']['reply_to'])
        retried = self.add(text='保留下来的回应', parent=parent['id'], key='reply-request-1')
        self.assertEqual(reply['id'], retried['id'])
        self.assertIsNone(retried['reply_to'])
        self.add(parent=parent['id'], key='different-request', expected=409)

    def test_viewer_can_read_but_cannot_respond(self):
        self.seed('a')
        parent = self.add()
        feed = self.request('GET', 'activity-feed', headers=self.viewer)
        self.assertFalse(feed['can_respond'])
        self.assertTrue(all(not item['can_respond'] for item in feed['items']))
        self.add(headers=self.viewer, expected=403)
        self.add(headers=self.viewer, parent=parent['id'], expected=403)

    def test_repeat_request_does_not_create_duplicate_and_changed_text_conflicts(self):
        self.seed('a')
        first = self.add(text='  小小的快乐  ', key='same-request-001')
        second = self.add(text='小小的快乐', key='same-request-001')
        self.assertEqual(first['id'], second['id'])
        self.assertEqual(1, len(self.rows('media/a/comments')))
        self.add(text='改了内容', key='same-request-001', expected=409)
        self.add(text='小小的快乐', key='same-request-001', headers=self.contributor)
        self.assertEqual(2, len(self.rows('media/a/comments')))

    def test_concurrent_retries_produce_one_comment(self):
        self.seed('a')
        def send(_):
            return self.add(key='concurrent-request-1')['id']
        with ThreadPoolExecutor(max_workers=4) as pool:
            ids = list(pool.map(send, range(4)))
        self.assertEqual(1, len(set(ids)))
        self.assertEqual(1, len(self.rows('media/a/comments')))

    def test_deleted_sent_comment_does_not_reappear_on_retry(self):
        self.seed('a')
        comment = self.add(key='removed-request-1')
        self.request('DELETE', 'media/a/comments/' + comment['id'])
        self.add(key='removed-request-1', expected=409)
        self.assertEqual([], self.rows(kind='comment'))

    def test_same_name_identical_comments_delete_only_exact_new_event(self):
        self.seed('a')
        with patch.object(self.app, 'now_text', return_value='2026-05-17T12:00:00+08:00'):
            mine = self.add(text='同一句话')
            theirs = self.add(text='同一句话', headers=self.contributor)
        self.request('DELETE', 'media/a/comments/' + mine['id'])
        self.assertEqual([theirs['id']], [row['comment']['id'] for row in self.rows(kind='comment')])
        legacy_comments = [row for row in self.rows('activity') if row['type'] == 'comment']
        self.assertEqual(1, len(legacy_comments))

    def test_empty_long_and_malformed_requests_use_natural_errors(self):
        self.seed('a')
        self.add(text='   ', expected=422)
        self.add(text='字' * 2001, expected=422)
        self.add(key='bad request key', expected=422)
        self.request('GET', 'activity-feed', headers={}, expected=401)


if __name__ == '__main__':
    unittest.main()
