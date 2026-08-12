import asyncio
import json
import os
import sys
import time
import traceback
from pathlib import Path
from typing import Any, Dict, List, Optional

from .android_xhs import AndroidXHS
from common.android_flow_logger import AndroidFlowLogger, url_preview
from common.android_progress_reporter import AndroidProgressReporter
from common.android_utils import (
    build_error_response,
    changed_files_since,
    first_media_host,
    sum_file_bytes,
    sum_metric_duration,
)

_runtime: Dict[str, Any] = {
    "app_root": None,
    "output_root": None,
    "cookie": "",
}
_MEDIA_SUFFIXES = {
    ".gif",
    ".jpeg",
    ".jpg",
    ".m4a",
    ".mov",
    ".mp3",
    ".mp4",
    ".png",
    ".webp",
}
_SHORT_URL_CACHE: Dict[str, str] = {}
_FLOW_LOGGER_NAME = "XhsAndroidFlow"


def warm_up(app_data_dir: str, output_dir: str, cookie_header: str) -> str:
    flow = AndroidFlowLogger(_FLOW_LOGGER_NAME)
    try:
        flow.info("warm_up.begin", app_data_dir=app_data_dir, output_dir=output_dir)
        with flow.stage("prepare_runtime"):
            _prepare_runtime(Path(app_data_dir), Path(output_dir))
        with flow.stage("init_config", has_cookie=bool(cookie_header)):
            _runtime["cookie"] = str(cookie_header or "")
        flow.mark_total()
        return json.dumps({"ok": True, "timings": dict(flow.timings)}, ensure_ascii=False)
    except Exception as exc:
        flow.mark_total()
        flow.error("warm_up.failed", error=str(exc))
        return json.dumps(
            {"ok": False, "error": str(exc), "timings": dict(flow.timings)},
            ensure_ascii=False,
        )


def refresh_cookies(cookie_header: str) -> str:
    _runtime["cookie"] = str(cookie_header or "")
    return json.dumps({"ok": True}, ensure_ascii=False)


def download(input_text: str, progress_callback=None) -> str:
    flow = AndroidFlowLogger(_FLOW_LOGGER_NAME)
    try:
        result = asyncio.run(_download_async(input_text, flow, progress_callback))
    except Exception as exc:
        flow.mark_total()
        flow.error("download.failed", error=str(exc), input=url_preview(input_text))
        result = _error(
            str(exc),
            timings=dict(flow.timings),
            traceback_text=traceback.format_exc(limit=12),
        )
    return json.dumps(result, ensure_ascii=False)


def _prepare_runtime(app_root: Path, output_root: Path) -> None:
    app_root = app_root / "xhs"
    output_root = output_root / "XHS"
    app_root.mkdir(parents=True, exist_ok=True)
    output_root.mkdir(parents=True, exist_ok=True)
    os.environ.setdefault("HOME", str(app_root))
    os.environ.setdefault("TMPDIR", str(app_root / "tmp"))
    os.environ.setdefault("PYTHONUTF8", "1")
    Path(os.environ["TMPDIR"]).mkdir(parents=True, exist_ok=True)
    package_root = Path(__file__).resolve().parents[1]
    if str(package_root) not in sys.path:
        sys.path.insert(0, str(package_root))
    _runtime["app_root"] = app_root
    _runtime["output_root"] = output_root


