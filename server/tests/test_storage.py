"""Storage contracts; real S3 checks opt in with ALBUM_S3_TEST_FIXTURE.

python -m unittest discover -s server/tests -p test_storage.py -v
No tests read the real album or send media to external services.
"""
import contextlib
import hashlib
import json
import os
from pathlib import Path
import socket
import sqlite3
import sys
import tempfile
import unittest
from unittest.mock import patch, Mock
from urllib.parse import urlsplit
import uuid
import alibabacloud_oss_v2 as oss

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from storage import EndpointPolicy, LocalAdapter, S3Adapter, OSSAdapter, StorageError, StorageManager


class StorageFixture:
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.data = self.root / "data"
        self.library = self.root / "library"
        self.library.mkdir()
        self.nas = self.root / "nas"
        self.environment = patch.dict(os.environ, {"ALBUM_STORAGE_ROOTS": json.dumps([str(self.nas)]),
                                                   "ALBUM_STORAGE_ALLOWED_ENDPOINTS": "", "ALBUM_STORAGE_KEY": ""})
        self.environment.start()
        self.data.mkdir()
        with self.database() as db:
            db.execute("CREATE TABLE media (id TEXT PRIMARY KEY,root_kind TEXT,relative_path TEXT,filename TEXT,size_bytes INTEGER,sha256 TEXT)")
        self.manager = StorageManager(self.data, self.library, self.database)
        self.manager.initialize()

    def tearDown(self):
        self.environment.stop()
        self.temp.cleanup()

    @contextlib.contextmanager
    def database(self):
        conn = sqlite3.connect(self.data / "test.sqlite")
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    def source(self, payload=b"synthetic album bytes" * 37):
        path = self.root / "source.jpg"
        path.write_bytes(payload)
        return path, hashlib.sha256(payload).hexdigest()

    def make_local(self):
        return self.manager.save_config({"name": "家庭硬盘", "provider": "local", "local_path": str(self.nas / "photos")})

    def assert_contract(self, config, payload):
        result = self.manager.test_config(config["id"])
        self.assertTrue(result["ok"])
        self.manager.set_default(config["id"])
        source, digest = self.source(payload)
        blob = self.manager.store_upload(source, ".jpg", "image/jpeg", digest)
        self.assertEqual(source.read_bytes(), payload, "source is owned by upload endpoint")
        row = dict(blob, id="synthetic", filename="test.jpg", size_bytes=len(payload), sha256=digest)
        self.assertEqual(self.manager.stat_media(row), len(payload))
        self.assertEqual(b"".join(self.manager.open_media(row)), payload)
        self.assertEqual(b"".join(self.manager.open_media(row, 11, 32)), payload[11:33])
        self.assertEqual(b"".join(self.manager.open_media(row, len(payload)-9)), payload[-9:])
        with self.manager.materialize(row) as materialized:
            self.assertEqual(materialized.read_bytes(), payload)
            self.assertTrue(materialized.is_file())
        self.manager.delete_blob(blob["storage_id"], blob["object_key"])
        with self.assertRaises(StorageError):
            self.manager.stat_media(row)
        self.assertEqual(list((self.data / "temporary").iterdir()), [])
        return row

