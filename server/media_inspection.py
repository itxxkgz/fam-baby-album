"""Inspect downloaded media without rewriting the original file bytes."""

from __future__ import annotations

from copy import deepcopy
from datetime import date, datetime, time, timedelta, timezone
from functools import lru_cache
import os
from pathlib import Path
import re
import shutil
import subprocess

from PIL import Image


LOCAL_TIMEZONE = timezone(timedelta(hours=8))
VIDEO_EXTENSIONS = {".mp4", ".mov", ".m4v", ".3gp", ".webm", ".mkv", ".avi"}
ORIGINAL_TIME_SOURCES = {"照片原拍摄时间", "视频原拍摄时间"}
EMBEDDED_TIME_SOURCES = ORIGINAL_TIME_SOURCES | {"照片数字化时间", "照片内嵌修改时间", "视频内嵌创建时间"}


@lru_cache(maxsize=1)
def _ffmpeg_path() -> str:
    installed = shutil.which("ffmpeg")
    if installed:
        return installed
    import imageio_ffmpeg

    return imageio_ffmpeg.get_ffmpeg_exe()


def _parse_datetime(value: str) -> datetime | None:
    text = value.strip().strip("\x00")
    if re.match(r"^\d{4}:\d{2}:\d{2}", text):
        text = text[:10].replace(":", "-") + text[10:]
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.year < 1900:
        return None
    return parsed


def _assign_time(result: dict, captured: datetime, source: str, assumed_timezone: bool) -> None:
    if captured.tzinfo is None:
        captured = captured.replace(tzinfo=LOCAL_TIMEZONE)
    local = captured.astimezone(LOCAL_TIMEZONE)
    result.update(
        capture_time=local.isoformat(timespec="microseconds" if local.microsecond else "seconds"),
        capture_date=local.date().isoformat(),
        time_source=source,
        time_precision="second",
        timezone_assumed=assumed_timezone,
    )


def _apply_album_date(result: dict, album_date: str | date | datetime | None) -> None:
    if result["capture_date"] or album_date is None:
        return
    if isinstance(album_date, datetime):
        _assign_time(result, album_date, "相册记录时间", album_date.tzinfo is None)
        return
    if isinstance(album_date, date):
        calendar_date = album_date
    else:
        value = str(album_date).strip().replace("/", "-")
        if re.fullmatch(r"\d{4}-\d{1,2}-\d{1,2}", value):
            year, month, day = map(int, value.split("-"))
            calendar_date = date(year, month, day)
        else:
            parsed = _parse_datetime(value)
            if parsed is None:
                raise ValueError("相册日期格式无法识别")
            _assign_time(result, parsed, "相册记录时间", parsed.tzinfo is None)
            return
    result.update(
        capture_date=calendar_date.isoformat(),
        time_source="相册记录日期",
        time_precision="date",
        timezone_assumed=True,
    )


def normalize_capture_time(metadata: dict, album_time: str | date | datetime | None = None) -> dict:
    """Return a deep copy applying capture-time policy 2 without reading media.

    Explicit original capture tags outrank an exact album timestamp. The exact
    album timestamp outranks generic digitization, modification and container
    creation tags. The original embedded candidate remains available for audit.
    A date-only album value is used only if no embedded timestamp is available.
    """
    normalized = deepcopy(metadata)
    source = metadata.get("time_source")
    embedded_time = metadata.get("embedded_time")
    embedded_source = metadata.get("embedded_time_source")
    assumed_timezone = metadata.get("embedded_timezone_assumed", False)
    if not embedded_time and source in EMBEDDED_TIME_SOURCES:
        embedded_time = metadata.get("capture_time")
        embedded_source = source
        assumed_timezone = metadata.get("timezone_assumed", False)
    normalized.update(
        embedded_time=embedded_time,
        embedded_time_source=embedded_source,
        embedded_timezone_assumed=assumed_timezone,
        time_policy_version=2,
    )
    embedded_candidate = _parse_datetime(embedded_time) if embedded_time else None
    album_value = album_time
    if album_value is None:
        album_value = metadata.get("album_time") or metadata.get("album_date")
    if album_value is None and source in ("相册记录时间", "相册记录日期"):
        album_value = metadata.get("capture_time") or metadata.get("capture_date")
    album_candidate = {
        "capture_time": None, "capture_date": None, "time_source": "拍摄时间未知",
        "time_precision": "unknown", "timezone_assumed": False,
    }
    _apply_album_date(album_candidate, album_value)
    if embedded_candidate and embedded_source in ORIGINAL_TIME_SOURCES:
        _assign_time(normalized, embedded_candidate, embedded_source, assumed_timezone)
    elif album_candidate["time_precision"] == "second":
        normalized.update(album_candidate)
    elif embedded_candidate:
        _assign_time(normalized, embedded_candidate, embedded_source, assumed_timezone)
    elif album_candidate["capture_date"]:
        normalized.update(album_candidate)
    return normalized


