"""Private local/NAS and S3-compatible storage, with stable per-media locations.

Only deployment administrators can extend local roots or permit private endpoints.
Credentials never leave this module; media are always served by the album API.
"""
from __future__ import annotations

import contextlib
import hashlib
import ipaddress
import json
import os
import re
import shutil
import socket
import tempfile
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

import boto3
import alibabacloud_oss_v2 as oss
from alibabacloud_oss_v2.credentials import StaticCredentialsProvider
from alibabacloud_oss_v2.crc import Crc64
from alibabacloud_oss_v2.exceptions import ServiceError as OSSServiceError
from alibabacloud_oss_v2.transport import RequestsHttpClient
import requests
from boto3.s3.transfer import TransferConfig
from botocore.config import Config
from botocore.exceptions import BotoCoreError, ClientError
from cryptography.fernet import Fernet, InvalidToken

CHUNK = 512 * 1024
LABELS = {"local": "服务器硬盘 / NAS", "oss": "阿里云 OSS", "cos": "腾讯云 COS", "s3": "通用 S3"}
LOCATION_FIELDS = ("provider", "local_path", "endpoint", "region", "bucket", "prefix", "addressing_style")


class StorageError(Exception):
    def __init__(self, message: str, status_code: int = 400):
        super().__init__(message)
        self.message = message
        self.status_code = status_code


def _now():
    return datetime.now(timezone.utc).isoformat()


def _safe_key(key: str):
    if not key or len(key) > 900 or "\\" in key or any(p in ("", ".", "..") for p in key.split("/")):
        raise StorageError("文件位置不正确，请检查存储配置")
    if any(ord(c) < 32 for c in key) or ":" in key or key.startswith("/"):
        raise StorageError("文件位置不正确，请检查存储配置")
    return key


def _contained(root: Path, relative: str):
    path = (root / _safe_key(relative)).resolve()
    if not path.is_relative_to(root.resolve()):
        raise StorageError("文件位置超出了允许的目录")
    return path


