import asyncio
import json
import os
import re
import sys
import time
import traceback
from pathlib import Path
from typing import Any, Dict, List, Optional
from urllib.parse import urlparse

from .android_xhs import AndroidXHS
from .xhs_url_utils import normalize_xhs_media_urls
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
    ".gif", ".jpeg", ".jpg", ".m4a", ".mov", ".mp3", ".mp4", ".png", ".webp",
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
        return json.dumps({"ok": False, "error": str(exc), "timings": dict(flow.timings)}, ensure_ascii=False)


def refresh_cookies(cookie_header: str) -> str:
    _runtime["cookie"] = str(cookie_header or "")
    return json.dumps({"ok": True}, ensure_ascii=False)


def resolve(input_text: str) -> str:
    flow = AndroidFlowLogger(_FLOW_LOGGER_NAME)
    try:
        result = asyncio.run(_resolve_async(input_text, flow))
    except Exception as exc:
        flow.mark_total()
        flow.error("resolve.failed", error=str(exc), input=url_preview(input_text))
        result = _error(str(exc), timings=dict(flow.timings), traceback_text=traceback.format_exc(limit=12))
    return json.dumps(result, ensure_ascii=False)


def download(input_text: str, request_json=None, progress_callback=None) -> str:
    flow = AndroidFlowLogger(_FLOW_LOGGER_NAME)
    try:
        if request_json is not None and not isinstance(request_json, str):
            progress_callback = request_json
            request_json = None
        request = _parse_download_request(request_json)
        source_input = _select_source_input(input_text, request)
        result = asyncio.run(_download_async(source_input, flow, progress_callback, request))
    except Exception as exc:
        flow.mark_total()
        flow.error("download.failed", error=str(exc), input=url_preview(input_text))
        result = _error(str(exc), timings=dict(flow.timings), traceback_text=traceback.format_exc(limit=12))
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


async def _resolve_async(input_text: str, flow: AndroidFlowLogger) -> Dict[str, Any]:
    if _runtime["app_root"] is None or _runtime["output_root"] is None:
        return _error("Python runtime is not initialized", timings=dict(flow.timings))
    input_text = str(input_text or "").strip()
    if not input_text:
        return _error("请先粘贴小红书分享文本或链接", timings=dict(flow.timings))

    flow.info("resolve.begin", input=url_preview(input_text))
    with flow.stage("resolve_public_note", has_cookie=bool(_runtime.get("cookie"))):
        async with _create_xhs(flow, progress_reporter=None, live_download=False) as xhs:
            items = await xhs.extract(input_text, download=False, data=True)
    item = next((value for value in items if isinstance(value, dict) and value.get("作品ID")), None)
    if not item:
        return _error("小红书公开页面未返回可解析作品数据", timings=dict(flow.timings))
    flow.mark_total()
    response = _build_resolve_response(item, input_text)
    counts = (response.get("work") or {}).get("counts") or {}
    if not any(int(counts.get(name, 0) or 0) > 0 for name in ("videos", "images", "covers")):
        return _error("小红书作品已识别，但公开页面未返回可下载资源", timings=dict(flow.timings))
    response["timings"] = dict(flow.timings)
    return response


