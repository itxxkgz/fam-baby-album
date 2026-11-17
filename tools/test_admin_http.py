"""Exercise the admin web app over real localhost HTTP using disposable albums.

Run with ``python tools/test_admin_http.py`` after installing server requirements.
No browser is opened, and neither the normal local port nor existing albums are
used. Both uvicorn processes exit gracefully before temporary files are removed.
"""

from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime
import hashlib
from http.cookiejar import CookieJar
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, ProxyHandler, Request, build_opener
import uuid
import zipfile

from PIL import Image


PROJECT = Path(__file__).resolve().parents[1]
SERVER = PROJECT / 'server'
PASSWORD = 'synthetic-http-family-password'
USERNAME = 'http.family'

# The child binds its own unused port, eliminating a port-selection race. A
# private stop file asks uvicorn to run its normal lifespan shutdown on Windows
# as well as POSIX; terminate/kill are only cleanup fallbacks after a timeout.
CHILD = r'''
import json, os, socket, threading, time
from pathlib import Path
import uvicorn

listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
listener.bind(('127.0.0.1', 0))
listener.listen(128)
config = uvicorn.Config('app:app', host='127.0.0.1', port=listener.getsockname()[1],
                        log_level='warning', access_log=False, lifespan='on')
server = uvicorn.Server(config)
control = Path(os.environ['FAM_HTTP_TEST_CONTROL'])
def watch():
    while not server.should_exit:
        if (control / 'stop').exists():
            server.should_exit = True
            return
        time.sleep(0.05)
threading.Thread(target=watch, daemon=True).start()
(control / 'port.json').write_text(json.dumps({'port': listener.getsockname()[1]}))
try:
    server.run(sockets=[listener])
finally:
    listener.close()
(control / 'stopped').write_text('done')
'''


@dataclass
class Reply:
    status: int
    headers: object
    content: bytes

    def json(self):
        return json.loads(self.content)


class LocalClient:
    """Small cookie-aware client; proxy environment variables are ignored."""

    def __init__(self, address):
        self.address = address
        self.cookies = CookieJar()
        self.opener = build_opener(ProxyHandler({}), HTTPCookieProcessor(self.cookies))
        self.csrf = None
        self.token = None
        self.child_id = None

    def close(self):
        self.cookies.clear()
        self.opener = None
        self.csrf = None
        self.token = None
        self.child_id = None

    def request(self, method, path, *, body=None, payload=None, headers=None, csrf=True):
        if self.opener is None:
            raise RuntimeError('测试连接已经关闭')
        if not path.startswith('/') or path.startswith('//'):
            raise ValueError('测试只能访问临时相册的相对地址')
        supplied = dict(headers or {})
        if self.token:
            supplied.setdefault('Authorization', 'Bearer ' + self.token)
        if self.child_id:
            supplied.setdefault('X-FAM-Child', self.child_id)
        if body is not None:
            payload = json.dumps(body, ensure_ascii=False).encode('utf-8')
            supplied['Content-Type'] = 'application/json'
        if method not in ('GET', 'HEAD'):
            supplied.setdefault('Origin', self.address)
            if csrf and self.csrf:
                supplied.setdefault('X-FAM-CSRF', self.csrf)
        request = Request(self.address + path, data=payload, headers=supplied, method=method)
        try:
            response = self.opener.open(request, timeout=30)
        except HTTPError as error:
            response = error
        with response:
            return Reply(response.status, response.headers, response.read())

    def upload(self, path, name, content, fields=None):
        boundary = 'fam-http-test-' + uuid.uuid4().hex
        parts = []
        for field, value in (fields or {}).items():
            parts.append((f'--{boundary}\r\nContent-Disposition: form-data; '
                          f'name="{field}"\r\n\r\n{value}\r\n').encode('utf-8'))
        parts.append((f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
                      f'filename="{name}"\r\nContent-Type: image/jpeg\r\n\r\n').encode('utf-8'))
        parts.extend([content, f'\r\n--{boundary}--\r\n'.encode('ascii')])
        return self.request('POST', path, payload=b''.join(parts), headers={
            'Content-Type': 'multipart/form-data; boundary=' + boundary})