class EndpointPolicy:
    """Validate the actual destination, including every redirect and socket connect.

    The optional allowlist consists of complete origins, e.g.
    ALBUM_STORAGE_ALLOWED_ENDPOINTS=http://127.0.0.1:18333,https://nas.example:9443
    An explicit origin permits private addresses only at that exact host and port.
    """
    def __init__(self, endpoint: str, bucket: str = ""):
        self.endpoint = self.normalize(endpoint)
        self.origin = self._origin(self.endpoint)
        self.bucket = bucket
        self.allowed = set()
        for value in os.getenv("ALBUM_STORAGE_ALLOWED_ENDPOINTS", "").split(","):
            if value.strip():
                self.allowed.add(self._origin(self.normalize(value.strip())))

    @staticmethod
    def normalize(value):
        try:
            parsed = urlsplit(value.strip())
            port = parsed.port
            if (parsed.scheme not in ("https", "http") or not parsed.hostname or parsed.username
                    or parsed.password or parsed.query or parsed.fragment or parsed.path not in ("", "/")
                    or any(c in value for c in ("\\", "\r", "\n", "\t", " "))):
                raise ValueError()
            host = parsed.hostname.encode("idna").decode("ascii").lower()
            if "%" in host or host.endswith("."):
                raise ValueError()
            shown = f"[{host}]" if ":" in host else host
            return f"{parsed.scheme}://{shown}" + (f":{port}" if port else "")
        except (ValueError, UnicodeError, AttributeError):
            raise StorageError("存储地址格式不正确，请填写不含路径的完整服务地址") from None

    @staticmethod
    def _origin(url):
        p = urlsplit(url)
        return p.scheme, p.hostname.lower(), p.port or (443 if p.scheme == "https" else 80)

    def check_destination(self, host, port, scheme=None):
        scheme = scheme or self.origin[0]
        origin = (scheme, host.lower(), int(port))
        permitted_hosts = {self.origin[1]}
        if self.bucket:
            permitted_hosts.add(self.bucket + "." + self.origin[1])
        if host.lower() not in permitted_hosts or origin[0] != self.origin[0] or origin[2] != self.origin[2]:
            raise StorageError("存储服务尝试跳转到其他地址，请检查服务地址和区域")
        explicit = self.origin in self.allowed
        if scheme != "https" and not explicit:
            raise StorageError("存储服务需要使用 HTTPS；自建服务须先由部署管理员允许")
        try:
            addresses = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
        except OSError:
            raise StorageError("无法找到存储服务器，请检查服务地址和网络", 502) from None
        if not addresses:
            raise StorageError("无法找到存储服务器，请检查服务地址和网络", 502)
        for _, _, _, _, address in addresses:
            ip = ipaddress.ip_address(address[0])
            if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped:
                ip = ip.ipv4_mapped
            # Metadata/link-local addresses are forbidden even with an allowlist.
            if (ip.is_link_local or ip.is_multicast or ip.is_unspecified or str(ip) in ("fd00:ec2::254", "100.100.100.200")
                    or (not explicit and not ip.is_global)):
                raise StorageError("此存储地址不在部署允许的范围内，请联系服务器管理员")
        return addresses

    def validate(self):
        self.check_destination(self.origin[1], self.origin[2])

    def before_send(self, request, **kwargs):
        p = urlsplit(request.url)
        self.check_destination(p.hostname, p.port or (443 if p.scheme == "https" else 80), p.scheme)

    def session(self):
        # Pin each new TCP connection to a checked address, closing the DNS
        # rebinding gap between URL validation and urllib3's socket creation.
        from botocore.awsrequest import AWSHTTPConnection, AWSHTTPSConnection
        from botocore.httpsession import URLLib3Session
        from botocore.awsrequest import AWSHTTPConnectionPool, AWSHTTPSConnectionPool
        policy = self

        class CheckedConnect:
            def _new_conn(conn):
                addresses = policy.check_destination(conn.host, conn.port)
                last_error = None
                for family, socktype, proto, _, address in addresses:
                    sock = None
                    try:
                        sock = socket.socket(family, socktype, proto)
                        sock.settimeout(conn.timeout)
                        for option in conn.socket_options or []:
                            sock.setsockopt(*option)
                        if conn.source_address:
                            sock.bind(conn.source_address)
                        sock.connect(address)
                        return sock
                    except OSError as exc:
                        last_error = exc
                        if sock:
                            sock.close()
                raise last_error or OSError("Storage connection failed")

        class CheckedHTTP(CheckedConnect, AWSHTTPConnection):
            pass

        class CheckedHTTPS(CheckedConnect, AWSHTTPSConnection):
            pass

        class HTTPPool(AWSHTTPConnectionPool):
            ConnectionCls = CheckedHTTP

        class HTTPSPool(AWSHTTPSConnectionPool):
            ConnectionCls = CheckedHTTPS

        session = URLLib3Session(timeout=(8, 45), max_pool_connections=8, proxies={})
        session._pool_classes_by_scheme = {"http": HTTPPool, "https": HTTPSPool}
        session._manager.pool_classes_by_scheme = session._pool_classes_by_scheme
        return session


def _storage_exception(exc):
    # Official SDKs wrap service errors in operation/upload errors. Unwrap
    # without returning their raw message, endpoint or signed request details.
    for _ in range(8):
        if callable(getattr(exc, "unwrap", None)):
            nested = exc.unwrap()
        else:
            nested = None
        if nested is None or nested is exc:
            break
        exc = nested
    if isinstance(exc, StorageError):
        return exc
    if isinstance(exc, ClientError):
        code = str(exc.response.get("Error", {}).get("Code", ""))
        if code in ("AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch", "InvalidToken", "ExpiredToken", "403"):
            return StorageError("存储服务拒绝访问，请检查密钥以及上传、读取和删除权限", 502)
        if code in ("NoSuchBucket", "NoSuchKey", "404", "NotFound"):
            return StorageError("未找到存储空间或文件，请检查存储配置", 404)
    if isinstance(exc, OSSServiceError):
        if exc.status_code == 404:
            return StorageError("未找到存储空间或文件，请检查存储配置", 404)
        if exc.status_code in (401, 403):
            return StorageError("阿里云拒绝访问，请检查密钥以及上传、读取和删除权限", 502)
    return StorageError("暂时无法访问存储服务，请检查网络、存储空间和权限后重试", 502)