class StorageTests(StorageFixture, unittest.TestCase):
    def test_local_storage_contract(self):
        self.assert_contract(self.make_local(), os.urandom(2 * 1024 * 1024 + 17))

    def test_local_capture_file_time_is_preserved(self):
        src, digest = self.source()
        capture_timestamp = 1546290000
        os.utime(src, (capture_timestamp, capture_timestamp))
        blob = self.manager.store_upload(src, ".jpg", "image/jpeg", digest)
        with self.manager.materialize(blob) as path:
            self.assertEqual(int(path.stat().st_mtime), capture_timestamp)

    def test_location_reserved_before_media_record_is_inserted(self):
        cfg = self.make_local()
        self.manager.test_config(cfg["id"])
        self.manager.set_default(cfg["id"])
        src, digest = self.source()
        self.manager.store_upload(src, ".jpg", "image/jpeg", digest)
        # Simulates the gap while the endpoint inspects/inserts the media row.
        with self.assertRaises(StorageError):
            self.manager.save_config({"local_path": str(self.nas / "elsewhere")}, cfg["id"])
        self.manager.set_default("local-default")
        with self.assertRaises(StorageError):
            self.manager.delete_config(cfg["id"])

    def test_legacy_paths_and_default_switch(self):
        (self.library / "old.jpg").write_bytes(b"legacy library")
        (self.data / "uploads" / "old.mp4").write_bytes(b"legacy upload")
        for kind, name, payload in (("library", "old.jpg", b"legacy library"), ("uploads", "old.mp4", b"legacy upload")):
            row = {"root_kind": kind, "relative_path": name}
            self.assertEqual(self.manager.stat_media(row), len(payload))
            self.assertEqual(b"".join(self.manager.open_media(row)), payload)
            with self.manager.materialize(row) as path:
                self.assertEqual(path.read_bytes(), payload)
        src, digest = self.source()
        prior = self.manager.store_upload(src, ".jpg", "image/jpeg", digest)
        cfg = self.make_local()
        self.manager.test_config(cfg["id"])
        self.manager.set_default(cfg["id"])
        after = self.manager.store_upload(src, ".jpg", "image/jpeg", digest)
        self.assertNotEqual(prior["storage_id"], after["storage_id"])
        self.assertEqual(b"".join(self.manager.open_media(prior)), src.read_bytes())
        self.assertEqual(b"".join(self.manager.open_media(after)), src.read_bytes())

    def test_location_in_use_is_immutable_and_config_cannot_be_deleted(self):
        cfg = self.make_local()
        with self.database() as db:
            db.execute("INSERT INTO media(id,storage_id) VALUES (?,?)", ("one", cfg["id"]))
        with self.assertRaises(StorageError):
            self.manager.save_config({"local_path": str(self.nas / "moved")}, cfg["id"])
        with self.assertRaises(StorageError):
            self.manager.delete_config(cfg["id"])
        renamed = self.manager.save_config({"name": "新名字"}, cfg["id"])
        self.assertEqual(renamed["media_count"], 1)

    def test_paths_and_defaults(self):
        with self.assertRaises(StorageError):
            self.manager.save_config({"name": "越界", "local_path": str(self.root / "elsewhere")})
        cfg = self.make_local()
        with self.assertRaises(StorageError):
            self.manager.set_default(cfg["id"])
        for key in ("../escape", "a/../../escape", "C:/escape", "a\\escape", "/escape", "a//b"):
            with self.assertRaises(StorageError):
                self.manager.delete_blob("local-default", key)

    def test_failed_upload_does_not_return_record_and_removes_blob(self):
        original_put = LocalAdapter.put
        def interrupted(adapter, *args, **kwargs):
            original_put(adapter, *args, **kwargs)
            raise OSError("simulated disk disconnect")
        src, digest = self.source()
        with patch.object(LocalAdapter, "put", interrupted):
            with self.assertRaises(StorageError):
                self.manager.store_upload(src, ".jpg", "image/jpeg", digest)
        self.assertEqual(list((self.data / "uploads").rglob("*.jpg")), [])
        self.assertTrue(src.exists())

    def test_ciphertext_and_blank_secret_preservation(self):
        with patch.object(EndpointPolicy, "validate"):
            cfg = self.manager.save_config({"name": "云相册", "provider": "s3", "endpoint": "https://storage.example",
                                            "bucket": "album-test", "access_key_id": "private-access", "secret_access_key": "private-secret"})
            self.manager.save_config({"access_key_id": "", "secret_access_key": "", "name": "家庭云"}, cfg["id"])
        raw = self.manager._row(cfg["id"])
        self.assertNotIn("private-secret", json.dumps(raw))
        self.assertNotIn("private-access", json.dumps(raw))
        self.assertEqual(self.manager._credentials(raw)["secret_access_key"], "private-secret")
        self.assertNotIn("private-secret", json.dumps(self.manager.list_configs()))
        self.assertNotIn("access_key_id", json.dumps(self.manager.list_configs()))
        restarted = StorageManager(self.data, self.library, self.database)
        restarted.initialize()
        self.assertEqual(restarted._credentials(raw)["secret_access_key"], "private-secret")
        (self.data / "storage.key").unlink()
        with self.assertRaises(StorageError):
            restarted.initialize()
        self.assertFalse((self.data / "storage.key").exists(), "missing encryption key must not silently regenerate")

    def test_interrupted_cloud_materialization_removes_temporary_file(self):
        with patch.object(EndpointPolicy, "validate"):
            cfg = self.manager.save_config({"name": "测试云", "provider": "s3", "endpoint": "https://storage.example",
                                            "bucket": "album-test", "access_key_id": "test-access", "secret_access_key": "test-secret"})
        def interrupted(*args, **kwargs):
            yield b"partial download"
            raise StorageError("文件读取中断，请重试", 502)
        row = {"storage_id": cfg["id"], "object_key": "safe/test.jpg", "filename": "test.jpg"}
        with patch.object(self.manager, "open_media", interrupted):
            with self.assertRaises(StorageError):
                with self.manager.materialize(row):
                    self.fail("partial download cannot be inspected as a completed file")
        self.assertEqual(list((self.data / "temporary").iterdir()), [])

    def test_socket_connection_pins_checked_address(self):
        policy = EndpointPolicy("https://storage.example")
        addr = (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("93.184.216.34", 443))
        fake_socket = Mock()
        session = policy.session()
        connection_cls = session._pool_classes_by_scheme["https"].ConnectionCls
        connection = connection_cls("storage.example", port=443, timeout=1)
        with patch("storage.socket.getaddrinfo", return_value=[addr]) as resolve, patch("storage.socket.socket", return_value=fake_socket):
            self.assertIs(connection._new_conn(), fake_socket)
            resolve.assert_called_once()
            fake_socket.connect.assert_called_once_with(("93.184.216.34", 443))
        session.close()

    def test_endpoint_policy_rejects_unsafe_forms_and_private_destinations(self):
        os.environ["ALBUM_STORAGE_ALLOWED_ENDPOINTS"] = ""
        for endpoint in ("file:///tmp", "https://user:pass@example.com", "https://example.com/path", "https://example.com?key=x", "https://example.com#x", "https://example.com\\@127.0.0.1"):
            with self.assertRaises(StorageError):
                EndpointPolicy.normalize(endpoint)
        for endpoint in ("http://127.0.0.1:18333", "https://127.0.0.1", "https://169.254.169.254", "https://[::1]"):
            with self.assertRaises(StorageError):
                EndpointPolicy(endpoint).validate()
        with patch.dict(os.environ, {"ALBUM_STORAGE_ALLOWED_ENDPOINTS": "http://127.0.0.1:18333"}):
            policy = EndpointPolicy("http://127.0.0.1:18333")
            policy.validate()
            with self.assertRaises(StorageError):
                policy.check_destination("127.0.0.1", 18334)
            with self.assertRaises(StorageError):
                policy.check_destination("169.254.169.254", 18333)

    def test_public_hostname_resolving_private_is_rejected(self):
        with patch("storage.socket.getaddrinfo", return_value=[(socket.AF_INET,socket.SOCK_STREAM,6,"",("10.0.0.1",443))]):
            with self.assertRaises(StorageError):
                EndpointPolicy("https://storage.example").validate()
        with patch.dict(os.environ, {"ALBUM_STORAGE_ALLOWED_ENDPOINTS": "https://storage.example"}):
            for metadata_address in ("fd00:ec2::254", "100.100.100.200", "::ffff:169.254.169.254"):
                with patch("storage.socket.getaddrinfo", return_value=[(socket.AF_INET6,socket.SOCK_STREAM,6,"",(metadata_address,443))]):
                    with self.assertRaises(StorageError):
                        EndpointPolicy("https://storage.example").validate()

    def test_provider_endpoint_and_signature_contracts(self):
        providers = [("oss", "cn-hangzhou", "https://oss-cn-hangzhou.aliyuncs.com", "virtual"),
                     ("cos", "ap-guangzhou", "https://cos.ap-guangzhou.myqcloud.com", "virtual"),
                     ("s3", "us-east-1", "https://s3.example", "path")]
        for provider, region, endpoint, style in providers:
            with self.subTest(provider=provider), patch.object(EndpointPolicy, "validate"):
                cfg = self.manager.save_config({"name": "云存储", "provider": provider, "region": region,
                                                "endpoint": "" if provider != "s3" else endpoint,
                                                "bucket": "album-contract", "addressing_style": "path",
                                                "access_key_id": "example-access", "secret_access_key": "example-secret"})
                self.assertEqual(cfg["endpoint"], endpoint)
                self.assertEqual(cfg["addressing_style"], style)
                adapter = (OSSAdapter if provider == "oss" else S3Adapter)(cfg, {"access_key_id": "example-access", "secret_access_key": "example-secret"})
                try:
                    url = (adapter.client.presign(oss.GetObjectRequest(bucket=cfg["bucket"], key="safe/key.jpg")).url if provider == "oss"
                           else adapter.client.generate_presigned_url("get_object", Params={"Bucket": cfg["bucket"], "Key": "safe/key.jpg"}))
                    expected_host = urlsplit(endpoint).hostname if style == "path" else "album-contract." + urlsplit(endpoint).hostname
                    self.assertEqual(urlsplit(url).hostname, expected_host)
                    if provider == "oss":
                        self.assertIn("x-oss-signature-version=OSS4-HMAC-SHA256", url)
                        self.assertIn(region + "%2Foss%2Faliyun_v4_request", url)
                        self.assertFalse(adapter.session.trust_env)
                    else:
                        self.assertIn("X-Amz-Algorithm=AWS4-HMAC-SHA256", url)
                        self.assertIn(region + "%2Fs3%2Faws4_request", url)
                        self.assertEqual(adapter.client.meta.config.request_checksum_calculation, "when_required")
                        self.assertEqual(adapter.client.meta.config.proxies, {})
                finally:
                    adapter.close()


