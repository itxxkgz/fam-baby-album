"""Native OSS SDK contract over an isolated loopback protocol fixture.

This validates HTTP requests, signing choice and streaming without a cloud account.
It is not a substitute for an acceptance test against an actual Alibaba bucket.
"""
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
import socket
import threading
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, unquote, urlsplit
import uuid

from alibabacloud_oss_v2.crc import Crc64
from test_storage import StorageFixture
from storage import StorageError


class NativeOSSFixture:
    def __init__(self):
        self.objects = {}
        self.parts = {}
        self.calls = []
        self.fail_parts = False
        owner = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):
                pass

            def respond(self, status, body=b"", headers=None):
                self.send_response(status)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("x-oss-request-id", "synthetic-request")
                for key, value in (headers or {}).items():
                    if key.lower() != "content-length":
                        self.send_header(key, str(value))
                # HEAD needs original size rather than the empty response body.
                if self.command == "HEAD" and headers and "Content-Length" in headers:
                    # Override the last header in the buffered response.
                    self._headers_buffer = [h for h in self._headers_buffer if not h.lower().startswith(b"content-length:")]
                    self.send_header("Content-Length", headers["Content-Length"])
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(body)

            def handle_request(self):
                parsed = urlsplit(self.path)
                key = unquote(parsed.path.lstrip("/"))
                query = parse_qs(parsed.query, keep_blank_values=True)
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                owner.calls.append((self.command, self.path, dict(self.headers)))
                auth = self.headers.get("Authorization", "")
                if not auth.startswith("OSS4-HMAC-SHA256 ") or "/cn-hangzhou/oss/aliyun_v4_request" not in auth:
                    return self.respond(403, b"<Error><Code>AccessDenied</Code><Message>native signature required</Message></Error>")
                if self.command == "POST" and "uploads" in query:
                    upload_id = uuid.uuid4().hex
                    owner.parts[upload_id] = {}
                    return self.respond(200, f"<InitiateMultipartUploadResult><Bucket>album-contract</Bucket><Key>{key}</Key><UploadId>{upload_id}</UploadId></InitiateMultipartUploadResult>".encode())
                if self.command == "PUT" and "uploadId" in query:
                    if owner.fail_parts:
                        return self.respond(403, b"<Error><Code>AccessDenied</Code><Message>synthetic part failure</Message></Error>")
                    owner.parts[query["uploadId"][0]][int(query["partNumber"][0])] = body
                    return self.respond(200, headers=owner.headers(body))
                if self.command == "POST" and "uploadId" in query:
                    parts = owner.parts.pop(query["uploadId"][0])
                    data = b"".join(parts[n] for n in sorted(parts))
                    owner.objects[key] = data
                    return self.respond(200, f"<CompleteMultipartUploadResult><Location>local</Location><Bucket>album-contract</Bucket><Key>{key}</Key><ETag>synthetic</ETag></CompleteMultipartUploadResult>".encode(), owner.headers(data))
                if self.command == "DELETE" and "uploadId" in query:
                    owner.parts.pop(query["uploadId"][0], None)
                    return self.respond(204)
                if self.command == "PUT":
                    owner.objects[key] = body
                    return self.respond(200, headers=owner.headers(body))
                if self.command == "DELETE":
                    owner.objects.pop(key, None)
                    return self.respond(204)
                if key not in owner.objects:
                    return self.respond(404, b"<Error><Code>NoSuchKey</Code><Message>synthetic missing</Message></Error>")
                data = owner.objects[key]
                headers = owner.headers(data)
                if self.command == "HEAD":
                    return self.respond(200, headers=dict(headers, **{"Content-Length": str(len(data))}))
                if self.command == "GET":
                    range_header = self.headers.get("Range")
                    if range_header:
                        first, last = range_header.removeprefix("bytes=").split("-")
                        first, last = int(first), int(last) if last else len(data)-1
                        headers["Content-Range"] = f"bytes {first}-{last}/{len(data)}"
                        return self.respond(206, data[first:last+1], headers)
                    return self.respond(200, data, headers)
                self.respond(405)

            do_PUT = do_POST = do_GET = do_HEAD = do_DELETE = handle_request

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.endpoint = f"http://oss.test:{self.server.server_port}"

    @staticmethod
    def headers(data):
        crc = Crc64(0)
        crc.update(data)
        return {"ETag": '"' + hashlib.md5(data).hexdigest() + '"', "x-oss-hash-crc64ecma": str(crc.sum64()),
                "Content-Type": "application/octet-stream"}

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)


class OSSStorageTests(StorageFixture, unittest.TestCase):
    def setUp(self):
        super().setUp()
        self.fixture = NativeOSSFixture()
        os.environ["ALBUM_STORAGE_ALLOWED_ENDPOINTS"] = self.fixture.endpoint
        original_resolve = socket.getaddrinfo
        def resolve(host, port, *args, **kwargs):
            if host == "oss.test" or host.endswith(".oss.test"):
                host = "127.0.0.1"
            return original_resolve(host, port, *args, **kwargs)
        self.resolver = patch("socket.getaddrinfo", resolve)
        self.resolver.start()

    def tearDown(self):
        self.resolver.stop()
        self.fixture.close()
        super().tearDown()

    def config(self):
        return self.manager.save_config({"name": "原生阿里云协议测试", "provider": "oss", "endpoint": self.fixture.endpoint,
                                         "region": "cn-hangzhou", "bucket": "album-contract", "prefix": "family",
                                         "access_key_id": "synthetic-access", "secret_access_key": "synthetic-secret"})

    def test_native_oss_roundtrip_range_checksum_and_multipart(self):
        self.assert_contract(self.config(), os.urandom(9 * 1024 * 1024 + 17))
        self.assertEqual(self.fixture.objects, {})
        self.assertEqual(self.fixture.parts, {})
        self.assertTrue(any("uploadId=" in path for _, path, _ in self.fixture.calls))
        for _, _, headers in self.fixture.calls:
            self.assertTrue(headers["Host"].startswith("album-contract.oss.test:"))
            self.assertTrue(headers["Authorization"].startswith("OSS4-HMAC-SHA256 "))
            self.assertNotIn("x-oss-object-acl", {h.lower() for h in headers})

    def test_native_oss_failed_multipart_is_aborted(self):
        cfg = self.config()
        self.manager.test_config(cfg["id"])
        self.manager.set_default(cfg["id"])
        self.fixture.fail_parts = True
        source, digest = self.source(os.urandom(9 * 1024 * 1024 + 17))
        with self.assertRaises(StorageError):
            self.manager.store_upload(source, ".jpg", "image/jpeg", digest)
        self.assertEqual(self.fixture.objects, {})
        self.assertEqual(self.fixture.parts, {})
        self.assertTrue(any(method == "DELETE" and "uploadId=" in path for method, path, _ in self.fixture.calls))
        self.assertTrue(source.exists())


if __name__ == "__main__":
    unittest.main()