def _image_metadata(path: Path, result: dict) -> None:
    with Image.open(path) as picture:
        result.update(width=picture.width, height=picture.height, format=picture.format)
        exif = picture.getexif()
        details = exif.get_ifd(34665)
        for field, offset_field, label in (
            (36867, 36881, "照片原拍摄时间"),
            (36868, 36882, "照片数字化时间"),
            (306, 36880, "照片内嵌修改时间"),
        ):
            value = details.get(field) or exif.get(field)
            if not isinstance(value, str):
                continue
            parsed = _parse_datetime(value)
            if parsed is None:
                continue
            offset = details.get(offset_field) or exif.get(offset_field)
            assumed = True
            if isinstance(offset, str) and re.fullmatch(r"[+-]\d{2}:\d{2}", offset.strip()):
                sign = 1 if offset[0] == "+" else -1
                hours, minutes = map(int, offset[1:].split(":"))
                parsed = parsed.replace(tzinfo=timezone(sign * timedelta(hours=hours, minutes=minutes)))
                assumed = False
            _assign_time(result, parsed, label, assumed)
            break
        result["recorded_original_width"] = details.get(40962) or exif.get(256)
        result["recorded_original_height"] = details.get(40963) or exif.get(257)
    with Image.open(path) as picture:
        picture.verify()
    with Image.open(path) as picture:
        frame_count = getattr(picture, "n_frames", 1)
        missing_auxiliary_frames = 0
        decoded_frames = 0
        for frame in range(frame_count):
            if picture.format == "MPO" and frame > 0:
                # Some cloud-resized JPEGs retain the camera's multi-picture
                # index. A positive offset beyond EOF proves that auxiliary
                # picture is unavailable. The primary picture must still decode.
                entry = picture.mpinfo[0xB002][frame]
                offset = entry.get("DataOffset")
                if isinstance(offset, int) and offset >= result["bytes"]:
                    missing_auxiliary_frames += 1
                    continue
            picture.seek(frame)
            picture.load()
            decoded_frames += 1
    result["image_frames"] = frame_count
    result["decoded_image_frames"] = decoded_frames
    result["unavailable_auxiliary_frames"] = missing_auxiliary_frames
    if missing_auxiliary_frames:
        result["warnings"].append(
            f"主照片检查通过，原文件标记的另外 {missing_auxiliary_frames} 幅附图缺少可读取的数据。"
        )
        result["validation"] = "primary_image_decode"
    else:
        result["validation"] = "full_decode"