@contextmanager
def running_album(root, *, data=None, library=None):
    """Start only a child owned by this test, with every album variable isolated."""
    control = root / ('server-' + uuid.uuid4().hex)
    control.mkdir()
    environment = {key: value for key, value in os.environ.items()
                   if not key.upper().startswith('ALBUM_')}
    environment.update({
        'PYTHONUTF8': '1', 'ALBUM_DATA': str(data or root / 'data'),
        'ALBUM_LIBRARY': str(library or root / 'library'),
        'ALBUM_PASSWORD': '', 'ALBUM_BOOTSTRAP_TOKEN': '', 'ALBUM_STORAGE_KEY': '',
        'ALBUM_STORAGE_ROOTS': '[]', 'ALBUM_IMPORT_ROOTS': '[]',
        'ALBUM_STORAGE_ALLOWED_ENDPOINTS': '', 'FAM_HTTP_TEST_CONTROL': str(control),
    })
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    with (control / 'server.log').open('wb') as log:
        process = subprocess.Popen([sys.executable, '-u', '-c', CHILD], cwd=SERVER,
                                   env=environment, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=flags)
        try:
            deadline = time.monotonic() + 30
            address = None
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise AssertionError('临时相册未能启动，请检查服务端依赖是否已经安装。')
                port_file = control / 'port.json'
                if port_file.exists():
                    try:
                        port = json.loads(port_file.read_text())['port']
                    except (json.JSONDecodeError, KeyError):
                        time.sleep(0.05)
                        continue
                    if port == 8765:
                        raise AssertionError('测试端口不能使用现有相册的端口。')
                    address = 'http://127.0.0.1:' + str(port)
                    probe = LocalClient(address)
                    try:
                        if probe.request('GET', '/api/status').status == 200:
                            break
                    except (URLError, TimeoutError, ConnectionError):
                        pass
                    finally:
                        probe.close()
                time.sleep(0.05)
            else:
                raise AssertionError('临时相册没有在规定时间内启动。')
            yield address
        finally:
            (control / 'stop').write_text('stop')
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            if process.returncode != 0 or not (control / 'stopped').exists():
                raise AssertionError('临时相册未能正常结束后台任务并关闭。')


def jpeg(color, captured='2021:04:05 10:20:30'):
    content = io.BytesIO()
    exif = Image.Exif()
    exif[36867] = captured
    Image.new('RGB', (48, 32), color).save(content, 'JPEG', exif=exif)
    return content.getvalue()


