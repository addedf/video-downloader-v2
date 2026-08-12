import re
import time
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple
from urllib.parse import urlparse

URL_PATTERN = re.compile(r"https?://[^\s\"'<>]+")
URL_TRAILING_PUNCTUATION = ".,;，。；)"
TMP_SUFFIX = ".tmp"


def extract_first_url(text: str) -> Optional[str]:
    match = URL_PATTERN.search(text or "")
    if not match:
        return None
    return match.group(0).rstrip(URL_TRAILING_PUNCTUATION)


def iter_urls(value: Any) -> Iterable[str]:
    if isinstance(value, str):
        yield from URL_PATTERN.findall(value)
    elif isinstance(value, dict):
        for item in value.values():
            yield from iter_urls(item)
    elif isinstance(value, (list, tuple, set)):
        for item in value:
            yield from iter_urls(item)


def changed_files_since(
    root: Path,
    started_at: float,
    *,
    allowed_suffixes: Optional[set[str]] = None,
    ignored_suffixes: Optional[set[str]] = None,
    include_ignored: bool = False,
) -> List[str] | Tuple[List[str], List[str]]:
    changed: List[str] = []
    ignored: List[str] = []
    normalized_allowed = {item.lower() for item in allowed_suffixes or set()}
    normalized_ignored = {item.lower() for item in ignored_suffixes or set()}

    for path in root.rglob("*"):
        if not path.is_file():
            continue
        suffix = path.suffix.lower()
        try:
            if path.stat().st_mtime < started_at:
                continue
            if suffix in normalized_ignored:
                ignored.append(str(path))
                continue
            if normalized_allowed and suffix not in normalized_allowed:
                ignored.append(str(path))
                continue
            changed.append(str(path))
        except OSError:
            pass

    changed = sorted(changed)
    ignored = sorted(ignored)
    return (changed, ignored) if include_ignored else changed


def sum_file_bytes(files: List[str]) -> int:
    total = 0
    for file in files:
        try:
            total += Path(file).stat().st_size
        except OSError:
            pass
    return total


def sum_metric_duration(metrics: List[Dict[str, Any]]) -> int:
    return sum(int(item.get("duration_ms") or 0) for item in metrics)


def first_media_host(items: List[Any], fallback_suffixes: tuple[str, ...] = ()) -> Optional[str]:
    fallback: Optional[str] = None
    for item in items:
        for url in iter_urls(item):
            host = urlparse(url).hostname
            if not host:
                continue
            if fallback_suffixes and any(host.endswith(suffix) for suffix in fallback_suffixes):
                fallback = fallback or host
                continue
            return host
    return fallback


def host(url: str) -> str:
    try:
        return urlparse(str(url)).netloc
    except Exception:
        return ""


def elapsed_ms(started_at: float) -> int:
    return max(0, int((time.perf_counter() - started_at) * 1000))


def speed_kbps(bytes_written: int, duration_ms: int) -> int:
    return int((bytes_written or 0) / max(duration_ms / 1000, 0.001) / 1024)


def speed_bytes_per_second(bytes_written: int, started_at: float) -> int:
    elapsed_seconds = max(time.perf_counter() - started_at, 0.001)
    return int((bytes_written or 0) / elapsed_seconds)


def build_error_response(
    message: str,
    *,
    output_root: Optional[Path] = None,
    timings: Optional[Dict[str, int]] = None,
    traceback_text: str = "",
    total: Optional[int] = None,
) -> Dict[str, Any]:
    response = {
        "ok": False,
        "message": message,
        "error": message,
        "output_dir": str(output_root) if output_root is not None else "",
        "files": [],
        "success": 0,
        "failed": 1,
        "skipped": 0,
        "timings": timings or {},
        "download_metrics": [],
        "api_metrics": [],
    }
    if total is not None:
        response["total"] = total
    if traceback_text:
        response["traceback"] = traceback_text
    return response