@unittest.skipUnless(os.getenv("ALBUM_S3_TEST_FIXTURE"), "real local S3 fixture not configured")
class RealS3Tests(StorageFixture, unittest.TestCase):
    def setUp(self):
        super().setUp()
        self.fixture = json.loads(Path(os.environ["ALBUM_S3_TEST_FIXTURE"]).read_text(encoding="utf-8"))
        # The integration fixture may only address the local computer.
        assert urlsplit(self.fixture["endpoint"]).hostname == "127.0.0.1"
        os.environ["ALBUM_STORAGE_ALLOWED_ENDPOINTS"] = self.fixture["endpoint"]

    def cloud_config(self, **overrides):
        values = {"name": "测试对象存储", "provider": "s3", "endpoint": self.fixture["endpoint"], "region": "us-east-1",
                  "bucket": self.fixture["bucket"], "prefix": "contract/" + uuid.uuid4().hex, "addressing_style": "path",
                  "access_key_id": self.fixture["access_key_id"], "secret_access_key": self.fixture["secret_access_key"]}
        values.update(overrides)
        return self.manager.save_config(values)

    def test_real_s3_storage_contract_and_multipart(self):
        # boto3 switches to multipart at 8 MiB; test above this threshold.
        self.assert_contract(self.cloud_config(), os.urandom(9 * 1024 * 1024 + 17))

    def test_wrong_key_and_readonly_bucket_fail_connection_test(self):
        for overrides in ({"secret_access_key": "intentionally-invalid-test-secret"}, self.fixture["readonly"]):
            with self.subTest(mode="invalid credentials or readonly"):
                cfg = self.cloud_config(**overrides)
                with self.assertRaises(StorageError):
                    self.manager.test_config(cfg["id"])
                self.assertFalse(self.manager._dto(self.manager._row(cfg["id"]))["tested"])
                with self.assertRaises(StorageError):
                    self.manager.set_default(cfg["id"])


if __name__ == "__main__":
    unittest.main()