def _video_metadata(path: Path, result: dict, full_decode: bool) -> None:
    command = [
        _ffmpeg_path(), "-hide_banner", "-nostdin", "-xerror", "-i", str(path),
        "-map", "0:v:0", "-map", "0:a:0?",
    ]
    if not full_decode:
        command.extend(["-t", "0.1"])
    command.extend(["-f", "null", "-"])
    completed = subprocess.run(
        command, capture_output=True, encoding="utf-8", errors="replace",
        timeout=1800 if full_decode else 60,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    information = completed.stderr.split("Stream mapping:", 1)[0]
    video_line = next((line for line in information.splitlines() if "Stream #0:" in line and "Video:" in line), "")
    size = re.search(r"\b(\d{2,6})x(\d{2,6})\b", video_line)
    if size:
        result.update(width=int(size[1]), height=int(size[2]))
    duration = re.search(r"Duration: (\d+):(\d+):(\d+(?:\.\d+)?)", information)
    if duration:
        result["duration"] = int(duration[1]) * 3600 + int(duration[2]) * 60 + float(duration[3])
    result["has_audio"] = bool(re.search(r"Stream #0:.*?Audio:", information))
    for tag, label in (
        ("com.apple.quicktime.creationdate", "视频原拍摄时间"),
        ("creation_time", "视频内嵌创建时间"),
    ):
        match = re.search(r"^\s*" + re.escape(tag) + r"\s*:\s*(.*?)\s*$", information, re.MULTILINE)
        if match:
            captured = _parse_datetime(match[1])
            if captured:
                _assign_time(result, captured, label, captured.tzinfo is None)
                break
    if completed.returncode or not size:
        result["errors"].append("视频解码检查失败，文件可能不完整或格式暂不支持")
    result["validation"] = "full_decode" if full_decode else "sample_decode"


def inspect_media(path: str | Path, album_date: str | date | datetime | None = None,
                  full_decode: bool = False) -> dict:
    """Return JSON-safe metadata. A date-only fallback never invents a capture time.

    Image pixels are fully decoded. Video checks decode the beginning by default,
    or every audio/video frame when full_decode=True. Unsupported/broken files
    return valid=False with human-readable errors.
    """
    source = Path(path)
    result = {
        "kind": "video" if source.suffix.lower() in VIDEO_EXTENSIONS else "photo",
        "extension": source.suffix.lower(), "bytes": 0,
        "width": None, "height": None, "duration": None, "has_audio": False,
        "capture_time": None, "capture_date": None, "time_source": "拍摄时间未知",
        "time_precision": "unknown", "timezone_assumed": False,
        "validation": "not_checked", "valid": False, "errors": [], "warnings": [],
    }
    try:
        result["bytes"] = source.stat().st_size
        if result["bytes"] == 0:
            raise ValueError("文件为空")
        if result["kind"] == "video":
            _video_metadata(source, result, full_decode)
        else:
            _image_metadata(source, result)
    except FileNotFoundError:
        result["errors"].append("文件不存在或缺少视频检查程序")
    except subprocess.TimeoutExpired:
        result["errors"].append("媒体文件检查超时，需要重试")
    except (OSError, ValueError, SyntaxError, ImportError, RuntimeError, Image.DecompressionBombError) as error:
        result["errors"].append("文件为空" if str(error) == "文件为空" else "媒体文件无法完整读取")
    try:
        result = normalize_capture_time(result, album_date)
    except ValueError:
        result["errors"].append("相册日期格式无法识别")
    result["valid"] = result["validation"] != "not_checked" and not result["errors"]
    return result


def preserve_file_time(path: str | Path, metadata: dict) -> bool:
    """Set filesystem modification time only. File bytes and embedded tags stay intact.

    For date-only records, noon in China is a sorting convention recorded through
    time_precision='date', while capture_time remains None.
    """
    if metadata.get("capture_time"):
        captured = datetime.fromisoformat(metadata["capture_time"])
    elif metadata.get("capture_date"):
        captured = datetime.combine(date.fromisoformat(metadata["capture_date"]), time(12), LOCAL_TIMEZONE)
    else:
        return False
    original = Path(path).stat()
    os.utime(path, (original.st_atime, captured.timestamp()))
    return True


def friendly_filename(metadata: dict, sequence: int = 1, extension: str | None = None) -> str:
    """Generate a readable filename, with sequence supplied by the caller for uniqueness."""
    if sequence < 1:
        raise ValueError("文件顺序必须大于零")
    label = "视频" if metadata.get("kind") == "video" else "照片"
    if metadata.get("capture_time"):
        captured = datetime.fromisoformat(metadata["capture_time"])
        prefix = f"{captured.year}年{captured.month}月{captured.day}日 {captured:%H时%M分%S秒}"
    elif metadata.get("capture_date"):
        captured_date = date.fromisoformat(metadata["capture_date"])
        prefix = f"{captured_date.year}年{captured_date.month}月{captured_date.day}日"
    else:
        prefix = "拍摄日期待确认"
    suffix = (extension or metadata.get("extension") or (".mp4" if label == "视频" else ".jpg")).lower()
    if not re.fullmatch(r"\.[a-z0-9]{1,8}", suffix):
        raise ValueError("文件扩展名无法识别")
    return f"{prefix}-{label}{sequence:04d}{suffix}"