class AdminHttpSmokeTests(unittest.TestCase):
    def ok(self, response, status=200):
        # Never print cookies or credentials in a failed assertion.
        detail = ''
        if response.status != status:
            try:
                detail = str(response.json().get('detail', ''))[:200]
            except (ValueError, AttributeError):
                detail = '服务没有返回预期内容'
        self.assertEqual(response.status, status, detail)
        return response

    def login(self, client, username=USERNAME, password=PASSWORD):
        reply = self.ok(client.request('POST', '/api/admin/session', body={
            'username': username, 'password': password}))
        session = reply.json()
        client.csrf = session['csrf']
        self.assertNotIn('token', session)
        return reply

    def wait_job(self, client, path, timeout=30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            job = self.ok(client.request('GET', path)).json()
            if job['status'] in ('completed', 'failed', 'cancelled'):
                self.assertEqual(job['status'], 'completed', job.get('message', '后台任务没有完成'))
                return job
            time.sleep(0.1)
        self.fail('后台任务没有在规定时间内完成。')

    def exercise_two_children(self, client, photo, original):
        """Exercise real cookie and bearer requests against the same two babies."""
        listing = self.ok(client.request('GET', '/api/children')).json()
        self.assertEqual(len(listing['items']), 1)
        older = listing['selected_child_id']
        older_headers = {'X-FAM-Child': older}
        self.ok(client.request('PATCH', '/api/children/' + older, body={
            'name': '测试姐姐', 'birthday': '2020-01-02'}, headers=older_headers))
        younger = self.ok(client.request('POST', '/api/children', body={
            'name': '测试弟弟', 'birthday': '2022-02-03'}, headers=older_headers)).json()['id']
        younger_headers = {'X-FAM-Child': younger}

        app_client = LocalClient(client.address)
        self.addCleanup(app_client.close)
        session = self.ok(app_client.request('POST', '/api/login', body={
            'username': USERNAME, 'password': PASSWORD})).json()
        app_client.token = session['token']
        self.assertEqual(len(app_client.cookies), 0, 'App 测试必须使用 Bearer，不能借用后台 Cookie')
        app_client.child_id = younger
        younger_original = jpeg((33, 161, 99), '2023:06:07 08:09:10')
        younger_photo = self.ok(app_client.upload('/api/upload', '弟弟自己的照片.jpg', younger_original)).json()
        self.assertEqual(younger_photo['captured_at'], '2023-06-07T08:09:10+08:00')
        self.assertEqual(self.ok(app_client.request('GET', younger_photo['file_url'])).content, younger_original)

        # Both transports must scope the same account per request, rather than
        # storing the selected baby in a shared login session.
        for identity, expected_name, expected_ids in (
                (older, '测试姐姐', {photo['id']}),
                (younger, '测试弟弟', {younger_photo['id']}),
                (older, '测试姐姐', {photo['id']})):
            with self.subTest(transport='cookie', baby=expected_name):
                headers = {'X-FAM-Child': identity}
                profile = self.ok(client.request('GET', '/api/profile', headers=headers)).json()
                self.assertEqual((profile['child_id'], profile['child_name'], profile['counts']['photos']),
                                 (identity, expected_name, 1))
                page = self.ok(client.request('GET', '/api/admin/media', headers=headers)).json()
                self.assertEqual({item['id'] for item in page['items']}, expected_ids)
                self.assertEqual(self.ok(client.request('GET', '/api/admin/overview', headers=headers)).json()['profile']['child_id'], identity)
                cookie_index = self.ok(client.request('GET', '/api/album-locator', headers=headers)).json()
                expected_date = photo['album_date'] if identity == older else younger_photo['album_date']
                self.assertEqual((cookie_index['total'], cookie_index['has_birthday']), (1, True))
                self.assertEqual([(item['date'], item['count']) for item in cookie_index['items']], [(expected_date, 1)])
                self.assertEqual(cookie_index['items'][0]['age_months'], 8 if identity == older else 16)
                self.assertEqual(cookie_index['items'][0]['month_label'], '8个月' if identity == older else '1岁4个月')
                jumped = self.ok(client.request('GET', '/api/media?' + urlencode({
                    'start_date': expected_date, 'limit': 1}), headers=headers)).json()
                self.assertEqual({item['id'] for item in jumped['items']}, expected_ids)
                self.assertFalse(jumped['has_previous'])
            with self.subTest(transport='bearer', baby=expected_name):
                app_client.child_id = identity
                profile = self.ok(app_client.request('GET', '/api/profile')).json()
                self.assertEqual((profile['child_id'], profile['counts']['photos']), (identity, 1))
                page = self.ok(app_client.request('GET', '/api/media')).json()
                self.assertEqual({item['id'] for item in page['items']}, expected_ids)
                self.assertEqual(self.ok(app_client.request('GET', '/api/album-locator')).json(), cookie_index)
                self.assertEqual(self.ok(app_client.request('GET', '/api/album-locator?kind=video')).json()['items'], [])
                jumped = self.ok(app_client.request('GET', '/api/media?' + urlencode({
                    'start_date': expected_date, 'limit': 1}))).json()
                self.assertEqual({item['id'] for item in jumped['items']}, expected_ids)
                other_date = younger_photo['album_date'] if identity == older else photo['album_date']
                self.ok(app_client.request('GET', '/api/media?' + urlencode({'start_date': other_date})), 404)
                self.ok(client.request('GET', '/api/media?' + urlencode({'start_date': other_date}), headers=headers), 404)
        self.ok(client.request('GET', younger_photo['file_url'], headers=older_headers), 400)
        self.ok(client.request('GET', '/api/media/' + younger_photo['id'] + '/file', headers=older_headers), 404)

        # A baby administrator remains a normal family account. Knowing the
        # other baby's ID or a valid media URL must not grant access.
        invitation = self.ok(client.request('POST', '/api/invitations', body={
            'name': '只照顾姐姐的家人', 'role': 'admin', 'child_ids': [older],
            'expires_hours': 24}, headers=older_headers)).json()
        restricted = LocalClient(client.address)
        self.addCleanup(restricted.close)
        accepted = self.ok(restricted.request('POST', '/api/invitations/accept', body={
            'code': invitation['code'], 'username': 'http.relative',
            'password': 'synthetic-relative-password', 'name': '只照顾姐姐的家人'})).json()
        restricted.token = accepted['token']
        restricted.child_id = older
        relative_id = accepted['member']['id']
        self.assertFalse(accepted['member']['is_family_admin'])
        self.assertEqual(self.ok(restricted.request('GET', '/api/me')).json()['role'], 'admin')
        visible = self.ok(restricted.request('GET', '/api/children')).json()['items']
        self.assertEqual([child['id'] for child in visible], [older])
        self.ok(restricted.request('GET', '/api/admin/overview'), 403)
        self.ok(restricted.request('POST', '/api/admin/session', body={
            'username': 'http.relative', 'password': 'synthetic-relative-password'}), 403)
        for path in ('/api/profile', '/api/media', '/api/timeline', '/api/album-locator', younger_photo['file_url'], younger_photo['thumbnail_url']):
            self.ok(restricted.request('GET', path, headers=younger_headers), 403)
        self.ok(restricted.request('GET', '/api/media/' + younger_photo['id']), 404)
        self.assertEqual(self.ok(restricted.request('GET', photo['file_url'])).content, original)
        restricted_index = self.ok(restricted.request('GET', '/api/album-locator')).json()
        self.assertEqual([item['date'] for item in restricted_index['items']], [photo['album_date']])
        restricted_position = self.ok(restricted.request('GET', '/api/media?' + urlencode({
            'start_date': photo['album_date'], 'limit': 1}))).json()['previous_cursor']
        self.ok(restricted.request('GET', '/api/media?' + urlencode({'start_date': younger_photo['album_date']})), 404)

        # Link a single original into both babies; a duplicate import also
        # keeps one media identity, the original EXIF time and original bytes.
        app_client.child_id = older
        shared = self.ok(app_client.request('POST', '/api/media/' + photo['id'] + '/children',
                                           body={'child_ids': [younger]})).json()
        self.assertEqual((shared['id'], shared['shared_count'], shared['captured_at']),
                         (photo['id'], 2, photo['captured_at']))
        app_client.child_id = younger
        duplicate_job = self.ok(client.request('POST', '/api/admin/imports', body={
            'name': '多宝宝合照重复导入', 'source': 'upload', 'child_ids': [older, younger]},
            headers=older_headers)).json()
        duplicate_path = '/api/admin/imports/' + duplicate_job['id']
        self.ok(client.upload(duplicate_path + '/files', '姐弟合照再次加入.jpg', original, {
            'fallback_captured_at': '2026-01-01T00:00:00+08:00'}))
        self.ok(client.request('POST', duplicate_path + '/start', headers=older_headers))
        duplicate_result = self.wait_job(client, duplicate_path)
        self.assertEqual((duplicate_result['imported'], duplicate_result['duplicates'], duplicate_result['failed']), (0, 1, 0))
        self.assertEqual(set(duplicate_result['child_ids']), {older, younger})
        duplicate = self.ok(app_client.request('GET', '/api/media/' + photo['id'])).json()
        self.assertEqual((duplicate['id'], duplicate['captured_at']), (photo['id'], photo['captured_at']))
        # The same original has different ages for the two babies. Both cookie
        # and bearer navigation must use the requested baby's own birthday.
        for transport in (client, app_client):
            with self.subTest(navigation='cookie' if transport is client else 'bearer'):
                index = self.ok(transport.request('GET', '/api/album-locator', headers=younger_headers)).json()
                self.assertEqual([(item['date'], item['count']) for item in index['items']],
                                 [(younger_photo['album_date'], 1), (photo['album_date'], 1)])
                self.assertEqual((index['items'][1]['year'], index['items'][1]['month_label']), (-1, '出生前'))
                filtered = self.ok(transport.request('GET', '/api/album-locator?kind=photo&month=2023-06', headers=younger_headers)).json()
                self.assertEqual((filtered['total'], filtered['items'][0]['date']), (1, younger_photo['album_date']))
                older_page = self.ok(transport.request('GET', '/api/media?' + urlencode({
                    'start_date': photo['album_date'], 'limit': 1}), headers=younger_headers)).json()
                self.assertEqual([item['id'] for item in older_page['items']], [photo['id']])
                self.assertEqual((older_page['total'], older_page['has_previous'], older_page['next_cursor']), (2, True, None))
                newer_page = self.ok(transport.request('GET', '/api/media?' + urlencode({
                    'newer_cursor': older_page['previous_cursor'], 'limit': 1}), headers=younger_headers)).json()
                self.assertEqual([item['id'] for item in newer_page['items']], [younger_photo['id']])
                self.assertFalse(newer_page['has_previous'])
                returned = self.ok(transport.request('GET', '/api/media?' + urlencode({
                    'cursor': newer_page['next_cursor'], 'limit': 1}), headers=younger_headers)).json()
                self.assertEqual([item['id'] for item in returned['items']], [photo['id']])
        self.ok(restricted.request('PATCH', '/api/media/' + photo['id'], body={'description': '不应跨宝宝修改'}), 403)
        self.ok(client.request('POST', '/api/admin/media/batch', body={
            'ids': [photo['id']], 'action': 'edit', 'description': '姐姐和弟弟的合照'}, headers=older_headers))
        for identity, expected_count in ((older, 1), (younger, 2)):
            headers = {'X-FAM-Child': identity}
            detail = self.ok(client.request('GET', '/api/media/' + photo['id'], headers=headers)).json()
            self.assertEqual((detail['description'], detail['captured_at']), ('姐姐和弟弟的合照', photo['captured_at']))
            self.assertEqual(self.ok(client.request('GET', detail['file_url'], headers=headers)).content, original)
            self.assertEqual(self.ok(client.request('GET', '/api/profile', headers=headers)).json()['counts']['photos'], expected_count)

        # Each baby's comments and recycle bin are local to that baby, while
        # the shared original remains readable in the other baby's album.
        for identity, text in ((older, '姐姐相册的留言'), (younger, '弟弟相册的留言')):
            app_client.child_id = identity
            self.ok(app_client.request('POST', '/api/media/' + photo['id'] + '/comments', body={'text': text}))
        for identity, expected_text in ((older, '姐姐相册的留言'), (younger, '弟弟相册的留言')):
            app_client.child_id = identity
            comments = self.ok(app_client.request('GET', '/api/media/' + photo['id'] + '/comments')).json()['items']
            self.assertEqual([comment['text'] for comment in comments], [expected_text])
        self.ok(client.request('POST', '/api/admin/media/batch', body={
            'ids': [photo['id']], 'action': 'trash'}, headers=older_headers))
        self.assertEqual(self.ok(client.request('GET', '/api/profile', headers=older_headers)).json()['counts']['photos'], 0)
        self.assertEqual(self.ok(client.request('GET', '/api/profile', headers=younger_headers)).json()['counts']['photos'], 2)
        self.assertEqual(self.ok(client.request('GET', '/api/album-locator', headers=older_headers)).json()['items'], [])
        self.assertEqual(self.ok(client.request('GET', '/api/album-locator', headers=younger_headers)).json()['total'], 2)
        self.ok(client.request('GET', '/api/media?' + urlencode({'start_date': photo['album_date']}), headers=older_headers), 404)
        app_client.child_id = younger
        self.assertEqual(self.ok(app_client.request('GET', duplicate['file_url'])).content, original)
        self.ok(client.request('POST', '/api/admin/media/batch', body={
            'ids': [photo['id']], 'action': 'restore'}, headers=older_headers))
        self.assertEqual(self.ok(client.request('GET', '/api/profile', headers=older_headers)).json()['counts']['photos'], 1)

        # Reuse the exact old bearer token and old URLs after revocation; no
        # logout or token refresh is allowed to mask authorization problems.
        old_file_url, old_thumbnail_url = photo['file_url'], photo['thumbnail_url']
        self.ok(restricted.request('GET', old_thumbnail_url))
        self.ok(client.request('PUT', f'/api/children/{older}/members/{relative_id}',
                               body={'role': None}, headers=older_headers))
        self.ok(restricted.request('GET', '/api/me'))
        self.assertEqual(self.ok(restricted.request('GET', '/api/children')).json()['items'], [])
        for path in (old_file_url, old_thumbnail_url, '/api/media/' + photo['id'], '/api/profile',
                     '/api/album-locator', '/api/media?' + urlencode({'start_date': photo['album_date']}),
                     '/api/media?' + urlencode({'newer_cursor': restricted_position})):
            self.ok(restricted.request('GET', path), 403)
        restricted.child_id = None
        self.ok(restricted.request('GET', old_file_url), 403)
        self.ok(restricted.request('GET', old_thumbnail_url), 403)
        self.ok(restricted.request('GET', '/api/album-locator'), 403)
        self.ok(client.request('PUT', f'/api/children/{older}/members/{relative_id}',
                               body={'role': 'viewer'}, headers=older_headers))
        self.assertEqual(self.ok(restricted.request('GET', old_file_url)).content, original)
        restricted.close()
        app_client.close()
        return older, younger, younger_photo, younger_original

    def test_admin_http_and_background_lifecycle(self):
        with tempfile.TemporaryDirectory(prefix='fam-admin-http-') as temporary:
            root = Path(temporary).resolve()
            with running_album(root) as address:
                client = LocalClient(address)
                self.addCleanup(client.close)
                self.ok(client.request('GET', '/api/admin/session'), 401)
                html = self.ok(client.request('GET', '/admin/'))
                self.assertIn('text/html', html.headers['Content-Type'])
                self.assertIn('FAM宝贝相册'.encode(), html.content)
                self.assertIn("script-src 'self'", html.headers['Content-Security-Policy'])
                resources = sorted((SERVER / 'web').rglob('*'))
                self.assertTrue((SERVER / 'web' / 'admin.css').is_file(), '后台样式文件尚未生成')
                seen = set()
                for resource in resources:
                    if resource.suffix not in {'.js', '.mjs', '.css'}:
                        continue
                    seen.add(resource.suffix)
                    response = self.ok(client.request('GET', '/admin/' + resource.relative_to(SERVER / 'web').as_posix()))
                    expected_type = 'text/css' if resource.suffix == '.css' else 'javascript'
                    self.assertIn(expected_type, response.headers['Content-Type'])
                    self.assertEqual(response.content, resource.read_bytes())
                self.assertEqual(seen, {'.js', '.mjs', '.css'})
                login = self.login(client, 'admin', 'admin')
                self.assertTrue(login.json()['password_change_required'])
                self.assertIn('HttpOnly', login.headers['Set-Cookie'])
                self.assertIn('SameSite=strict', login.headers['Set-Cookie'])
                self.ok(client.request('GET', '/api/admin/overview'), 403)
                self.ok(client.request('POST', '/api/admin/account', body={
                    'current_password': 'admin', 'new_password': PASSWORD}, csrf=False), 403)
                changed = self.ok(client.request('POST', '/api/admin/account', body={
                    'current_password': 'admin', 'new_password': PASSWORD, 'new_username': USERNAME})).json()
                self.assertFalse(changed['password_change_required'])
                client.csrf = changed['csrf']
                self.assertEqual(changed['member']['username'], USERNAME)
                self.ok(client.request('PUT', '/api/profile', body={'name': '不应保存'}, csrf=False), 403)
                self.ok(client.request('PUT', '/api/profile', body={'name': '本机冒烟测试家庭'}))
                self.ok(client.request('GET', '/api/admin/overview'))

                original = jpeg((184, 89, 61), '2020:09:08 14:15:16')
                photo = self.ok(client.upload('/api/upload', '测试上传照片.jpg', original)).json()
                self.assertEqual(datetime.fromisoformat(photo['captured_at']).isoformat(), '2020-09-08T14:15:16+08:00')
                self.assertEqual(self.ok(client.request('GET', photo['file_url'])).content, original)
                self.ok(client.request('GET', photo['thumbnail_url']))
                older, younger, younger_photo, younger_original = self.exercise_two_children(client, photo, original)

                job = self.ok(client.request('POST', '/api/admin/imports', body={
                    'name': '关闭连接后继续导入', 'source': 'upload'})).json()
                job_path = '/api/admin/imports/' + job['id']
                imported_originals = []
                for number in range(4):
                    content = jpeg((70 + number * 24, 120, 170))
                    imported_originals.append(content)
                    self.ok(client.upload(job_path + '/files', f'测试导入照片{number + 1}.jpg', content,
                                          {'fallback_captured_at': '2025-05-06T12:13:14+08:00'}))
                self.ok(client.request('POST', job_path + '/start'))
                client.close()
                # There is no connected page or HTTP client during this period.
                time.sleep(1.2)
                client = LocalClient(address)
                self.addCleanup(client.close)
                self.login(client)
                finished = self.wait_job(client, job_path)
                self.assertEqual((finished['imported'], finished['failed']), (4, 0))
                media = self.ok(client.request('GET', '/api/admin/media')).json()['items']
                self.assertEqual(len(media), 5)
                imported = [item for item in media if item['id'] != photo['id']]
                self.assertTrue(all(item['captured_at'] == '2021-04-05T10:20:30+08:00' for item in imported))
                originals = {item['id']: self.ok(client.request('GET', item['file_url'])).content for item in media}
                self.assertEqual(set(originals.values()), set([original] + imported_originals))
                self.assertEqual(self.ok(client.request('GET', '/api/profile', headers={
                    'X-FAM-Child': younger})).json()['counts']['photos'], 2)
                # Four existing imports share one date. Reverse HTTP paging
                # must fetch the nearest two, then the remaining two, without
                # skipping same-day items or mixing in the other baby's date.
                index = self.ok(client.request('GET', '/api/album-locator')).json()
                self.assertEqual([(item['date'], item['count']) for item in index['items']],
                                 [('2021-04-05', 4), ('2020-09-08', 1)])
                self.assertEqual(index['total'], 5)
                day_page = self.ok(client.request('GET', '/api/media?date=2021-04-05')).json()
                jumped = self.ok(client.request('GET', '/api/media?start_date=2020-09-08&limit=1')).json()
                self.assertEqual([item['id'] for item in jumped['items']], [photo['id']])
                nearest = self.ok(client.request('GET', '/api/media?' + urlencode({
                    'newer_cursor': jumped['previous_cursor'], 'limit': 2}))).json()
                remaining = self.ok(client.request('GET', '/api/media?' + urlencode({
                    'newer_cursor': nearest['previous_cursor'], 'limit': 2}))).json()
                self.assertTrue(nearest['has_previous'])
                self.assertFalse(remaining['has_previous'])
                self.assertEqual([item['id'] for item in remaining['items'] + nearest['items']],
                                 [item['id'] for item in day_page['items']])
                self.assertEqual((nearest['total'], remaining['total']), (5, 5))
                all_originals = {**originals, younger_photo['id']: younger_original}

                backup = self.ok(client.request('POST', '/api/admin/backups', body={'mode': 'full'}), 202).json()
                backup_path = '/api/admin/backups/' + backup['id']
                backup = self.wait_job(client, backup_path)
                download = self.ok(client.request('GET', backup['download_url']))
                self.assertEqual(download.headers['Cache-Control'], 'no-store')
                with zipfile.ZipFile(io.BytesIO(download.content)) as archive:
                    manifest = json.loads(archive.read('manifest.json'))
                    self.assertIn('storage.key', archive.namelist())
                    self.assertEqual(len(manifest['media']), 6)
                    archived = {hashlib.sha256(archive.read(item['path'])).digest() for item in manifest['media']}
                    self.assertEqual(archived, {hashlib.sha256(content).digest() for content in all_originals.values()})
                self.assertTrue(self.ok(client.request('POST', backup_path + '/verify')).json()['verified'])
                preview = self.ok(client.request('POST', backup_path + '/restore-preview')).json()
                self.assertEqual((preview['photos'], preview['videos']), (6, 0))
                restored = self.ok(client.request('POST', backup_path + '/restore', body={
                    'current_password': PASSWORD})).json()
                folder = Path(restored['directory']).resolve()
                self.assertTrue(folder.is_relative_to(root / 'data' / 'restored'))
                self.assertTrue((folder / 'data' / 'album.sqlite').is_file())
                old_cookie = next(cookie.value for cookie in client.cookies if cookie.name == 'fam_admin_session')
                with running_album(root, data=folder / 'data', library=folder / 'library') as restored_address:
                    restored_client = LocalClient(restored_address)
                    try:
                        self.ok(restored_client.request('GET', '/api/admin/session', headers={
                            'Cookie': 'fam_admin_session=' + old_cookie}), 401)
                        self.assertFalse(self.login(restored_client).json()['password_change_required'])
                        copied = self.ok(restored_client.request('GET', '/api/admin/media')).json()['items']
                        self.assertEqual({item['id']: item['captured_at'] for item in copied},
                                         {item['id']: item['captured_at'] for item in media})
                        for item in copied:
                            self.assertEqual(self.ok(restored_client.request('GET', item['file_url'])).content,
                                             originals[item['id']])
                        restored_children = self.ok(restored_client.request('GET', '/api/children')).json()['items']
                        self.assertEqual({child['id'] for child in restored_children}, {older, younger})
                        younger_copy = self.ok(restored_client.request('GET', '/api/admin/media', headers={
                            'X-FAM-Child': younger})).json()['items']
                        self.assertEqual({item['id'] for item in younger_copy}, {photo['id'], younger_photo['id']})
                        self.assertEqual({item['id']: item['captured_at'] for item in younger_copy}, {
                            photo['id']: photo['captured_at'], younger_photo['id']: younger_photo['captured_at']})
                        for item in younger_copy:
                            self.assertEqual(self.ok(restored_client.request('GET', item['file_url'])).content,
                                             all_originals[item['id']])
                        self.assertEqual(self.ok(restored_client.request('GET', '/api/admin/imports')).json()['items'], [])
                        self.assertEqual(self.ok(restored_client.request('GET', '/api/admin/backups')).json()['items'], [])
                    finally:
                        restored_client.close()
                # Restoring a copy must not disturb the original service or session.
                self.assertEqual(self.ok(client.request('GET', photo['file_url'])).content, original)
                self.ok(client.request('DELETE', '/api/admin/session'))
                self.ok(client.request('GET', '/api/admin/overview'), 401)
                client.close()


if __name__ == '__main__':
    unittest.main(verbosity=2)
