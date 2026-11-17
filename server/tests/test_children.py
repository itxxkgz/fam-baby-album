"""Synthetic multi-child migration, exact-resource isolation, and account preservation."""
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import io
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid

from fastapi.testclient import TestClient
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from children import initialize


class ChildMigrationTests(unittest.TestCase):
    def test_existing_accounts_sessions_originals_and_old_memories_survive_once(self):
        with sqlite3.connect(':memory:') as db:
            db.row_factory = sqlite3.Row
            db.executescript('''
                CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);
                CREATE TABLE members(id TEXT PRIMARY KEY,account_role TEXT,password_hash TEXT);
                CREATE TABLE sessions(token_hash TEXT PRIMARY KEY,member_id TEXT,expires_at REAL);
                CREATE TABLE media(id TEXT PRIMARY KEY,deleted_at TEXT,uploader_id TEXT,created_at TEXT,filename TEXT);
                CREATE TABLE comments(id TEXT PRIMARY KEY,media_id TEXT,text TEXT);
                CREATE TABLE activity(id TEXT PRIMARY KEY,media_id TEXT,text TEXT);
                CREATE TABLE invitations(id TEXT PRIMARY KEY,role TEXT);
                CREATE TABLE growth_selections(year INTEGER PRIMARY KEY,media_id TEXT);
                CREATE TABLE storybooks(month TEXT PRIMARY KEY,title TEXT);
            ''')
            db.executemany('INSERT INTO settings VALUES (?,?)', [('child_name','测试姐姐'),('birthday','2020-02-29'),('avatar_media_id','photo')])
            db.executemany('INSERT INTO members VALUES (?,?,?)', [('owner','admin','preserved'),('relative','viewer','also-preserved')])
            db.execute("INSERT INTO sessions VALUES ('existing-session','relative',9999999999)")
            db.executemany('INSERT INTO media VALUES (?,?,?,?,?)', [('photo',None,'owner','2024-01-01','照片.jpg'),('trash','2025-01-01','owner','2024-02-01','旧照片.jpg')])
            db.execute("INSERT INTO comments VALUES ('comment','photo','保留留言')")
            db.execute("INSERT INTO activity VALUES ('event','photo','原上传')")
            db.execute("INSERT INTO invitations VALUES ('invite','admin')")
            db.execute("INSERT INTO growth_selections VALUES (1,'photo')")
            db.execute("INSERT INTO storybooks VALUES ('2024-01','原故事')")
            before = {table: [tuple(row) for row in db.execute('SELECT * FROM ' + table)] for table in ('members','sessions','media','growth_selections','storybooks')}
            initialize(db)
            child = db.execute('SELECT * FROM children').fetchone()
            self.assertEqual((child['name'],child['birthday'],child['avatar_media_id']), ('测试姐姐','2020-02-29','photo'))
            self.assertEqual(db.execute('SELECT COUNT(*) FROM media_children').fetchone()[0], 2)
            self.assertEqual(db.execute("SELECT deleted_at FROM media_children WHERE media_id='trash'").fetchone()[0], '2025-01-01')
            self.assertEqual(db.execute('SELECT child_id FROM comments').fetchone()[0], child['id'])
            self.assertEqual(json.loads(db.execute('SELECT child_ids_json FROM invitations').fetchone()[0]), [child['id']])
            self.assertEqual(db.execute('SELECT family_admin FROM invitations').fetchone()[0], 0)
            db.execute("INSERT INTO children VALUES ('second','测试妹妹','2024-01-01',NULL,NULL,'2024','2024')")
            initialize(db)
            self.assertEqual(db.execute('SELECT COUNT(*) FROM children').fetchone()[0],2)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM child_members WHERE child_id='second'").fetchone()[0],0)
            for table, rows in before.items():
                self.assertEqual([tuple(row) for row in db.execute('SELECT * FROM ' + table)],rows,table)


class ChildrenApiTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='fam-children-')
        self.addCleanup(self.temporary.cleanup)
        directory = Path(self.temporary.name)
        self.env = {'ALBUM_DATA': str(directory/'data'), 'ALBUM_LIBRARY': str(directory/'library'),
                    'ALBUM_PASSWORD':'', 'ALBUM_BOOTSTRAP_TOKEN':'', 'ALBUM_STORAGE_KEY':'',
                    'ALBUM_STORAGE_ROOTS':'[]', 'ALBUM_IMPORT_ROOTS':'[]', 'ALBUM_STORAGE_ALLOWED_ENDPOINTS':''}
        name = '_child_test_' + uuid.uuid4().hex
        spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parents[1]/'app.py')
        self.app = importlib.util.module_from_spec(spec)
        sys.modules[name] = self.app
        self.addCleanup(sys.modules.pop,name,None)
        with patch.dict(os.environ,self.env):
            spec.loader.exec_module(self.app)
        self.client = TestClient(self.app.app)
        self.addCleanup(self.client.close)
        self.addCleanup(self.app.admin_imports.stop)
        self.addCleanup(self.app.admin_backups.stop)
        initial = self.client.post('/api/login',json={'username':'admin','password':'admin'}).json()
        first = {'Authorization':'Bearer ' + initial['token']}
        changed = self.client.post('/api/me/password',headers=first,json={'current_password':'admin','new_password':'family-password'})
        self.assertEqual(changed.status_code,200,changed.text)
        self.admin = {'Authorization':'Bearer ' + changed.json()['token']}
        self.parent_id = changed.json()['member']['id']
        self.first = self.req('GET','/api/children')['items'][0]['id']
        self.req('PATCH',f'/api/children/{self.first}',json={'name':'测试姐姐','birthday':'2020-02-29'})
        self.second = self.req('POST','/api/children',json={'name':'测试妹妹','birthday':'2024-01-01'})['id']
        picture = io.BytesIO()
        Image.new('RGB',(24,24),'orange').save(picture,'JPEG')
        self.picture = picture.getvalue()

    def req(self, method, path, headers=None, expected=200, **kw):
        response = self.client.request(method,path,headers=headers if headers is not None else self.admin,**kw)
        self.assertEqual(response.status_code,expected,response.text)
        return response.json()

    def scoped(self, child, headers=None):
        return dict(self.admin if headers is None else headers, **{'X-FAM-Child':child})

    def upload(self, children=None, headers=None):
        return self.req('POST','/api/upload',headers=headers or self.scoped(self.first),
                        files={'file':('测试照片.jpg',self.picture,'image/jpeg')},
                        data={'child_ids':json.dumps(children or [self.first]),'captured_at':'2025-05-04T03:02:01+08:00'})

    def invite(self, name, role='viewer', children=None, actor=None):
        result = self.req('POST','/api/invitations',headers=actor or self.admin,
                          json={'name':name,'role':role,'child_ids':children or [self.first]})
        accepted = self.req('POST','/api/invitations/accept',headers={},
                            json={'code':result['code'],'username':name,'password':'member-password','name':name})
        return accepted['member'], {'Authorization':'Bearer '+accepted['token']}

    def test_exact_media_reads_and_writes_cannot_cross_child_scope(self):
        first = self.upload([self.first])
        other = self.upload([self.second],self.scoped(self.second))
        _, viewer = self.invite('oneviewer')
        visible = self.req('GET','/api/children',headers=viewer)
        self.assertEqual([c['id'] for c in visible['items']],[self.first])
        self.assertEqual(self.req('GET','/api/media',headers=viewer)['total'],1)
        self.assertEqual(self.req('GET','/api/profile',headers=viewer)['child_id'],self.first)
        for suffix in ('','/file','/thumbnail','/comments'):
            response=self.client.get('/api/media/'+other['id']+suffix,headers=viewer)
            self.assertEqual(response.status_code,404,(suffix,response.text))
        self.req('GET','/api/media',headers=self.scoped(self.second,viewer),expected=403)
        self.req('PUT',f"/api/media/{other['id']}/favorite",headers=viewer,json={'favorite':True},expected=404)
        self.req('DELETE',f"/api/media/{other['id']}",headers=viewer,expected=404)
        self.assertEqual(self.client.get(first['file_url'],headers=viewer).content,self.picture)
        self.assertEqual(self.client.get(first['file_url'],headers=self.scoped(self.second)).status_code,400)
        self.assertEqual(self.client.get(other['file_url'],headers=viewer).status_code,403)

    def test_shared_original_independent_trash_and_scope_specific_comments(self):
        photo = self.upload([self.first,self.second])
        self.assertEqual(photo['shared_count'],2)
        with self.app.database() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM media').fetchone()[0],1)
            self.assertEqual(db.execute('SELECT COUNT(*) FROM media_children').fetchone()[0],2)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM activity WHERE type='upload'").fetchone()[0],2)
        comment=self.req('POST',f"/api/media/{photo['id']}/comments",headers=self.scoped(self.first),json={'text':'姐姐的留言'})
        self.assertEqual(self.req('GET',f"/api/media/{photo['id']}/comments",headers=self.scoped(self.second))['items'],[])
        self.req('POST',f"/api/media/{photo['id']}/comments",headers=self.scoped(self.second),json={'text':'回复','reply_to_id':comment['id']},expected=409)
        self.req('DELETE',f"/api/media/{photo['id']}",headers=self.scoped(self.first))
        self.assertEqual(self.req('GET','/api/media',headers=self.scoped(self.first))['total'],0)
        self.assertEqual(self.req('GET','/api/media',headers=self.scoped(self.second))['total'],1)
        self.assertEqual(self.req('GET','/api/trash',headers=self.scoped(self.first))['total'],1)
        self.assertEqual(self.req('GET','/api/trash',headers=self.scoped(self.second))['total'],0)
        self.req('POST',f"/api/media/{photo['id']}/restore",headers=self.scoped(self.first))
        self.assertEqual(self.req('GET','/api/media',headers=self.scoped(self.first))['total'],1)

    def test_child_admin_can_invite_but_cannot_become_family_admin(self):
        member, child_admin=self.invite('babyadmin','admin')
        scoped=self.scoped(self.first,child_admin)
        identity=self.req('GET','/api/me',headers=scoped)
        self.assertEqual(identity['role'],'admin')
        self.assertFalse(identity['is_family_admin'])
        self.assertTrue(identity['permissions']['can_invite_child'])
        self.assertFalse(identity['permissions']['can_manage_storage'])
        self.req('POST','/api/children',headers=scoped,json={'name':'越权宝宝'},expected=403)
        self.req('GET','/api/storage',headers=scoped,expected=403)
        self.req('GET','/api/admin/overview',headers=scoped,expected=403)
        self.req('PATCH',f'/api/children/{self.first}',headers=scoped,json={'name':'姐姐新名字'})
        self.req('PATCH',f'/api/children/{self.second}',headers=scoped,json={'name':'不允许'},expected=403)
        invited=self.req('POST','/api/invitations',headers=scoped,json={'name':'新家人','role':'admin','child_ids':[self.first]})
        self.assertFalse(invited['is_family_admin'])
        self.req('GET','/api/invitations',headers=scoped)
        self.req('POST','/api/invitations',headers=scoped,json={'name':'越权','role':'admin'},expected=403)
        self.req('PUT',f"/api/children/{self.first}/members/{member['id']}",headers=scoped,json={'role':None},expected=409)

    def test_shared_metadata_requires_every_child_but_local_trash_does_not(self):
        shared=self.upload([self.first,self.second])
        _, child_admin=self.invite('limitedadmin','admin')
        scoped=self.scoped(self.first,child_admin)
        detail=self.req('GET',f"/api/media/{shared['id']}",headers=scoped)
        self.assertFalse(detail['can_edit'])
        self.assertTrue(detail['can_delete'])
        self.req('PUT',f"/api/media/{shared['id']}",headers=scoped,json={'description':'不能改妹妹的共用说明'},expected=403)
        self.req('DELETE',f"/api/media/{shared['id']}",headers=scoped)
        self.assertEqual(self.req('GET','/api/media',headers=self.scoped(self.second))['total'],1)

    def test_join_existing_account_and_removal_apply_to_existing_session(self):
        member, viewer=self.invite('existinguser')
        invite=self.req('POST','/api/invitations',json={'name':'已有家人','role':'contributor','child_ids':[self.second]})
        joined=self.req('POST','/api/invitations/join',headers=dict(viewer,**{'X-FAM-Child':'outdated-child'}),json={'code':invite['code']})
        self.assertEqual(joined['member']['id'],member['id'])
        self.assertEqual(len(self.req('GET','/api/children',headers=viewer)['items']),2)
        self.req('POST','/api/invitations/join',headers=viewer,json={'code':invite['code']},expected=401)
        self.req('PUT',f"/api/children/{self.second}/members/{member['id']}",json={'role':None})
        self.req('GET','/api/media',headers=self.scoped(self.second,viewer),expected=403)
        self.req('PUT',f"/api/children/{self.first}/members/{member['id']}",json={'role':None})
        self.assertEqual(self.req('GET','/api/children',headers=viewer)['items'],[])
        self.req('GET','/api/profile',headers=viewer,expected=403)
        self.req('GET','/api/me',headers=viewer)

    def test_archived_child_is_read_only_and_last_active_child_is_preserved(self):
        photo=self.upload([self.second],self.scoped(self.second))
        self.req('PATCH',f'/api/children/{self.second}',json={'archived':True})
        self.assertEqual(len(self.req('GET','/api/children')['items']),1)
        self.assertEqual(len(self.req('GET','/api/children?include_archived=true')['items']),2)
        self.req('GET',f"/api/media/{photo['id']}",headers=self.scoped(self.second))
        self.req('DELETE',f"/api/media/{photo['id']}",headers=self.scoped(self.second),expected=409)
        self.req('PATCH',f'/api/children/{self.first}',json={'archived':True},expected=409)
        self.req('PATCH',f'/api/children/{self.second}',json={'archived':False})
        self.req('DELETE',f"/api/media/{photo['id']}",headers=self.scoped(self.second))

    def test_parallel_child_requests_keep_profiles_and_photos_separate(self):
        one=self.upload([self.first])
        two=self.upload([self.second],self.scoped(self.second))
        def load(child):
            headers=self.scoped(child)
            profile=self.req('GET','/api/profile',headers=headers)
            media=self.req('GET','/api/media',headers=headers)
            return child,profile,media
        with ThreadPoolExecutor(max_workers=4) as pool:
            results=list(pool.map(load,[self.first,self.second]*4))
        for child,profile,media in results:
            self.assertEqual(profile['child_id'],child)
            self.assertEqual(media['items'][0]['id'],one['id'] if child==self.first else two['id'])
            self.assertIn('child_id='+child,media['items'][0]['thumbnail_url'])

    def test_avatar_and_binary_urls_require_their_selected_child(self):
        photo=self.upload([self.second],self.scoped(self.second))
        self.req('PATCH',f'/api/children/{self.first}',json={'avatar_media_id':photo['id']},expected=404)
        saved=self.req('PATCH',f'/api/children/{self.second}',json={'avatar_media_id':photo['id']})
        self.assertIn('child_id='+self.second,saved['avatar_url'])
        member,viewer=self.invite('avatarviewer','viewer',[self.second])
        response=self.client.get(saved['avatar_url'],headers=viewer)
        self.assertEqual(response.status_code,200,response.text[:100])
        self.assertIn('no-store',response.headers['cache-control'])
        response=self.client.get(photo['file_url'],headers=dict(viewer,Range='bytes=0-9'))
        self.assertEqual((response.status_code,response.content),(206,self.picture[:10]))
        self.assertEqual(self.client.head(photo['file_url'],headers=viewer).status_code,200)
        self.req('PUT',f"/api/children/{self.second}/members/{member['id']}",json={'role':None})
        self.assertEqual(self.client.get(saved['avatar_url'],headers=viewer).status_code,403)
        self.assertEqual(self.client.head(photo['file_url'],headers=viewer).status_code,403)

    def test_family_identity_change_preserves_child_grants_but_legacy_role_updates_current_child(self):
        member,viewer=self.invite('rolesuser','contributor',[self.first,self.second])
        self.req('PATCH',f"/api/members/{member['id']}",headers=self.scoped(self.first),json={'role':'viewer'})
        self.assertFalse(self.req('GET','/api/me',headers=self.scoped(self.first,viewer))['permissions']['can_upload'])
        self.assertTrue(self.req('GET','/api/me',headers=self.scoped(self.second,viewer))['permissions']['can_upload'])
        self.req('PATCH',f"/api/members/{member['id']}",json={'family_role':'admin'})
        self.assertTrue(self.req('GET','/api/me',headers=viewer)['is_family_admin'])
        self.req('PATCH',f"/api/members/{member['id']}",json={'family_role':'contributor'})
        self.assertFalse(self.req('GET','/api/me',headers=self.scoped(self.first,viewer))['permissions']['can_upload'])
        self.assertTrue(self.req('GET','/api/me',headers=self.scoped(self.second,viewer))['permissions']['can_upload'])

    def test_linking_shared_photo_keeps_one_original_and_creates_one_target_activity(self):
        photo=self.upload([self.first])
        self.req('POST',f"/api/media/{photo['id']}/children",headers=self.scoped(self.first),json={'child_ids':[self.second]})
        self.req('POST',f"/api/media/{photo['id']}/children",headers=self.scoped(self.first),json={'child_ids':[self.second]})
        with self.app.database() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM media').fetchone()[0],1)
            self.assertEqual(db.execute('SELECT COUNT(*) FROM media_children').fetchone()[0],2)
            self.assertEqual(db.execute('SELECT COUNT(*) FROM activity WHERE child_id=?',(self.second,)).fetchone()[0],1)
        self.assertEqual(self.req('GET',f"/api/media/{photo['id']}",headers=self.scoped(self.second))['shared_count'],2)

    def test_revoking_bound_pending_child_access_revokes_its_invitation(self):
        with self.app.database() as db:
            db.execute('INSERT INTO members(id,name,role,joined_at) VALUES (?,?,?,?)',('pending','待加入家人','家人',self.app.now_text()))
            db.execute('INSERT INTO child_members VALUES (?,?,?)',(self.first,'pending','viewer'))
        invite=self.req('POST','/api/invitations',json={'member_id':'pending','name':'待加入家人','role':'admin','child_ids':[self.first]})
        self.req('PUT',f'/api/children/{self.first}/members/pending',json={'role':None})
        self.req('POST','/api/invitations/accept',headers={},json={'code':invite['code'],'username':'pendinguser',
                 'password':'member-password','name':'待加入家人'},expected=401)


if __name__=='__main__':
    unittest.main()
