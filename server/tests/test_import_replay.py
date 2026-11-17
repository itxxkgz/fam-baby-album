"""Replay the export-import CLI using only generated photos and isolated data."""
import hashlib
from contextlib import closing
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
import uuid

from PIL import Image


class ImportReplayTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='fam-import-replay-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.library = self.root / 'library'
        self.library.mkdir()
        self.data = self.root / 'data'
        self.photo = self.library / '测试原照片.jpg'
        Image.new('RGB', (24, 18), 'coral').save(self.photo, 'JPEG')
        self.original_bytes = self.photo.read_bytes()
        self.original_mtime = self.photo.stat().st_mtime_ns
        self.capture = '2021-06-02T10:11:12+08:00'
        self.manifest = self.root / 'manifest.json'
        self.records = self.root / 'records.json'
        self.manifest.write_text(json.dumps({'items': [{
            'id': 'synthetic-photo', 'kind': 'photo', 'album_date': '2021-06-02',
            'album_time': self.capture, 'description': '最初的照片说明',
        }]}, ensure_ascii=False), encoding='utf-8')
        self.records.write_text(json.dumps({'media': [{
            'media_id': 'synthetic-photo', 'upload_uid': 'synthetic-mom', 'member_name': '测试妈妈',
            'upload_time': 1622602800000,
            'likes': [{'uid': 'synthetic-owner', 'member_name': '我'}],
            'comments': [{'comment_id': 'synthetic-comment', 'uid': 'synthetic-grandma',
                          'member_name': '测试奶奶', 'content': '原来的家人留言', 'add_time': 1622602860000}],
        }]}, ensure_ascii=False), encoding='utf-8')
        progress = self.library / '.下载进度'
        progress.mkdir()
        with closing(sqlite3.connect(progress / 'progress.sqlite')) as db, db:
            db.executescript('''
                CREATE TABLE downloads(media_id TEXT PRIMARY KEY,relative_path TEXT,byte_count INTEGER,
                    sha256 TEXT,metadata TEXT);
                CREATE TABLE failures(media_id TEXT PRIMARY KEY);
            ''')
            db.execute('INSERT INTO downloads VALUES (?,?,?,?,?)', (
                'synthetic-photo', self.photo.name, len(self.original_bytes),
                hashlib.sha256(self.original_bytes).hexdigest(),
                json.dumps({'valid': True, 'kind': 'photo', 'width': 24, 'height': 18, 'capture_time': self.capture}),
            ))
        self.environment = dict(os.environ, PYTHONUTF8='1', ALBUM_DATA=str(self.data),
                                ALBUM_LIBRARY=str(self.library), ALBUM_PASSWORD='', ALBUM_BOOTSTRAP_TOKEN='',
                                ALBUM_STORAGE_KEY='', ALBUM_STORAGE_ROOTS='[]', ALBUM_IMPORT_ROOTS='[]',
                                ALBUM_STORAGE_ALLOWED_ENDPOINTS='')
        self.server = Path(__file__).resolve().parents[1]

    def run_import(self):
        result = subprocess.run([
            sys.executable, '-X', 'utf8', str(self.server / 'import_album.py'),
            '--library', str(self.library), '--manifest', str(self.manifest), '--records', str(self.records),
            '--child-name', '最初的宝宝名字', '--birthday', '2020-02-29', '--self-name', '测试爸爸', '--verify',
        ], cwd=self.root, env=self.environment, capture_output=True, text=True, encoding='utf-8', timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result.stdout

    def database(self):
        db = sqlite3.connect(self.data / 'album.sqlite')
        db.row_factory = sqlite3.Row
        db.execute('PRAGMA foreign_keys=ON')
        self.addCleanup(db.close)
        return db

    @staticmethod
    def source_id(kind, value):
        return uuid.uuid5(uuid.NAMESPACE_URL, 'xiaojia-time/' + kind + '/' + value).hex

    def test_first_cli_import_preserves_files_dates_family_records_and_child_links(self):
        self.assertIn('本次导入 1 项', self.run_import())
        with self.database() as db:
            child = db.execute('SELECT * FROM children').fetchone()
            self.assertEqual((child['name'], child['birthday']), ('最初的宝宝名字', '2020-02-29'))
            self.assertEqual(db.execute('SELECT COUNT(*) FROM media').fetchone()[0], 1)
            photo = db.execute('SELECT * FROM media').fetchone()
            self.assertEqual(photo['captured_at'], self.capture)
            self.assertEqual(photo['description'], '最初的照片说明')
            self.assertEqual(photo['root_kind'], 'library')
            self.assertEqual(photo['relative_path'], self.photo.name)
            self.assertEqual(photo['uploader_name'], '测试妈妈')
            self.assertEqual(photo['sha256'], hashlib.sha256(self.original_bytes).hexdigest())
            link = db.execute('SELECT * FROM media_children').fetchone()
            self.assertEqual((link['media_id'], link['child_id'], link['deleted_at']), (photo['id'], child['id'], None))
            self.assertEqual(child['avatar_media_id'], photo['id'])
            expected = {'测试妈妈', '测试爸爸', '测试奶奶'}
            actual = {row['name'] for row in db.execute('''SELECT m.name FROM members m JOIN child_members cm
                ON cm.member_id=m.id WHERE cm.child_id=? AND m.username IS NULL''', (child['id'],))}
            self.assertEqual(actual, expected)
            self.assertEqual(db.execute('SELECT COUNT(*) FROM child_members WHERE role=? AND child_id=?',
                                        ('contributor', child['id'])).fetchone()[0], 3)
            comment = db.execute('SELECT * FROM comments').fetchone()
            self.assertEqual((comment['text'], comment['name'], comment['child_id']), ('原来的家人留言', '测试奶奶', child['id']))
            favorite = db.execute('SELECT * FROM favorites').fetchone()
            self.assertEqual((favorite['member_id'], favorite['media_id']),
                             (self.source_id('member', 'synthetic-owner'), photo['id']))
            activity = db.execute('SELECT * FROM activity').fetchone()
            self.assertEqual((activity['media_id'], activity['child_id']), (photo['id'], child['id']))
        self.assertEqual(self.photo.read_bytes(), self.original_bytes)
        self.assertEqual(self.photo.stat().st_mtime_ns, self.original_mtime)

    def test_replay_does_not_restore_revoked_access_or_overwrite_existing_album(self):
        self.run_import()
        mother = self.source_id('member', 'synthetic-mom')
        grandma = self.source_id('member', 'synthetic-grandma')
        with self.database() as db:
            first = db.execute('SELECT id FROM children').fetchone()[0]
            photo = db.execute('SELECT id FROM media').fetchone()[0]
            # Emulate a registered account whose access to the original baby was removed.
            salt = '12' * 16
            digest = hashlib.scrypt(b'registered-password', salt=bytes.fromhex(salt), n=16384, r=8, p=1).hex()
            db.execute('''UPDATE members SET username=?,password_salt=?,password_hash=?,registered_at=? WHERE id=?''',
                       ('synthetic-mother', salt, digest, '2025-01-01', mother))
            db.execute('INSERT INTO sessions VALUES (?,?,?)', ('preserved-session', mother, 9999999999))
            db.execute('DELETE FROM child_members WHERE child_id=? AND member_id=?', (first, mother))
            db.execute("UPDATE child_members SET role='viewer' WHERE child_id=? AND member_id=?", (first, grandma))
            # Existing access to another baby must also survive the replay unchanged.
            db.execute('INSERT INTO children VALUES (?,?,?,?,?,?,?)', ('second-child', '妹妹', '2024-01-01', None, None, '2025', '2025'))
            db.execute("INSERT INTO child_members VALUES ('second-child',?,'viewer')", (mother,))
            db.execute('INSERT INTO media_children VALUES (?,?,NULL,?,?)', (photo, 'second-child', mother, '2025'))
            db.execute('UPDATE media_children SET deleted_at=? WHERE child_id=? AND media_id=?', ('2025-01-02', first, photo))
            db.execute('''UPDATE media SET description=?,captured_at=?,album_date=?,sort_time=?,updated_at=? WHERE id=?''',
                       ('家人后来整理的说明', '2022-03-04T05:06:07+08:00', '2022-03-04', 1646341567, '2025-01-03', photo))
            db.execute('UPDATE children SET name=?,birthday=?,avatar_media_id=NULL WHERE id=?', ('已经更改的宝宝名字', '2020-03-01', first))
            tables = ('children', 'members', 'sessions', 'media', 'media_children', 'child_members', 'comments', 'favorites', 'activity')
            expected = {table: [tuple(row) for row in db.execute('SELECT * FROM ' + table + ' ORDER BY rowid')] for table in tables}
        self.assertIn('本次导入 0 项', self.run_import())
        with self.database() as db:
            self.assertIsNone(db.execute('SELECT 1 FROM child_members WHERE child_id=? AND member_id=?', (first, mother)).fetchone())
            self.assertEqual(db.execute('SELECT role FROM child_members WHERE child_id=? AND member_id=?', (first, grandma)).fetchone()[0], 'viewer')
            for table, rows in expected.items():
                self.assertEqual([tuple(row) for row in db.execute('SELECT * FROM ' + table + ' ORDER BY rowid')], rows, table)
        self.assertEqual(self.photo.read_bytes(), self.original_bytes)
        self.assertEqual(self.photo.stat().st_mtime_ns, self.original_mtime)


if __name__ == '__main__':
    unittest.main()
