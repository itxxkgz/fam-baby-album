"""Simple family invitations and preserved import history in isolated albums."""
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import uuid

from fastapi.testclient import TestClient
from PIL import Image


class FamilyMemberTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='fam-family-members-')
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        server = Path(__file__).resolve().parents[1]
        sys.path.insert(0, str(server))
        name = '_family_members_' + uuid.uuid4().hex
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
        login = self.call('POST', '/api/login', headers={}, json={'username': 'admin', 'password': 'admin'})
        self.admin = {'Authorization': 'Bearer ' + login['token']}
        changed = self.call('POST', '/api/me/password', json={
            'current_password': 'admin', 'new_password': 'family-private-password',
        })
        self.admin = {'Authorization': 'Bearer ' + changed['token']}
        self.owner = changed['member']['id']
        self.first = self.call('GET', '/api/children')['items'][0]['id']

    def call(self, method, path, *, headers=None, expected=200, **kwargs):
        response = self.client.request(method, path, headers=self.admin if headers is None else headers, **kwargs)
        self.assertEqual(response.status_code, expected, response.text)
        return response.json()

    def scoped(self, child, headers=None):
        return dict(self.admin if headers is None else headers, **{'X-FAM-Child': child})

    def second_child(self):
        return self.call('POST', '/api/children', json={'name': '测试妹妹'})['id']

    def invite(self, name='测试妈妈', role='viewer', child=None):
        invitation = self.call('POST', '/api/invitations', json={
            'name': name, 'role': role, 'child_ids': [child or self.first],
        })
        return self.accept(invitation)

    def accept(self, invitation, name='接收方填写的称呼'):
        accepted = self.call('POST', '/api/invitations/accept', headers={}, json={
            'code': invitation['code'], 'username': 'relative-' + uuid.uuid4().hex[:10],
            'password': 'relative-private-password', 'name': name,
        })
        return accepted, {'Authorization': 'Bearer ' + accepted['token']}

    def legacy(self, name='原上传家人', child=None, identity=None, username=None, password_hash=None):
        identity = identity or uuid.uuid4().hex
        with self.app.database() as db:
            db.execute('''INSERT INTO members(id,name,role,joined_at,username,password_hash)
                VALUES (?,?, '家人',?,?,?)''', (identity, name, self.app.now_text(), username, password_hash))
            db.execute('INSERT INTO child_members VALUES (?,?,?)', (child or self.first, identity, 'contributor'))
        return identity

    def member_count(self):
        with self.app.database() as db:
            return db.execute('SELECT COUNT(*) FROM members').fetchone()[0]

    def test_fresh_album_has_only_real_administrator(self):
        self.assertEqual(self.member_count(), 1)
        for path in ('/api/members', '/api/members?include_legacy=true', f'/api/children/{self.first}/members'):
            result = self.call('GET', path)['items']
            self.assertEqual([item['id'] for item in result], [self.owner])
            self.assertTrue(result[0]['registered'])
        profile = self.call('GET', '/api/profile')
        self.assertEqual(profile['members_count'], 1)
        self.assertEqual(profile['counts']['members'], 1)

    def test_name_only_invite_is_current_baby_viewer_and_adds_member_only_on_accept(self):
        second = self.second_child()
        before = time.time()
        invitation = self.call('POST', '/api/invitations', headers=self.scoped(second), json={'name': '  小姨  '})
        self.assertEqual(invitation['name'], '小姨')
        self.assertEqual(invitation['role'], 'viewer')
        self.assertEqual(invitation['child_ids'], [second])
        self.assertFalse(invitation['is_family_admin'])
        self.assertEqual(self.member_count(), 1)
        self.assertEqual(len(self.call('GET', '/api/members')['items']), 1)
        with self.app.database() as db:
            expiry = db.execute('SELECT expires_at FROM invitations WHERE id=?', (invitation['id'],)).fetchone()[0]
        self.assertGreaterEqual(expiry, before + 24 * 3600)
        self.assertLessEqual(expiry, time.time() + 24 * 3600)
        accepted, family = self.accept(invitation)
        self.assertEqual(accepted['member']['name'], '小姨')
        self.assertEqual(accepted['member']['role'], 'viewer')
        self.assertFalse(accepted['member']['is_family_admin'])
        self.assertFalse(accepted['member']['permissions']['can_upload'])
        self.assertEqual([c['id'] for c in self.call('GET', '/api/children', headers=family)['items']], [second])
        self.call('GET', '/api/media', headers=self.scoped(self.first, family), expected=403)
        self.assertEqual(self.member_count(), 2)
        self.assertEqual(self.call('GET', '/api/profile', headers=self.scoped(second))['members_count'], 2)
        self.assertEqual(self.call('GET', '/api/profile', headers=self.scoped(self.first))['members_count'], 1)

    def test_explicit_blank_name_is_rejected_and_omitted_legacy_name_remains_compatible(self):
        for name in ('', ' \n\t ', '称' * 41):
            self.call('POST', '/api/invitations', json={'name': name}, expected=422)
        self.assertEqual(self.call('GET', '/api/invitations')['items'], [])
        invitation = self.call('POST', '/api/invitations', json={'role': 'viewer'})
        accepted, _ = self.accept(invitation, '原版客户端填写的称呼')
        self.assertEqual(accepted['member']['name'], '原版客户端填写的称呼')

    def test_legacy_history_is_hidden_without_altering_uploads_comments_or_accounts(self):
        old = self.legacy()
        self.legacy('只有用户名的旧记录', username='unfinished')
        self.legacy('只有空白凭据的旧记录', username='', password_hash='')
        accepted, _ = self.invite('暂停使用的家人')
        disabled = accepted['member']['id']
        self.call('PATCH', '/api/members/' + disabled, json={'disabled': True})
        picture = io.BytesIO()
        Image.new('RGB', (16, 12), 'orange').save(picture, 'JPEG')
        uploaded = self.call('POST', '/api/upload', files={'file': ('旧照片.jpg', picture.getvalue(), 'image/jpeg')},
                             data={'captured_at': '2021-06-02T10:11:12+08:00'})
        with self.app.database() as db:
            db.execute('UPDATE media SET uploader_id=?,uploader_name=? WHERE id=?', (old, '原上传家人', uploaded['id']))
            db.execute('''INSERT INTO comments(id,media_id,member_id,name,text,created_at,child_id)
                VALUES (?,?,?,?,?,?,?)''', ('historical-comment', uploaded['id'], old, '原上传家人',
                                          '保留原来的留言', self.app.now_text(), self.first))
            tables = ('members', 'sessions', 'media', 'media_children', 'child_members', 'comments')
            before = {table: [tuple(row) for row in db.execute('SELECT * FROM ' + table + ' ORDER BY rowid')] for table in tables}
        for path in ('/api/members', f'/api/children/{self.first}/members'):
            items = self.call('GET', path)['items']
            self.assertEqual({r['id'] for r in items}, {self.owner, disabled})
            self.assertTrue(next(r for r in items if r['id'] == disabled)['disabled'])
        self.assertEqual(len(self.call('GET', '/api/members?include_legacy=true')['items']), 5)
        profile = self.call('GET', '/api/profile')
        self.assertEqual((profile['members_count'], profile['counts']['members']), (2, 2))
        photo = self.call('GET', '/api/media/' + uploaded['id'])
        self.assertEqual(photo['uploader_name'], '原上传家人')
        self.assertEqual(photo['captured_at'], uploaded['captured_at'])
        comments = self.call('GET', '/api/media/' + uploaded['id'] + '/comments')
        self.assertIn('保留原来的留言', json.dumps(comments, ensure_ascii=False))
        with self.app.database() as db:
            for table in tables:
                self.assertEqual([tuple(row) for row in db.execute('SELECT * FROM ' + table + ' ORDER BY rowid')], before[table], table)
        for hidden in ('password_hash', 'password_salt', 'token_hash'):
            self.assertNotIn(hidden, json.dumps(self.call('GET', '/api/members?include_legacy=true')))

    def test_only_managers_can_load_legacy_history_and_child_managers_are_scoped(self):
        second = self.second_child()
        old_first = self.legacy('姐姐的原上传家人')
        old_second = self.legacy('妹妹的原上传家人', second)
        accepted, manager = self.invite('宝宝管理员', 'admin')
        _, viewer = self.invite('普通家人')
        self.call('GET', '/api/members?include_legacy=true', headers=viewer, expected=403)
        items = self.call('GET', '/api/members?include_legacy=true', headers=self.scoped(self.first, manager))['items']
        self.assertIn(old_first, [r['id'] for r in items])
        self.assertNotIn(old_second, [r['id'] for r in items])
        self.call('GET', '/api/members?include_legacy=true', headers=self.scoped(second, manager), expected=403)
        self.call('PUT', f"/api/children/{self.first}/members/{accepted['member']['id']}", json={'role': None})
        self.call('GET', '/api/members?include_legacy=true', headers=self.scoped(self.first, manager), expected=403)
        self.call('POST', '/api/invitations', headers=self.scoped(self.first, manager), json={'name': '不能越权'}, expected=403)

    def test_child_manager_can_add_viewer_by_name_without_gaining_family_access(self):
        second = self.second_child()
        _, manager = self.invite('妹妹管理员', 'admin', second)
        invitation = self.call('POST', '/api/invitations', headers=self.scoped(second, manager), json={'name': '外婆'})
        self.assertEqual(invitation['child_ids'], [second])
        self.assertFalse(invitation['is_family_admin'])
        accepted, _ = self.accept(invitation)
        self.assertEqual(accepted['member']['role'], 'viewer')
        self.call('POST', '/api/invitations', headers=self.scoped(second, manager),
                  json={'name': '无权邀请的家人', 'child_ids': [self.first]}, expected=403)
        self.call('POST', '/api/invitations', headers=self.scoped(second, manager),
                  json={'name': '不能提升家庭身份', 'role': 'admin'}, expected=403)

    def test_revoked_selected_baby_never_silently_falls_back_to_another_baby(self):
        second = self.second_child()
        accepted, manager = self.invite('双宝宝管理员', 'admin')
        self.call('PUT', f"/api/children/{second}/members/{accepted['member']['id']}", json={'role': 'admin'})
        self.call('PUT', f"/api/children/{second}/members/{accepted['member']['id']}", json={'role': None})
        with self.app.database() as db:
            count = db.execute('SELECT COUNT(*) FROM invitations').fetchone()[0]
        self.call('POST', '/api/invitations', headers=self.scoped(second, manager), json={'name': '不会加错相册'}, expected=403)
        with self.app.database() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM invitations').fetchone()[0], count)
        valid = self.call('POST', '/api/invitations', headers=self.scoped(self.first, manager), json={'name': '当前宝宝家人'})
        self.assertEqual(valid['child_ids'], [self.first])

    def test_explicit_historical_claim_is_optional_and_preserves_original_identity(self):
        old = self.legacy('原来的妈妈')
        ordinary = self.call('POST', '/api/invitations', json={'name': '原来的妈妈'})
        accepted, _ = self.accept(ordinary)
        self.assertNotEqual(accepted['member']['id'], old)
        self.assertIn(old, [r['id'] for r in self.call('GET', '/api/members?include_legacy=true')['items'] if not r['registered']])
        linked = self.call('POST', '/api/invitations', json={'member_id': old, 'role': 'viewer', 'child_ids': [self.first]})
        self.assertEqual(linked['name'], '原来的妈妈')
        count = self.member_count()
        claimed, _ = self.accept(linked)
        self.assertEqual(claimed['member']['id'], old)
        self.assertEqual(claimed['member']['name'], '原来的妈妈')
        self.assertEqual(self.member_count(), count)
        self.assertIn(old, [r['id'] for r in self.call('GET', '/api/members')['items']])

    def test_explicit_legacy_family_admin_invitation_remains_compatible(self):
        invitation = self.call('POST', '/api/invitations', json={'role': 'admin', 'name': '另一位管理员'})
        self.assertTrue(invitation['is_family_admin'])
        accepted, _ = self.accept(invitation)
        self.assertTrue(accepted['member']['is_family_admin'])

    def test_relationship_is_per_baby_and_never_changes_account_or_upload_history(self):
        second = self.second_child()
        accepted, family = self.invite('原来的账号称呼', 'contributor')
        identity = accepted['member']['id']
        self.call('PUT', f'/api/children/{second}/members/{identity}', json={'role': 'viewer'})
        picture = io.BytesIO()
        Image.new('RGB', (16, 12), 'pink').save(picture, 'JPEG')
        uploaded = self.call('POST', '/api/upload', headers=family,
                             files={'file': ('家人上传.jpg', picture.getvalue(), 'image/jpeg')})
        self.call('POST', f"/api/media/{uploaded['id']}/comments", headers=family, json={'text': '原来的留言'})
        with self.app.database() as db:
            tables = ('members', 'sessions', 'media', 'comments', 'child_members')
            before = {table: [tuple(row) for row in db.execute('SELECT * FROM ' + table + ' ORDER BY rowid')] for table in tables}
        changed = self.call('PATCH', f'/api/children/{self.first}/members/{identity}', json={'relationship': '  妈妈  '})
        self.assertEqual(changed['relationship'], '妈妈')
        self.assertEqual(changed['name'], '原来的账号称呼')
        self.assertTrue(all(changed[key] for key in ('can_edit_relationship', 'can_change_role', 'can_remove')))
        self.call('PATCH', f'/api/children/{second}/members/{identity}', json={'relationship': '小姨'})
        for child, expected in ((self.first, '妈妈'), (second, '小姨')):
            members = self.call('GET', f'/api/children/{child}/members')['items']
            self.assertEqual(next(row for row in members if row['id'] == identity)['relationship'], expected)
            ordinary = self.call('GET', '/api/members', headers=self.scoped(child, family))['items']
            self.assertEqual(next(row for row in ordinary if row['id'] == identity)['relationship'], expected)
        with self.app.database() as db:
            for table in tables:
                self.assertEqual([tuple(row) for row in db.execute('SELECT * FROM ' + table + ' ORDER BY rowid')], before[table], table)
        photo = self.call('GET', '/api/media/' + uploaded['id'])
        self.assertEqual(photo['uploader_name'], '原来的账号称呼')
        self.assertEqual(photo['comments'][0]['name'], '原来的账号称呼')

    def test_relationship_flags_protect_roles_but_allow_manager_and_self_relationships(self):
        accepted, manager = self.invite('宝宝管理员', 'admin')
        identity = accepted['member']['id']
        listed = self.call('GET', f'/api/children/{self.first}/members', headers=manager)['items']
        for target in (self.owner, identity):
            item = next(row for row in listed if row['id'] == target)
            self.assertTrue(item['can_edit_relationship'])
            self.assertFalse(item['can_change_role'])
            self.assertFalse(item['can_remove'])
            changed = self.call('PATCH', f'/api/children/{self.first}/members/{target}', headers=manager,
                                json={'relationship': '爸爸' if target == self.owner else '妈妈'})
            self.assertTrue(changed['can_edit_relationship'])
            self.call('PUT', f'/api/children/{self.first}/members/{target}', headers=manager,
                      json={'role': 'viewer'}, expected=409)
            self.call('PUT', f'/api/children/{self.first}/members/{target}', headers=manager,
                      json={'role': None}, expected=409)
        self.assertFalse(self.call('GET', '/api/me', headers=manager)['is_family_admin'])

    def test_relationship_validation_archived_scope_and_unjoined_targets(self):
        second = self.second_child()
        accepted, family = self.invite('普通家人')
        identity = accepted['member']['id']
        path = f'/api/children/{self.first}/members/{identity}'
        for value in ('', ' \n\t ', '称' * 41):
            self.call('PATCH', path, json={'relationship': value}, expected=422)
        self.call('PATCH', path, headers=family, json={'relationship': '越权修改'}, expected=403)
        self.call('PATCH', f'/api/children/{second}/members/{identity}', json={'relationship': '未加入'}, expected=404)
        historical = self.legacy()
        self.call('PATCH', f'/api/children/{self.first}/members/{historical}', json={'relationship': '历史记录'}, expected=404)
        self.call('PATCH', f'/api/children/{self.first}', json={'archived': True})
        self.call('PATCH', path, json={'relationship': '归档后修改'}, expected=409)
        items = self.call('GET', f'/api/children/{self.first}/members')['items']
        for item in items:
            self.assertFalse(any(item[key] for key in ('can_edit_relationship', 'can_change_role', 'can_remove')))

    def test_role_switches_keep_relationship_and_removal_only_removes_current_membership(self):
        second = self.second_child()
        accepted, family = self.invite('原有称呼')
        identity = accepted['member']['id']
        path = f'/api/children/{self.first}/members/{identity}'
        other = f'/api/children/{second}/members/{identity}'
        self.call('PUT', other, json={'role': 'contributor'})
        self.call('PATCH', path, json={'relationship': '妈妈'})
        self.call('PATCH', other, json={'relationship': '小姨'})
        for role in ('admin', 'contributor', 'viewer'):
            self.call('PUT', path, json={'role': role})
            current = self.call('GET', '/api/me', headers=self.scoped(self.first, family))
            self.assertEqual(current['role'], role)
            self.assertFalse(current['is_family_admin'])
            self.assertEqual(current['permissions']['can_upload'], role != 'viewer')
            members = self.call('GET', f'/api/children/{self.first}/members')['items']
            self.assertEqual(next(row for row in members if row['id'] == identity)['relationship'], '妈妈')
        with self.app.database() as db:
            account = tuple(db.execute('SELECT * FROM members WHERE id=?', (identity,)).fetchone())
            sessions = [tuple(row) for row in db.execute('SELECT * FROM sessions WHERE member_id=?', (identity,))]
        self.call('PUT', path, json={'role': None})
        self.call('GET', '/api/media', headers=self.scoped(self.first, family), expected=403)
        self.assertEqual(self.call('GET', '/api/me', headers=self.scoped(second, family))['role'], 'contributor')
        with self.app.database() as db:
            self.assertEqual(tuple(db.execute('SELECT * FROM members WHERE id=?', (identity,)).fetchone()), account)
            self.assertEqual([tuple(row) for row in db.execute('SELECT * FROM sessions WHERE member_id=?', (identity,))], sessions)
            self.assertIsNone(db.execute('SELECT * FROM child_member_relationships WHERE child_id=? AND member_id=?', (self.first, identity)).fetchone())
            self.assertEqual(db.execute('SELECT relationship FROM child_member_relationships WHERE child_id=? AND member_id=?', (second, identity)).fetchone()[0], '小姨')
        self.call('PATCH', path, json={'relationship': '不能恢复权限'}, expected=404)

    def test_existing_account_join_records_new_relationship_without_overwriting_old_one(self):
        second = self.second_child()
        accepted, family = self.invite('原账号称呼')
        identity = accepted['member']['id']
        self.call('PATCH', f'/api/children/{self.first}/members/{identity}', json={'relationship': '妈妈'})
        invitation = self.call('POST', '/api/invitations', headers=self.scoped(second), json={'name': '小姨'})
        self.call('POST', '/api/invitations/join', headers=family, json={'code': invitation['code']})
        repeated = self.call('POST', '/api/invitations', headers=self.scoped(second), json={'name': '重复邀请不能改名'})
        self.call('POST', '/api/invitations/join', headers=family, json={'code': repeated['code']})
        with self.app.database() as db:
            values = {row['child_id']: row['relationship'] for row in db.execute(
                'SELECT child_id,relationship FROM child_member_relationships WHERE member_id=?', (identity,))}
            self.assertEqual(values, {self.first: '妈妈', second: '小姨'})
            self.assertEqual(db.execute('SELECT name FROM members WHERE id=?', (identity,)).fetchone()[0], '原账号称呼')

    def test_revoked_child_manager_cannot_modify_relationship_using_existing_session(self):
        second = self.second_child()
        accepted, manager = self.invite('宝宝管理员', 'admin')
        target, _ = self.invite('普通家人')
        path = f"/api/children/{self.first}/members/{target['member']['id']}"
        self.call('PUT', f"/api/children/{second}/members/{accepted['member']['id']}", json={'role': 'admin'})
        self.call('PUT', f"/api/children/{self.first}/members/{accepted['member']['id']}", json={'role': None})
        self.call('PATCH', path, headers=self.scoped(self.first, manager), json={'relationship': '不能越权修改'}, expected=403)

    def test_relationship_table_initialization_preserves_existing_grants_sessions_and_names(self):
        accepted, _ = self.invite('已注册家人', 'contributor')
        identity = accepted['member']['id']
        self.call('PATCH', f'/api/children/{self.first}/members/{identity}', json={'relationship': '妈妈'})
        with self.app.database() as db:
            tables = ('members', 'sessions', 'child_members', 'child_member_relationships')
            before = {table: [tuple(row) for row in db.execute('SELECT * FROM ' + table + ' ORDER BY rowid')] for table in tables}
            self.app.initialize_children(db)
            self.app.initialize_children(db)
            self.assertEqual([row[1] for row in db.execute('PRAGMA table_info(child_members)')], ['child_id', 'member_id', 'role'])
            for table in tables:
                self.assertEqual([tuple(row) for row in db.execute('SELECT * FROM ' + table + ' ORDER BY rowid')], before[table], table)


if __name__ == '__main__':
    unittest.main()
