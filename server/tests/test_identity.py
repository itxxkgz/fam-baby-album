"""Legacy identity migration using entirely synthetic family data."""
from contextlib import contextmanager
from pathlib import Path
import hashlib
import os
import sqlite3
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from identity import IdentityService, password_digest


class IdentityMigrationTests(unittest.TestCase):
    def test_legacy_family_preserved_and_credentials_migrate_once(self):
        with tempfile.TemporaryDirectory(prefix='album-identity-migration-') as directory:
            root = Path(directory)

            @contextmanager
            def database():
                connection = sqlite3.connect(root / 'migration.sqlite')
                connection.row_factory = sqlite3.Row
                connection.execute('PRAGMA foreign_keys=ON')
                try:
                    yield connection
                    connection.commit()
                except BaseException:
                    connection.rollback()
                    raise
                finally:
                    connection.close()

            with database() as connection:
                connection.executescript('''
                    CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);
                    CREATE TABLE members(id TEXT PRIMARY KEY,name TEXT,role TEXT,joined_at TEXT);
                    CREATE TABLE sessions(token_hash TEXT PRIMARY KEY,member_id TEXT,expires_at REAL);
                    CREATE TABLE media(id TEXT PRIMARY KEY,favorite INTEGER,root_kind TEXT,relative_path TEXT,uploader_id TEXT);
                ''')
                connection.executemany('INSERT INTO members VALUES (?,?,?,?)',
                    [(f'person-{i}', f'测试家人{i}', '相册主人' if i == 0 else '家人', '2024-01-01') for i in range(7)])
                connection.executemany('INSERT INTO media VALUES (?,?,?,?,?)', [
                    ('original-photo', 1, 'library', '原文件.jpg', 'person-1'),
                    ('later-upload', 0, 'uploads', '后来上传.jpg', 'person-0'),
                ])
                connection.execute('INSERT INTO sessions VALUES (?,?,?)', ('legacy-token-hash', 'person-0', 9999999999))
                salt = '01' * 16
                connection.executemany('INSERT INTO settings VALUES (?,?)', [
                    ('password_salt', salt), ('password_hash', password_digest('legacy-password', salt)),
                ])
                original_media = [tuple(row) for row in connection.execute('SELECT * FROM media ORDER BY id')]

            service = IdentityService(database, root)
            with patch.dict(os.environ, {'ALBUM_PASSWORD': 'migration-password', 'ALBUM_BOOTSTRAP_TOKEN': ''}):
                with database() as connection:
                    service.initialize(connection)
                with database() as connection:
                    self.assertEqual(connection.execute('SELECT COUNT(*) FROM members').fetchone()[0], 7)
                    owner = connection.execute("SELECT * FROM members WHERE username='owner'").fetchone()
                    self.assertEqual(owner['id'], 'person-0')
                    self.assertEqual(owner['account_role'], 'admin')
                    self.assertTrue(service.check_password(owner, 'migration-password'))
                    self.assertEqual(connection.execute('SELECT COUNT(*) FROM members WHERE username IS NULL').fetchone()[0], 6)
                    self.assertEqual(connection.execute('SELECT COUNT(*) FROM sessions').fetchone()[0], 0)
                    self.assertEqual([tuple(row) for row in connection.execute('SELECT * FROM media ORDER BY id')], original_media)
                    self.assertEqual(connection.execute('SELECT member_id,media_id FROM favorites').fetchone()[:], ('person-0', 'original-photo'))
                    self.assertIsNone(connection.execute("SELECT 1 FROM settings WHERE key='password_hash'").fetchone())
                    new_salt = '02' * 16
                    connection.execute('UPDATE members SET password_salt=?,password_hash=? WHERE id=?',
                                       (new_salt, password_digest('changed-personal-password', new_salt), owner['id']))
                    connection.execute('DELETE FROM favorites')
                with database() as connection:
                    service.initialize(connection)
                    owner = connection.execute("SELECT * FROM members WHERE username='owner'").fetchone()
                    self.assertTrue(service.check_password(owner, 'changed-personal-password'))
                    self.assertFalse(service.check_password(owner, 'migration-password'))
                    self.assertEqual(connection.execute('SELECT COUNT(*) FROM favorites').fetchone()[0], 0)
                    self.assertEqual(connection.execute('SELECT COUNT(*) FROM members').fetchone()[0], 7)
            self.assertFalse(service.setup_file.exists())


if __name__ == '__main__':
    unittest.main()
