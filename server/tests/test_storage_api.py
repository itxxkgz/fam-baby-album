"""HTTP integration against a real local S3 service, with synthetic media only.

Set ALBUM_S3_TEST_FIXTURE to a private JSON file containing endpoint, bucket,
access_key_id and secret_access_key. With no fixture this test is skipped.
The app runs in a separate process and a temporary data directory; no running
album instance or existing library is contacted.
"""
from pathlib import Path
import os
import subprocess
import sys
import unittest


class StorageHttpIntegrationTests(unittest.TestCase):
    @unittest.skipUnless(os.getenv('ALBUM_S3_TEST_FIXTURE'), '未配置独立的本地对象存储测试资料')
    def test_real_s3_through_album_http_api(self):
        result = subprocess.run([sys.executable, '-X', 'utf8', str(Path(__file__).resolve()),
                                 '--worker', os.environ['ALBUM_S3_TEST_FIXTURE']],
                                capture_output=True, text=True, encoding='utf-8', timeout=180,
                                creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        self.assertEqual(result.returncode, 0, result.stdout.strip() or '独立存储接口测试未完成')
        self.assertIn('全部接口检查通过', result.stdout)


def run_worker(fixture_path):
    import hashlib
    import io
    import json
    import tempfile
    import uuid
    from urllib.parse import urlsplit

    from PIL import Image
    from fastapi.testclient import TestClient

    def require(condition, message):
        if not condition:
            raise AssertionError(message)

    fixture = json.loads(Path(fixture_path).read_text(encoding='utf-8-sig'))
    endpoint = urlsplit(fixture['endpoint'])
    require(endpoint.hostname == '127.0.0.1' and endpoint.scheme in ('http', 'https'),
            '测试只能使用这台电脑上的独立对象存储服务。')
    with tempfile.TemporaryDirectory(prefix='album-storage-http-') as directory:
        root = Path(directory)
        os.environ.update(ALBUM_DATA=str(root / 'data'), ALBUM_LIBRARY=str(root / 'library'),
                          ALBUM_PASSWORD='', ALBUM_BOOTSTRAP_TOKEN='synthetic-setup-code-for-test',
                          ALBUM_STORAGE_ALLOWED_ENDPOINTS=fixture['endpoint'],
                          ALBUM_STORAGE_ROOTS='', ALBUM_STORAGE_KEY='')
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        import app
        from media_inspection import _ffmpeg_path

        app.LIBRARY.mkdir()
        prefix = 'api-contract/' + uuid.uuid4().hex
        cloud_id = None
        with TestClient(app.app, raise_server_exceptions=False) as client:
            def request(method, path, *, headers=None, expected=200, **kwargs):
                response = client.request(method, path, headers=headers, **kwargs)
                require(response.status_code == expected, f'接口检查未通过：{method} {path.split("?")[0]}，请检查本地测试服务。')
                return response

            try:
                initialized = request('POST', '/api/setup', json={
                    'setup_code': os.environ['ALBUM_BOOTSTRAP_TOKEN'], 'username': 'testowner',
                    'password': 'synthetic-admin-password', 'name': '测试管理员'}).json()
                admin = {'Authorization': 'Bearer ' + initialized['token']}

                def join(role):
                    invitation = request('POST', '/api/invitations', headers=admin, json={'role': role}).json()
                    joined = request('POST', '/api/invitations/accept', json={
                        'code': invitation['code'], 'username': 'test' + role,
                        'password': 'synthetic-member-password', 'name': '测试家人'}).json()
                    return {'Authorization': 'Bearer ' + joined['token']}, joined['member']['id']

                contributor, contributor_id = join('contributor')
                viewer, _ = join('viewer')
                for user in (viewer, contributor):
                    request('GET', '/api/storage', headers=user, expected=403)
                    request('POST', '/api/storage', headers=user, json={'name': '越权位置'}, expected=403)

                cloud = request('POST', '/api/storage', headers=admin, expected=201, json={
                    'name': '独立接口测试存储', 'provider': 's3', 'endpoint': fixture['endpoint'],
                    'region': fixture.get('region', 'us-east-1'), 'bucket': fixture['bucket'],
                    'prefix': prefix, 'addressing_style': 'path',
                    'access_key_id': fixture['access_key_id'], 'secret_access_key': fixture['secret_access_key']}).json()
                cloud_id = cloud['id']
                require('access_key_id' not in cloud and 'secret_access_key' not in cloud,
                        '保存配置的响应不能包含云存储密钥。')
                request('POST', f'/api/storage/{cloud_id}/test', headers=admin)
                request('POST', f'/api/storage/{cloud_id}/default', headers=admin)
                for user in (viewer, contributor):
                    request('POST', f'/api/storage/{cloud_id}/default', headers=user, expected=403)
                    request('PUT', f'/api/storage/{cloud_id}', headers=user, json={'name': '越权改名'}, expected=403)
                    request('DELETE', f'/api/storage/{cloud_id}', headers=user, expected=403)

                image = Image.new('RGB', (96, 64), (44, 120, 75))
                exif = Image.Exif()
                exif[34665] = {36867: '2022:08:09 23:15:16', 36881: '-05:00'}
                output = io.BytesIO()
                image.save(output, 'JPEG', exif=exif)
                photo_bytes = output.getvalue()
                video_path = root / 'synthetic-video.mp4'
                command = [_ffmpeg_path(), '-hide_banner', '-loglevel', 'error', '-nostdin',
                           '-f', 'lavfi', '-i', 'color=c=blue:s=96x64:r=12', '-t', '1.2',
                           '-an', '-c:v', 'libx264', '-pix_fmt', 'yuv420p',
                           '-metadata', 'creation_time=2022-07-08T09:10:11Z',
                           '-metadata', 'com.apple.quicktime.creationdate=2022-07-08T09:10:11+00:00',
                           '-movflags', 'use_metadata_tags', '-y', str(video_path)]
                generated = subprocess.run(command, capture_output=True, timeout=30,
                                           creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
                require(generated.returncode == 0 and video_path.is_file(), '测试视频生成未完成，请检查视频处理程序。')
                video_bytes = video_path.read_bytes()
                uploads = []
                for name, payload, mime, headers, captured, owner in (
                    ('synthetic-photo.jpg', photo_bytes, 'image/jpeg', admin,
                     '2022-08-10T12:15:16+08:00', initialized['member']['id']),
                    ('synthetic-video.mp4', video_bytes, 'video/mp4', contributor,
                     '2022-07-08T17:10:11+08:00', contributor_id),
                ):
                    media = request('POST', '/api/upload', headers=headers,
                                    files={'file': (name, payload, mime)},
                                    data={'fallback_captured_at': '2025-01-01T01:00:00+08:00'}).json()
                    require(media['captured_at'] == captured, '上传后没有正确保留原文件的拍摄时间。')
                    with app.database() as connection:
                        row = connection.execute('SELECT * FROM media WHERE id=?', (media['id'],)).fetchone()
                        require(row['storage_id'] == cloud_id and row['root_kind'] == 'storage', '上传内容未归属选定的对象存储。')
                        require(row['object_key'].startswith(prefix + '/'), '测试内容超出了独立测试目录。')
                        require(row['uploader_id'] == owner, '上传者关联不正确。')
                        require(row['sha256'] == hashlib.sha256(payload).hexdigest(), '保存记录的文件校验不一致。')
                        require(row['size_bytes'] == len(payload) and row['captured_at'] == captured, '文件大小或拍摄时间记录不一致。')
                    full = request('GET', media['file_url'], headers=viewer)
                    require(full.content == payload, '从对象存储读取的原文件与上传内容不一致。')
                    partial = request('GET', media['file_url'], headers=viewer | {'Range': 'bytes=10-59'}, expected=206)
                    require(partial.content == payload[10:60], '对象存储分段读取的内容不一致。')
                    require(partial.headers['Content-Range'] == f'bytes 10-59/{len(payload)}', '分段读取范围说明不正确。')
                    head = request('HEAD', media['file_url'], headers=viewer)
                    require(int(head.headers['Content-Length']) == len(payload), '原文件大小响应不正确。')
                    thumbnail = request('GET', media['thumbnail_url'], headers=viewer)
                    with Image.open(io.BytesIO(thumbnail.content)) as preview:
                        preview.load()
                        require(max(preview.size) <= 640, '预览图没有按要求生成。')
                    uploads.append((media, payload))

                photo = uploads[0][0]
                request('PUT', '/api/media/' + photo['id'], headers=contributor,
                        json={'description': '不允许替别人修改'}, expected=403)
                request('DELETE', '/api/media/' + photo['id'], headers=contributor, expected=403)
                request('PUT', '/api/media/' + uploads[1][0]['id'], headers=viewer,
                        json={'description': '只读家人不能修改'}, expected=403)
                request('POST', '/api/upload', headers=viewer,
                        files={'file': ('forbidden.jpg', photo_bytes, 'image/jpeg')}, expected=403)
                request('POST', '/api/storage/local-default/test', headers=admin)
                request('POST', '/api/storage/local-default/default', headers=admin)
                for media, payload in uploads:
                    require(request('GET', media['file_url'], headers=admin).content == payload,
                            '切回本地存储后，原对象存储内容无法继续读取。')
                    require(request('GET', media['file_url'], headers=admin | {'Range': 'bytes=-16'}, expected=206).content == payload[-16:],
                            '切回本地存储后，原视频或照片的分段读取失败。')
                local = request('POST', '/api/upload', headers=admin,
                                files={'file': ('local-after-switch.jpg', photo_bytes, 'image/jpeg')}).json()
                with app.database() as connection:
                    row = connection.execute('SELECT storage_id,sha256 FROM media WHERE id=?', (local['id'],)).fetchone()
                    require(row['storage_id'] == 'local-default', '切换后的新上传没有保存到本地位置。')
                    require(row['sha256'] == hashlib.sha256(photo_bytes).hexdigest(), '切换后的本地文件校验不一致。')
                print('全部接口检查通过：真实对象存储配置、照片和视频上传、原件与预览、分段读取、拍摄时间、位置切换及家人权限。')
            finally:
                if cloud_id:
                    with app.database() as connection:
                        rows = connection.execute('SELECT storage_id,object_key FROM media WHERE storage_id=?', (cloud_id,)).fetchall()
                    for row in rows:
                        require(row['object_key'].startswith(prefix + '/'), '测试清理范围核对未通过。')
                        app.storage_manager.delete_blob(row['storage_id'], row['object_key'])
                        try:
                            app.storage_manager.stat_media(row)
                        except app.StorageError as error:
                            require(error.status_code == 404, '测试文件删除后的状态无法确认。')
                        else:
                            require(False, '测试文件尚未从独立对象存储中删除。')


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--worker':
        try:
            run_worker(sys.argv[2])
        except AssertionError as error:
            print(str(error))
            raise SystemExit(1)
        except Exception:
            print('独立接口测试未完成，请检查测试配置、本地对象存储服务和视频处理程序。')
            raise SystemExit(1)
    else:
        unittest.main()