async def _download_async(
    input_text: str,
    flow: AndroidFlowLogger,
    progress_callback=None,
    request: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    if _runtime["app_root"] is None or _runtime["output_root"] is None:
        return _error("Python runtime is not initialized", timings=dict(flow.timings))
    input_text = str(input_text or "").strip()
    if not input_text:
        return _error("请先粘贴小红书分享文本或链接", timings=dict(flow.timings))

    output_root: Path = _runtime["output_root"]
    started_wall = time.time()
    progress_reporter = AndroidProgressReporter(progress_callback)
    selection = (request or {}).get("selection", {})
    resource_type = str(selection.get("resource_type") or "")
    if resource_type == "audio":
        return _error("小红书作品暂不提供独立音频保存", output_root=output_root)
    indices = _selected_indices(selection)
    include_live = bool(selection.get("include_live_video", False))

    flow.info("download.begin", input=url_preview(input_text), output_dir=str(output_root))
    with flow.stage("download_content", has_cookie=bool(_runtime.get("cookie"))):
        async with _create_xhs(
            flow,
            progress_reporter=progress_reporter,
            live_download=include_live,
        ) as xhs:
            items = await xhs.extract(input_text, download=True, index=indices, data=True)

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
    return {
        "ok": bool(files or ok_count),
        "message": _summary_message(files, ok_count),
        "error": "" if files or ok_count else "未下载到文件，请检查链接或作品权限",
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


def _create_xhs(flow: AndroidFlowLogger, progress_reporter, live_download: bool) -> AndroidXHS:
    app_root: Path = _runtime["app_root"]
    output_root: Path = _runtime["output_root"]
    return AndroidXHS(
        root=app_root,
        work_path=str(output_root.parent),
        folder_name=output_root.name,
        cookie=str(_runtime.get("cookie") or ""),
        timeout=15,
        max_retry=2,
        record_data=False,
        download_record=False,
        image_format="JPEG",
        live_download=live_download,
        author_archive=False,
        folder_mode=False,
        flow=flow,
        progress_reporter=progress_reporter,
        short_url_cache=_SHORT_URL_CACHE,
    )


def _build_resolve_response(item: Dict[str, Any], input_text: str) -> Dict[str, Any]:
    work_id = str(item.get("作品ID") or "")
    source_url = str(item.get("作品链接") or input_text)
    title = str(item.get("作品标题") or item.get("作品描述") or "无标题作品").strip()
    author_name = str(item.get("作者昵称") or "未知作者")
    author_id = str(item.get("作者ID") or "")
    media_urls = _url_list(item.get("下载地址"))
    live_urls = _url_list(item.get("动图地址"), keep_empty=True)
    type_text = str(item.get("作品类型") or "")
    is_video = "视频" in type_text
    has_live = any(live_urls)
    work_type = "video" if is_video else ("live_photo" if has_live else "gallery")

    videos: List[Dict[str, Any]] = []
    images: List[Dict[str, Any]] = []
    covers: List[Dict[str, Any]] = []
    if is_video and media_urls:
        videos.append(_resource("video_1", 1, "video", "作品视频", media_urls, "mp4"))
    elif media_urls:
        for index, url in enumerate(media_urls, start=1):
            resource = _resource(f"image_{index}", index, "image", f"图片 {index}", [url], _format_hint(url, "jpg"))
            live_url = live_urls[index - 1] if index - 1 < len(live_urls) else ""
            resource["live_video"] = {
                "available": bool(live_url),
                "download_urls": [live_url] if live_url else [],
                "width": None,
                "height": None,
                "duration_ms": None,
                "format_hint": "mp4" if live_url else None,
            }
            images.append(resource)
        covers.append(_resource("cover_1", 1, "cover", "作品封面", [media_urls[0]], _format_hint(media_urls[0], "jpg")))

    cover_url = media_urls[0] if (media_urls and not is_video) else None
    work = {
        "type": work_type,
        "title": title,
        "author": {"id": author_id, "name": author_name},
        "capabilities": {
            "has_video": bool(videos),
            "has_images": bool(images),
            "has_cover": bool(covers),
            "has_audio": False,
            "has_live_video": has_live,
        },
        "counts": {
            "videos": len(videos),
            "images": len(images),
            "covers": len(covers),
            "audios": 0,
            "live_videos": sum(1 for value in live_urls if value),
        },
        "resources": {"videos": videos, "images": images, "covers": covers, "audios": []},
    }
    return {
        "schema_version": 2,
        "ok": True,
        "message": "解析成功",
        "error": None,
        "source_url": source_url,
        "source_id": work_id,
        "title": title,
        "author": author_name,
        "cover_url": cover_url,
        "media_type": work_type,
        "source": {
            "platform": "xiaohongshu",
            "input_url": input_text,
            "resolved_url": source_url,
            "id": work_id,
        },
        "work": work,
    }


def _resource(resource_id: str, index: int, resource_type: str, title: str, urls: List[str], format_hint: str) -> Dict[str, Any]:
    return {
        "id": resource_id,
        "index": index,
        "type": resource_type,
        "title": title,
        "preview_urls": list(urls),
        "download_urls": list(urls),
        "width": None,
        "height": None,
        "duration_ms": None,
        "format_hint": format_hint,
    }


def _url_list(value: Any, *, keep_empty: bool = False) -> List[str]:
    return normalize_xhs_media_urls(value, keep_empty=keep_empty)


def _format_hint(url: str, fallback: str) -> str:
    suffix = Path(urlparse(url).path).suffix.lower().lstrip(".")
    return "jpg" if suffix == "jpeg" else (suffix or fallback)


def _parse_download_request(raw: Optional[str]) -> Optional[Dict[str, Any]]:
    if raw is None or not str(raw).strip():
        return None
    request = json.loads(str(raw))
    if not isinstance(request, dict) or request.get("schema_version") != 2:
        raise ValueError("不支持的小红书下载选择参数")
    source = request.get("source")
    if not isinstance(source, dict) or source.get("platform") not in {"xiaohongshu", "xhs"}:
        raise ValueError("下载选择来源不是小红书")
    selection = request.get("selection")
    if not isinstance(selection, dict) or selection.get("resource_type") not in {"video", "image", "cover", "audio"}:
        raise ValueError("小红书下载选择格式错误")
    return request


def _select_source_input(input_text: str, request: Optional[Dict[str, Any]]) -> str:
    source = (request or {}).get("source")
    source_url = str(source.get("url") or "").strip() if isinstance(source, dict) else ""
    return source_url or str(input_text or "").strip()


def _selected_indices(selection: Dict[str, Any]) -> Optional[List[int]]:
    if selection.get("resource_type") == "cover":
        return [1]
    result = []
    for value in selection.get("resource_ids") or []:
        match = re.fullmatch(r"image_(\d+)", str(value))
        if match:
            result.append(int(match.group(1)))
    return result or None

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
    host = first_media_host(items, fallback_suffixes=("xiaohongshu.com", "xhslink.com", "xhslink.cn"))
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
