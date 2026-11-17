"""Import the verified desktop export without duplicating its media files."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import uuid

from PIL import Image, ImageOps


def local_time(value, fallback, local_timezone):
    try:
        number = float(value)
        if number > 10**11:
            number /= 1000
        if number <= 0:
            return fallback
        return datetime.fromtimestamp(number, local_timezone).isoformat(timespec='milliseconds')
    except (ValueError, TypeError, OverflowError, OSError):
        return fallback


def identity(kind, value):
    return uuid.uuid5(uuid.NAMESPACE_URL, 'xiaojia-time/' + kind + '/' + str(value)).hex


def import_export(args):
    import app
    library = args.library.resolve()
    progress = library / '.下载进度' / 'progress.sqlite'
    manifest = json.loads(args.manifest.read_text(encoding='utf-8-sig'))
    raw_document = json.loads(args.records.read_text(encoding='utf-8-sig')) if args.records else {'media': []}
    raw_media = {str(item['media_id']): item for item in raw_document['media']}
    source = sqlite3.connect(progress.as_uri() + '?mode=ro', uri=True)
    source.row_factory = sqlite3.Row
    downloaded = {str(row['media_id']): dict(row) for row in source.execute('SELECT * FROM downloads')}
    failed = {str(row[0]) for row in source.execute('SELECT media_id FROM failures')}
    source.close()
    prepared = []
    members = {}
    owner_sources = set()
    for item in manifest['items']:
        source_id = str(item['id'])
        entry = downloaded.get(source_id)
        if entry is None or source_id in failed:
            raise ValueError('导出清单里还有未完成的文件，请先完成照片导出。')
        path = (library / entry['relative_path']).resolve()
        if not path.is_relative_to(library) or not path.is_file() or path.stat().st_size != entry['byte_count']:
            raise ValueError('照片目录与导出记录不一致，请检查文件是否完整。')
        metadata = json.loads(entry['metadata'])
        if not metadata.get('valid'):
            raise ValueError('照片导出记录尚未通过检查。')
        if args.verify:
            with path.open('rb') as stream:
                actual = hashlib.file_digest(stream, 'sha256').hexdigest()
            if actual != entry['sha256']:
                raise ValueError('有文件的内容与原导出结果不一致，请先核对。')
        raw = raw_media.get(source_id, {})
        uploader_source = str(raw.get('upload_uid') or 'original-family')
        uploader_name = (raw.get('member_name') or '家人').strip()
        if uploader_name == '我':
            owner_sources.add(uploader_source)
            uploader_name = args.self_name
        members[uploader_source] = uploader_name
        for reaction in (raw.get('likes') or []) + (raw.get('comments') or []):
            person = reaction.get('uid')
            name = reaction.get('member_name')
            if person is not None and name:
                if name == '我':
                    owner_sources.add(str(person))
                    name = args.self_name
                members[str(person)] = name
        prepared.append((item, entry, metadata, raw, uploader_source, uploader_name, path))
    if len({str(item['id']) for item in manifest['items']}) != len(prepared):
        raise ValueError('相册清单中存在重复记录。')

    created = app.now_text()
    birthday = args.birthday
    child_name = args.child_name
    if args.profile:
        profile = json.loads(args.profile.read_text(encoding='utf-8-sig'))
        child_name = profile.get('name') or child_name
        if profile.get('birthTsMillis'):
            birthday = datetime.fromtimestamp(profile['birthTsMillis'] / 1000, timezone.utc).date().isoformat()
    if birthday:
        birthday = app.parse_time(birthday).date().isoformat()
    imported = 0
    with app.database() as connection:
        connection.execute('BEGIN IMMEDIATE')
        child_id = connection.execute("SELECT value FROM settings WHERE key='default_child_id'").fetchone()[0]
        had_media = bool(connection.execute('SELECT 1 FROM media LIMIT 1').fetchone())
        profile_initialized = bool(connection.execute("SELECT 1 FROM settings WHERE key='import_profile_initialized'").fetchone())
        update_profile = args.update_profile or (not had_media and not profile_initialized)
        for source_id, name in members.items():
            member_id = identity('member', source_id)
            role = '相册主人' if source_id in owner_sources else '家人'
            inserted = connection.execute('INSERT OR IGNORE INTO members(id,name,role,joined_at) VALUES (?,?,?,?)',
                                          (member_id, name, role, created))
            if inserted.rowcount:
                connection.execute('INSERT INTO child_members(child_id,member_id,role) VALUES (?,?,?)',
                                   (child_id, member_id, 'contributor'))
        for item, entry, metadata, raw, uploader_source, uploader_name, path in prepared:
            source_id = str(item['id'])
            media_id = identity('media', source_id)
            if connection.execute('SELECT 1 FROM media WHERE source_id=?', (source_id,)).fetchone():
                continue
            captured_text = metadata.get('capture_time') or item.get('album_time') or item['album_date']
            captured = app.parse_time(captured_text)
            album_date = item.get('album_date') or captured.date().isoformat()
            # The album's wall-clock timestamp preserves its ordering within a day.
            sort_time = app.parse_time(item.get('album_time') or captured_text).timestamp()
            upload_time = local_time(raw.get('upload_time'), captured.isoformat(), app.LOCAL_TIMEZONE)
            favorite = any(str(reaction.get('uid')) in owner_sources for reaction in raw.get('likes', []))
            metadata['original_album_record'] = raw
            connection.execute('''INSERT INTO media
                (id,source_id,kind,root_kind,relative_path,filename,mime_type,size_bytes,sha256,width,height,duration,
                 captured_at,album_date,sort_time,description,uploader_id,uploader_name,favorite,created_at,updated_at,metadata_json)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)''',
                (media_id, source_id, item['kind'], 'library', path.relative_to(library).as_posix(), path.name,
                 'image/jpeg' if item['kind'] == 'photo' else 'video/mp4', entry['byte_count'], entry['sha256'],
                 metadata.get('width'), metadata.get('height'), metadata.get('duration'), captured.isoformat(),
                 album_date, sort_time, item.get('description') or raw.get('media_desc') or '',
                 identity('member', uploader_source), uploader_name, favorite, upload_time, created,
                 json.dumps(metadata, ensure_ascii=False)))
            connection.execute('''INSERT INTO media_children(media_id,child_id,deleted_at,linked_by,created_at)
                VALUES (?,?,NULL,?,?)''', (media_id, child_id, identity('member', uploader_source), created))
            connection.execute('INSERT INTO activity(id,media_id,type,member_name,text,created_at,child_id) VALUES (?,?,?,?,?,?,?)',
                               (identity('activity', source_id), media_id, 'upload', uploader_name,
                                uploader_name + ('上传了照片' if item['kind'] == 'photo' else '上传了视频'), upload_time, child_id))
            if favorite:
                for owner_source in owner_sources:
                    connection.execute('INSERT OR IGNORE INTO favorites(member_id,media_id,created_at) VALUES (?,?,?)',
                                       (identity('member', owner_source), media_id, created))
            for index, comment in enumerate(raw.get('comments') or []):
                text = comment.get('content') or comment.get('text')
                if not isinstance(text, str) or not text.strip():
                    continue
                person = str(comment.get('uid', ''))
                name = members.get(person, comment.get('member_name') or '家人')
                member_id = identity('member', person) if person in members else None
                connection.execute('INSERT OR IGNORE INTO comments(id,media_id,member_id,name,text,created_at,child_id) VALUES (?,?,?,?,?,?,?)',
                    (identity('comment', source_id + '/' + str(comment.get('comment_id', index))), media_id,
                     member_id, name, text, local_time(comment.get('add_time'), upload_time, app.LOCAL_TIMEZONE), child_id))
            imported += 1
        if update_profile:
            for key, value in [('child_name', child_name), ('birthday', birthday)]:
                if value:
                    connection.execute('INSERT OR REPLACE INTO settings VALUES (?,?)', (key, value))
                    column = 'name' if key == 'child_name' else 'birthday'
                    connection.execute('UPDATE children SET ' + column + '=?,updated_at=? WHERE id=?', (value, created, child_id))
            if args.avatar:
                temporary = app.DATA / 'avatar.temporary.jpg'
                with Image.open(args.avatar) as picture:
                    image = ImageOps.exif_transpose(picture).convert('RGB')
                    image.thumbnail((640, 640))
                    image.save(temporary, 'JPEG', quality=95)
                os.replace(temporary, app.DATA / 'avatar.jpg')
                connection.execute("UPDATE settings SET value='' WHERE key='avatar_media_id'")
                connection.execute('UPDATE children SET avatar_media_id=NULL WHERE id=?', (child_id,))
            else:
                first_photo = connection.execute("SELECT id FROM media WHERE kind='photo' AND deleted_at IS NULL ORDER BY album_date DESC,sort_time LIMIT 1").fetchone()
                if first_photo:
                    connection.execute("UPDATE settings SET value=? WHERE key='avatar_media_id' AND value=''", (first_photo['id'],))
                    connection.execute("UPDATE children SET avatar_media_id=? WHERE id=? AND avatar_media_id IS NULL", (first_photo['id'], child_id))
        connection.execute("INSERT OR REPLACE INTO settings VALUES ('import_profile_initialized','1')")
    with app.database() as connection:
        profile = app.profile_data(connection)
    print(f"本次导入 {imported} 项，相册共有 {profile['photos_count']} 张照片、{profile['videos_count']} 个视频。")
    print(f"已保留 {len(members)} 位原相册家人的称呼，拍摄日期和原有说明已保留。")
    return profile


def main():
    parser = argparse.ArgumentParser(description='把已核对的亲时光导出文件接入FAM宝贝相册。')
    parser.add_argument('--library', type=Path, required=True, help='完整相册目录')
    parser.add_argument('--manifest', type=Path, required=True, help='导出工具的相册清单')
    parser.add_argument('--records', type=Path, help='原始相册记录，用于恢复家人称呼和说明')
    parser.add_argument('--profile', type=Path, help='原宝宝资料')
    parser.add_argument('--avatar', type=Path, help='宝宝头像图片')
    parser.add_argument('--child-name', default='宝宝')
    parser.add_argument('--birthday', default='')
    parser.add_argument('--self-name', default='宝宝爸爸')
    parser.add_argument('--verify', action='store_true', help='再次核对每个文件的内容')
    parser.add_argument('--update-profile', action='store_true', help='同时用导入资料更新宝宝名字、生日和头像')
    args = parser.parse_args()
    os.environ['ALBUM_LIBRARY'] = str(args.library.resolve())
    try:
        import_export(args)
    except (OSError, ValueError, sqlite3.Error, KeyError, RuntimeError) as error:
        message = str(error) if isinstance(error, (ValueError, RuntimeError)) else '相册资料无法读取，请检查路径和文件是否齐全。'
        print('导入未完成。' + message)
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()