class LocalAdapter:
    def __init__(self, root: Path):
        self.root = root.resolve()

    def put(self, key, source, mime, sha256):
        target = _contained(self.root, key)
        target.parent.mkdir(parents=True, exist_ok=True)
        # Resolve again after creating directories, including mounted NAS paths.
        target = _contained(self.root, key)
        temporary = target.with_name(target.name + "." + uuid.uuid4().hex + ".part")
        try:
            with open(source, "rb") as src, open(temporary, "xb") as dest:
                shutil.copyfileobj(src, dest, CHUNK)
                dest.flush()
                os.fsync(dest.fileno())
            timestamps = Path(source).stat()
            os.utime(temporary, ns=(timestamps.st_atime_ns, timestamps.st_mtime_ns))
            os.replace(temporary, target)
        finally:
            temporary.unlink(missing_ok=True)

    def stat(self, key):
        try:
            return _contained(self.root, key).stat().st_size
        except FileNotFoundError:
            raise StorageError("没有找到这份原文件，请检查存储目录是否可用", 404) from None

    def read(self, key, first=0, last=None):
        with open(_contained(self.root, key), "rb") as stream:
            stream.seek(first)
            remaining = None if last is None else last - first + 1
            while remaining is None or remaining > 0:
                chunk = stream.read(CHUNK if remaining is None else min(CHUNK, remaining))
                if not chunk:
                    break
                yield chunk
                if remaining is not None:
                    remaining -= len(chunk)
            if remaining not in (None, 0):
                raise StorageError("文件读取中断，请稍后重试", 502)

    def delete(self, key):
        _contained(self.root, key).unlink(missing_ok=True)


class S3Adapter:
    def __init__(self, config, credentials):
        self.config = config
        self.policy = EndpointPolicy(config["endpoint"], config["bucket"])
        self.policy.validate()
        options = Config(signature_version="s3v4", connect_timeout=8, read_timeout=45,
                         retries={"mode": "standard", "max_attempts": 2},
                         proxies={}, s3={"addressing_style": config["addressing_style"], "use_accelerate_endpoint": False},
                         request_checksum_calculation="when_required", response_checksum_validation="when_required")
        self.client = boto3.client("s3", endpoint_url=config["endpoint"], region_name=config["region"] or "us-east-1",
                                   aws_access_key_id=credentials["access_key_id"],
                                   aws_secret_access_key=credentials["secret_access_key"],
                                   aws_session_token=credentials.get("session_token") or None, config=options)
        self.client.meta.events.register("before-send.s3", self.policy.before_send)
        old_session = self.client._endpoint.http_session
        self.client._endpoint.http_session = self.policy.session()
        old_session.close()

    def _key(self, key):
        key = _safe_key(key)
        prefix = self.config.get("prefix", "")
        if prefix and not key.startswith(prefix + "/"):
            raise StorageError("文件位置超出了这个存储位置的目录")
        return key

    def put(self, key, source, mime, sha256):
        self.client.upload_file(str(source), self.config["bucket"], self._key(key),
                                ExtraArgs={"ContentType": mime, "Metadata": {"sha256": sha256}},
                                # CRT uses its own transport; keep all requests on
                                # the SDK session that checks each destination.
                                Config=TransferConfig(preferred_transfer_client="classic"))

    def stat(self, key):
        return int(self.client.head_object(Bucket=self.config["bucket"], Key=self._key(key))["ContentLength"])

    def read(self, key, first=0, last=None):
        kwargs = {"Bucket": self.config["bucket"], "Key": self._key(key)}
        if first or last is not None:
            kwargs["Range"] = f"bytes={first}-" + (str(last) if last is not None else "")
        response = self.client.get_object(**kwargs)
        body = response["Body"]
        total = 0
        try:
            if first or last is not None:
                content_range = response.get("ContentRange", "")
                if not content_range.startswith(f"bytes {first}-"):
                    raise StorageError("存储服务未按要求分段读取文件，请检查服务兼容性", 502)
                if last is not None and int(response["ContentLength"]) != last - first + 1:
                    raise StorageError("存储服务返回的文件片段不完整，请稍后重试", 502)
            while True:
                chunk = body.read(CHUNK)
                if not chunk:
                    break
                total += len(chunk)
                yield chunk
            if total != int(response["ContentLength"]):
                raise StorageError("文件读取中断，请稍后重试", 502)
        finally:
            body.close()

    def delete(self, key):
        self.client.delete_object(Bucket=self.config["bucket"], Key=self._key(key))

    def close(self):
        self.client.close()