async def _download_async(
    input_text: str,
    flow: AndroidFlowLogger,
    progress_callback=None,
) -> Dict[str, Any]:
    if _runtime["app_root"] is None or _runtime["output_root"] is None:
        return _error("Python runtime is not initialized", timings=dict(flow.timings))

    input_text = str(input_text or "").strip()
    if not input_text:
        return _error("请先粘贴小红书分享文本或链接", timings=dict(flow.timings))

    app_root: Path = _runtime["app_root"]
    output_root: Path = _runtime["output_root"]
    cookie = str(_runtime.get("cookie") or "")
    started_wall = time.time()
    progress_reporter = AndroidProgressReporter(progress_callback)

    flow.info("download.begin", input=url_preview(input_text), output_dir=str(output_root))
    with flow.stage("download_content", has_cookie=bool(cookie)):
        async with AndroidXHS(
            root=app_root,
            work_path=str(output_root.parent),
            folder_name=output_root.name,
            cookie=cookie,
            timeout=15,
            max_retry=2,
            record_data=False,
            download_record=False,
            image_format="JPEG",
            live_download=False,
            author_archive=False,
            folder_mode=False,
            flow=flow,
            progress_reporter=progress_reporter,
            short_url_cache=_SHORT_URL_CACHE,
        ) as xhs:
            items = await xhs.extract(input_text, download=True, data=True)

    with flow.stage("collect_files"):
        files, ignored_files = _changed_files_since(output_root, started_wall)
        flow.info("collect_files.result", files=len(files), ignored=len(ignored_files))

    ok_count = sum(1 for item in items if isinstance(item, dict) and item.get("下载地址"))
    failed = 0 if ok_count else 1
    success_count = len(files) or ok_count
    timings = _normalize_timings(flow.timings)
    download_metrics = _build_download_metrics(files, items, timings)
    api_metrics = _build_api_metrics(timings)
    _normalize_download_stage_timing(timings, api_metrics, download_metrics)
    flow.mark_total()
    timings["total_ms"] = flow.timings.get("total_ms", 0)
    flow.info(
        "download.done",
        total_ms=timings.get("total_ms"),
        items=len(items),
        success=success_count,
        failed=failed,
        files=len(files),
        bytes=sum(item.get("bytes", 0) for item in download_metrics),
        speed_kbps=download_metrics[0].get("speed_kbps") if download_metrics else 0,
    )
    return {
        "ok": bool(files or ok_count),
        "message": _summary_message(files, ok_count),
        "error": "" if files or ok_count else "未下载到文件，请检查链接、Cookie 或作品权限",
        "output_dir": str(output_root),
        "files": files,
        "success": success_count,
        "failed": failed,
        "skipped": 0,
        "timings": timings,
        "download_metrics": download_metrics,
        "api_metrics": api_metrics,
        "items": items,
    }


def _changed_files_since(root: Path, started_at: float) -> tuple[List[str], List[str]]:
    return changed_files_since(
        root,
        started_at,
        allowed_suffixes=_MEDIA_SUFFIXES,
        include_ignored=True,
    )


def _normalize_timings(source: Dict[str, int]) -> Dict[str, int]:
    timings = dict(source)
    resolve_ms = timings.get("resolve_short_url_ms", 0)
    extract_links_ms = timings.get("extract_links_ms", 0)
    timings.setdefault("resolve_input_url_ms", resolve_ms)
    timings.setdefault("parse_url_ms", max(0, extract_links_ms - resolve_ms))
    return timings


def _normalize_download_stage_timing(
    timings: Dict[str, int],
    api_metrics: List[Dict[str, Any]],
    download_metrics: List[Dict[str, Any]],
) -> None:
    timings["xhs_flow_ms"] = timings.get("download_content_ms", 0)
    timings["download_content_ms"] = sum_metric_duration(api_metrics) + sum_metric_duration(download_metrics)


def _build_api_metrics(timings: Dict[str, int]) -> List[Dict[str, Any]]:
    detail_ms = sum(
        timings.get(name, 0)
        for name in (
            "request_note_html_ms",
            "parse_note_data_ms",
            "extract_note_fields_ms",
            "extract_video_urls_ms",
            "extract_image_urls_ms",
        )
    )
    return [{"name": "get_video_detail", "duration_ms": detail_ms}] if detail_ms > 0 else []


def _build_download_metrics(
    files: List[str],
    items: List[Any],
    timings: Dict[str, int],
) -> List[Dict[str, Any]]:
    bytes_total = sum_file_bytes(files)
    if bytes_total <= 0:
        return []

    duration_ms = max(1, timings.get("download_files_ms", 0))
    speed_kbps = int((bytes_total / 1024) / (duration_ms / 1000))
    host = first_media_host(items, fallback_suffixes=("xiaohongshu.com", "xhslink.com"))
    return [
        {
            "ok": True,
            "host": host,
            "final_host": host,
            "bytes": bytes_total,
            "duration_ms": duration_ms,
            "first_chunk_ms": 0,
            "speed_kbps": speed_kbps,
        }
    ]

def _summary_message(files: List[str], ok_count: int) -> str:
    if files:
        return f"下载完成，新增 {len(files)} 个文件"
    if ok_count:
        return "作品解析完成，但没有发现新增文件"
    return "下载失败"


def _error(
    message: str,
    output_root: Optional[Path] = None,
    timings: Optional[Dict[str, int]] = None,
    traceback_text: str = "",
) -> Dict[str, Any]:
    return build_error_response(
        message,
        output_root=output_root,
        timings=timings,
        traceback_text=traceback_text,
    )