class OSSAdapter(S3Adapter):
    """Native OSS V4; does not require an S3-compatibility account switch."""
    def __init__(self, config, credentials):
        self.config = config
        self.policy = EndpointPolicy(config["endpoint"], config["bucket"])
        self.policy.validate()
        policy = self.policy

        class CheckedSession(requests.Session):
            def send(session, request, **kwargs):
                policy.before_send(request)
                kwargs.update(allow_redirects=False, proxies={})
                return super().send(request, **kwargs)

        self.session = CheckedSession()
        self.session.trust_env = False
        http_adapter = requests.adapters.HTTPAdapter(pool_connections=8, pool_maxsize=8, max_retries=0)
        checked_transport = policy.session()
        http_adapter.poolmanager.pool_classes_by_scheme = checked_transport._pool_classes_by_scheme
        checked_transport.close()
        self.session.mount("https://", http_adapter)
        self.session.mount("http://", http_adapter)
        self.transport = RequestsHttpClient(session=self.session, connect_timeout=8, readwrite_timeout=45,
                                            enabled_redirect=False, proxy_host={})
        self.client = oss.Client(oss.Config(
            region=config["region"], endpoint=config["endpoint"], signature_version="v4",
            credentials_provider=StaticCredentialsProvider(credentials["access_key_id"], credentials["secret_access_key"],
                                                            credentials.get("session_token") or None),
            http_client=self.transport, use_path_style=False, enabled_redirect=False,
            retry_max_attempts=2, connect_timeout=8, readwrite_timeout=45,
        ))

    def put(self, key, source, mime, sha256):
        # The official uploader handles multipart and aborts unfinished parts.
        # CRC64 validation stays enabled; SHA256 is independently recorded too.
        uploader = oss.Uploader(self.client, part_size=8 * 1024 * 1024, parallel_num=3,
                                leave_parts_on_error=False, enable_checkpoint=False)
        uploader.upload_file(oss.PutObjectRequest(bucket=self.config["bucket"], key=self._key(key),
                                                  content_type=mime, metadata={"sha256": sha256}), str(source))

    def stat(self, key):
        return int(self.client.head_object(oss.HeadObjectRequest(bucket=self.config["bucket"], key=self._key(key))).content_length)

    def read(self, key, first=0, last=None):
        ranged = first or last is not None
        header = f"bytes={first}-" + (str(last) if last is not None else "") if ranged else None
        result = self.client.get_object(oss.GetObjectRequest(bucket=self.config["bucket"], key=self._key(key), range_header=header))
        body, total = result.body, 0
        crc = Crc64(0) if not ranged and result.hash_crc64 is not None else None
        try:
            if ranged:
                if not (result.content_range or "").startswith(f"bytes {first}-"):
                    raise StorageError("存储服务未按要求分段读取文件，请检查服务兼容性", 502)
                if last is not None and int(result.content_length) != last - first + 1:
                    raise StorageError("存储服务返回的文件片段不完整，请稍后重试", 502)
            for chunk in body.iter_bytes(block_size=CHUNK):
                total += len(chunk)
                if crc:
                    crc.update(chunk)
                yield chunk
            if total != int(result.content_length):
                raise StorageError("文件读取中断，请稍后重试", 502)
            if crc and str(crc.sum64()) != str(result.hash_crc64):
                raise StorageError("原文件校验未通过，请检查存储中的文件是否被修改", 502)
        finally:
            body.close()

    def delete(self, key):
        self.client.delete_object(oss.DeleteObjectRequest(bucket=self.config["bucket"], key=self._key(key)))

    def close(self):
        self.transport.close()
        self.session.close()


class StorageManager:
    def __init__(self, data: Path, library: Path, database):
        self.data, self.library, self.database = Path(data).resolve(), Path(library).resolve(), database
        self.fernet = None
        self.allowed_roots = []

    def initialize(self):
        self.data.mkdir(parents=True, exist_ok=True)
        (self.data / "uploads").mkdir(exist_ok=True)
        (self.data / "temporary").mkdir(exist_ok=True)
        raw_roots = os.getenv("ALBUM_STORAGE_ROOTS", "")
        try:
            roots = json.loads(raw_roots) if raw_roots.startswith("[") else raw_roots.split(os.pathsep)
            self.allowed_roots = [Path(p).expanduser().resolve() for p in roots if p]
        except (ValueError, TypeError):
            raise StorageError("服务器允许的存储目录配置不正确", 500) from None
        # Built-in uploads always remain readable regardless of added NAS roots.
        self.allowed_roots.insert(0, self.data / "uploads")
        key = os.getenv("ALBUM_STORAGE_KEY", "").encode("ascii")
        if not key:
            keyfile = self.data / "storage.key"
            if not keyfile.exists():
                with self.database() as db:
                    table = db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='storage_configs'").fetchone()
                    if table and db.execute("SELECT count(*) FROM storage_configs WHERE credentials_ciphertext IS NOT NULL").fetchone()[0]:
                        raise StorageError("存储密钥文件缺失，请恢复 storage.key 的服务器备份后重启", 500)
            try:
                fd = os.open(keyfile, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            except FileExistsError:
                key = keyfile.read_bytes().strip()
            else:
                key = Fernet.generate_key()
                with os.fdopen(fd, "wb") as stream:
                    stream.write(key)
                    stream.flush()
                    os.fsync(stream.fileno())
            try:
                keyfile.chmod(0o600)
            except OSError:
                pass
        try:
            self.fernet = Fernet(key)
        except (ValueError, TypeError):
            raise StorageError("存储密钥文件无法使用，请从备份恢复；不要删除后重新生成", 500) from None
        with self.database() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS storage_configs (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, provider TEXT NOT NULL,
                config_json TEXT NOT NULL, credentials_ciphertext TEXT,
                is_default INTEGER NOT NULL DEFAULT 0, tested_at TEXT,
                created_at TEXT NOT NULL, updated_at TEXT NOT NULL)""")
            if "write_started" not in {row[1] for row in db.execute("PRAGMA table_info(storage_configs)")}:
                db.execute("ALTER TABLE storage_configs ADD COLUMN write_started INTEGER NOT NULL DEFAULT 0")
            columns = {row[1] for row in db.execute("PRAGMA table_info(media)")}
            for column in ("storage_id", "object_key"):
                if column not in columns:
                    db.execute(f"ALTER TABLE media ADD COLUMN {column} TEXT")
            default = {"provider": "local", "local_path": str(self.data / "uploads"), "endpoint": "", "region": "",
                       "bucket": "", "prefix": "", "addressing_style": "auto"}
            count = db.execute("SELECT count(*) FROM storage_configs").fetchone()[0]
            db.execute("INSERT OR IGNORE INTO storage_configs (id,name,provider,config_json,credentials_ciphertext,is_default,tested_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                       ("local-default", "自己的服务器", "local", json.dumps(default), None, int(not count), _now(), _now(), _now()))

    def _row(self, storage_id):
        with self.database() as db:
            row = db.execute("SELECT * FROM storage_configs WHERE id=?", (storage_id,)).fetchone()
            if not row:
                raise StorageError("这个存储位置已不存在，请刷新后重试", 404)
            return dict(row)

    def _credentials(self, row):
        if not row.get("credentials_ciphertext"):
            return {}
        try:
            return json.loads(self.fernet.decrypt(row["credentials_ciphertext"].encode("ascii")))
        except (InvalidToken, ValueError, TypeError):
            raise StorageError("无法读取存储密钥，请恢复服务器密钥备份或重新填写云存储密钥", 500) from None

    def _count(self, storage_id):
        with self.database() as db:
            if storage_id == "local-default":
                return db.execute("SELECT count(*) FROM media WHERE storage_id=? OR (storage_id IS NULL AND root_kind='uploads')", (storage_id,)).fetchone()[0]
            return db.execute("SELECT count(*) FROM media WHERE storage_id=?", (storage_id,)).fetchone()[0]

    def _dto(self, row):
        cfg = json.loads(row["config_json"])
        return dict(cfg, id=row["id"], name=row["name"], provider_label=LABELS[row["provider"]],
                    is_default=bool(row["is_default"]), has_credentials=bool(row["credentials_ciphertext"]),
                    tested=bool(row["tested_at"]), status_label="连接正常" if row["tested_at"] else "尚未测试",
                    media_count=self._count(row["id"]))

    def list_configs(self):
        with self.database() as db:
            rows = list(db.execute("SELECT * FROM storage_configs ORDER BY created_at,id"))
        return {"items": [self._dto(dict(row)) for row in rows], "allowed_local_roots": [str(p) for p in self.allowed_roots]}

    def _normalize(self, values, old=None):
        cfg = json.loads(old["config_json"]) if old else {}
        for field in LOCATION_FIELDS:
            if field in values and values[field] is not None:
                cfg[field] = str(values[field]).strip()
        provider = cfg.get("provider", "local")
        if provider not in LABELS:
            raise StorageError("请选择支持的存储类型")
        cfg["provider"] = provider
        for field in LOCATION_FIELDS[1:]:
            cfg.setdefault(field, "")
        cfg["addressing_style"] = cfg["addressing_style"] or "auto"
        if provider == "local":
            if not cfg["local_path"]:
                raise StorageError("请填写服务器上的存储目录")
            path = Path(cfg["local_path"]).expanduser().resolve()
            if not any(path.is_relative_to(root) for root in self.allowed_roots):
                raise StorageError("这个目录不在允许范围内，请先由部署管理员添加允许的存储目录")
            cfg.update(local_path=str(path), endpoint="", region="", bucket="", prefix="", addressing_style="auto")
        else:
            cfg["local_path"] = ""
            if not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", cfg["bucket"]) or ".." in cfg["bucket"]:
                raise StorageError("请填写正确的存储桶名称")
            if not re.fullmatch(r"[A-Za-z0-9-]{0,50}", cfg["region"]):
                raise StorageError("请填写正确的存储区域")
            if provider in ("oss", "cos"):
                if not cfg["region"]:
                    raise StorageError("请填写云存储区域")
                if not cfg["endpoint"]:
                    cfg["endpoint"] = (f"https://oss-{cfg['region']}.aliyuncs.com" if provider == "oss"
                                       else f"https://cos.{cfg['region']}.myqcloud.com")
                cfg["addressing_style"] = "virtual"
            if cfg["addressing_style"] not in ("auto", "path", "virtual"):
                raise StorageError("请选择正确的寻址方式")
            cfg["endpoint"] = EndpointPolicy.normalize(cfg["endpoint"])
            EndpointPolicy(cfg["endpoint"], cfg["bucket"]).validate()
            cfg["prefix"] = cfg["prefix"].strip("/")
            if cfg["prefix"]:
                _safe_key(cfg["prefix"])
        return cfg

    def save_config(self, values, storage_id=None):
        old = self._row(storage_id) if storage_id else None
        name = str(values.get("name") or (old["name"] if old else "")).strip()
        if not name or len(name) > 80:
            raise StorageError("请为存储位置填写一个简短、容易识别的名称")
        cfg = self._normalize(values, old)
        if old and (self._count(storage_id) or old.get("write_started")) and cfg != json.loads(old["config_json"]):
            raise StorageError("这个位置已经保存了照片。请新建存储位置，避免原有照片无法打开", 409)
        credentials = self._credentials(old) if old else {}
        changed_secret = False
        for field in ("access_key_id", "secret_access_key", "session_token"):
            value = values.get(field)
            if value:
                if len(value) > 8192:
                    raise StorageError("存储密钥格式不正确")
                changed_secret |= value != credentials.get(field)
                credentials[field] = value
        if cfg["provider"] != "local" and not all(credentials.get(f) for f in ("access_key_id", "secret_access_key")):
            raise StorageError("请填写云存储的访问密钥和密钥内容")
        if cfg["provider"] == "local":
            credentials = {}
        cipher = self.fernet.encrypt(json.dumps(credentials).encode()).decode() if credentials else None
        tested = old["tested_at"] if old and cfg == json.loads(old["config_json"]) and not changed_secret else None
        storage_id = storage_id or uuid.uuid4().hex
        with self.database() as db:
            if old:
                db.execute("BEGIN IMMEDIATE")
                current_row = db.execute("SELECT * FROM storage_configs WHERE id=?", (storage_id,)).fetchone()
                if current_row is None:
                    raise StorageError("这个存储位置已不存在，请刷新后重试", 404)
                current = dict(current_row)
                if current["config_json"] != old["config_json"] or current["credentials_ciphertext"] != old["credentials_ciphertext"]:
                    raise StorageError("存储设置刚刚发生了变化，请刷新后重试", 409)
                if current["write_started"] and cfg != json.loads(current["config_json"]):
                    raise StorageError("这个位置已经开始保存照片。请新建存储位置，避免原有照片无法打开", 409)
                db.execute("UPDATE storage_configs SET name=?,provider=?,config_json=?,credentials_ciphertext=?,tested_at=?,updated_at=? WHERE id=?",
                           (name, cfg["provider"], json.dumps(cfg), cipher, tested, _now(), storage_id))
            else:
                db.execute("INSERT INTO storage_configs (id,name,provider,config_json,credentials_ciphertext,is_default,tested_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                           (storage_id, name, cfg["provider"], json.dumps(cfg), cipher, 0, tested, _now(), _now()))
        return self._dto(self._row(storage_id))

    @contextlib.contextmanager
    def _adapter(self, row):
        cfg = json.loads(row["config_json"])
        adapter = None
        try:
            if cfg["provider"] == "local":
                # Revalidate on use to catch changed mounts/symlinks.
                normalized = self._normalize(cfg, row)
                adapter = LocalAdapter(Path(normalized["local_path"]))
            elif cfg["provider"] == "oss":
                adapter = OSSAdapter(cfg, self._credentials(row))
            else:
                adapter = S3Adapter(cfg, self._credentials(row))
            yield adapter
        except Exception as exc:
            raise _storage_exception(exc) from None
        finally:
            if isinstance(adapter, S3Adapter):
                adapter.close()

    def test_config(self, storage_id):
        row = self._row(storage_id)
        cfg = json.loads(row["config_json"])
        key = "/".join(p for p in (cfg["prefix"], ".album-connection-test", uuid.uuid4().hex) if p)
        payload = os.urandom(4097)
        path = self.data / "temporary" / (uuid.uuid4().hex + ".test")
        path.write_bytes(payload)
        try:
            with self._adapter(row) as adapter:
                try:
                    adapter.put(key, path, "application/octet-stream", hashlib.sha256(payload).hexdigest())
                    if adapter.stat(key) != len(payload) or b"".join(adapter.read(key)) != payload:
                        raise StorageError("测试文件读取不完整，请检查存储服务", 502)
                    if b"".join(adapter.read(key, 11, 41)) != payload[11:42]:
                        raise StorageError("存储服务不支持分段读取，视频可能无法播放", 502)
                finally:
                    adapter.delete(key)
                # HEAD after delete distinguishes true cleanup from a no-op gateway.
                try:
                    adapter.stat(key)
                except Exception as exc:
                    if _storage_exception(exc).status_code != 404:
                        raise
                else:
                    raise StorageError("存储服务未能清理测试文件，请检查删除权限", 502)
        except Exception:
            with self.database() as db:
                db.execute("UPDATE storage_configs SET tested_at=NULL WHERE id=? AND config_json=? AND credentials_ciphertext IS ?",
                           (storage_id, row["config_json"], row["credentials_ciphertext"]))
            raise
        finally:
            path.unlink(missing_ok=True)
        with self.database() as db:
            result = db.execute("UPDATE storage_configs SET tested_at=?,updated_at=? WHERE id=? AND config_json=? AND credentials_ciphertext IS ?",
                                (_now(), _now(), storage_id, row["config_json"], row["credentials_ciphertext"]))
            if result.rowcount != 1:
                raise StorageError("测试期间存储设置发生了变化，请重新测试连接", 409)
        return {"ok": True, "message": "连接正常，上传、读取和清理均已通过", "storage": self._dto(self._row(storage_id))}

    def set_default(self, storage_id):
        with self.database() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM storage_configs WHERE id=?", (storage_id,)).fetchone()
            if not row:
                raise StorageError("这个存储位置已不存在，请刷新后重试", 404)
            if not row["tested_at"]:
                raise StorageError("请先测试连接，通过后再设为默认上传位置")
            db.execute("UPDATE storage_configs SET is_default=0")
            db.execute("UPDATE storage_configs SET is_default=1 WHERE id=?", (storage_id,))
        return self._dto(self._row(storage_id))

    def delete_config(self, storage_id):
        with self.database() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM storage_configs WHERE id=?", (storage_id,)).fetchone()
            if not row:
                raise StorageError("这个存储位置已不存在，请刷新后重试", 404)
            used = db.execute("SELECT count(*) FROM media WHERE storage_id=?", (storage_id,)).fetchone()[0]
            if row["is_default"] or row["write_started"] or used or storage_id == "local-default":
                raise StorageError("仍在使用的存储位置不能删除", 409)
            db.execute("DELETE FROM storage_configs WHERE id=?", (storage_id,))
        return {"ok": True, "message": "已移除这个存储位置"}

    def store_upload(self, temporary, suffix, mime, sha256):
        with self.database() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM storage_configs WHERE is_default=1").fetchone()
            if row:
                # Reserve the location before I/O, so an edit concurrent with the
                # first upload cannot strand that upload between adapters.
                db.execute("UPDATE storage_configs SET write_started=1 WHERE id=?", (row["id"],))
        if not row:
            raise StorageError("请先设置默认上传位置", 409)
        row = dict(row)
        cfg = json.loads(row["config_json"])
        if not re.fullmatch(r"\.[a-zA-Z0-9]{1,12}", suffix):
            suffix = ".bin"
        key = "/".join(p for p in (cfg["prefix"], datetime.now().strftime("%Y/%m"), uuid.uuid4().hex + suffix.lower()) if p)
        source_size = Path(temporary).stat().st_size
        with self._adapter(row) as adapter:
            try:
                adapter.put(key, temporary, mime, sha256)
                if adapter.stat(key) != source_size:
                    raise StorageError("文件未能完整保存，请重试", 502)
            except Exception:
                with contextlib.suppress(Exception):
                    adapter.delete(key)
                raise
        return {"storage_id": row["id"], "object_key": key, "root_kind": "storage", "relative_path": key}

    def delete_blob(self, storage_id, object_key):
        with self._adapter(self._row(storage_id)) as adapter:
            adapter.delete(_safe_key(object_key))

    def _legacy_path(self, row):
        root = self.library if row["root_kind"] == "library" else self.data / "uploads"
        return _contained(root, row["relative_path"])

    def stat_media(self, row):
        row = dict(row)
        if not row.get("storage_id"):
            try:
                return self._legacy_path(row).stat().st_size
            except FileNotFoundError:
                raise StorageError("没有找到这份原文件，请检查存储目录是否可用", 404) from None
        with self._adapter(self._row(row["storage_id"])) as adapter:
            return adapter.stat(_safe_key(row["object_key"]))

    def open_media(self, row, first=0, last=None):
        row = dict(row)
        if first < 0 or (last is not None and last < first):
            raise StorageError("读取范围不正确", 416)
        if not row.get("storage_id"):
            root = self.library if row["root_kind"] == "library" else self.data / "uploads"
            yield from LocalAdapter(root).read(row["relative_path"], first, last)
        else:
            with self._adapter(self._row(row["storage_id"])) as adapter:
                yield from adapter.read(_safe_key(row["object_key"]), first, last)

    @contextlib.contextmanager
    def materialize(self, row):
        row = dict(row)
        if not row.get("storage_id"):
            yield self._legacy_path(row)
            return
        config = json.loads(self._row(row["storage_id"])["config_json"])
        if config["provider"] == "local":
            # Ensure local mount still falls under deployment's allowed roots.
            config = self._normalize(config)
            yield _contained(Path(config["local_path"]), row["object_key"])
            return
        suffix = Path(row.get("filename") or row["object_key"]).suffix
        fd, name = tempfile.mkstemp(dir=self.data / "temporary", suffix=suffix)
        path = Path(name)
        digest, size = hashlib.sha256(), 0
        try:
            with os.fdopen(fd, "wb") as stream:
                for chunk in self.open_media(row):
                    stream.write(chunk)
                    digest.update(chunk)
                    size += len(chunk)
            if row.get("size_bytes") is not None and size != row["size_bytes"]:
                raise StorageError("原文件未能完整读取，请稍后重试", 502)
            if row.get("sha256") and digest.hexdigest() != row["sha256"]:
                raise StorageError("原文件校验未通过，请检查存储中的文件是否被修改", 502)
            yield path
        finally:
            path.unlink(missing_ok=True)
