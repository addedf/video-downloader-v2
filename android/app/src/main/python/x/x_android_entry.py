# -*- coding: utf-8 -*-
"""X 平台 Android 入口：对齐 xhs_android_entry 的桥接协议。

Kotlin BasePyDownloadModule 按名调用：
- warm_up(app_data_dir, output_dir, cookie_header) -> JSON
- refresh_cookies(cookie_header) -> JSON
- resolve(input_text) -> JSON（v2 schema，ResolveResultParser 消费）
- download(input_text, [request_json,] progress_callback) -> JSON

download 的第二参兼容两种形态：``str`` 是 v2 DownloadRequest，其余视为
进度回调（BasePyDownloadModule 无 request 时只传回调）。
"""
import asyncio
import json
import os
import sys
import time
import traceback
from pathlib import Path
from typing import Any, Dict, List, Optional

import httpx

from .x_client import XGuestClient, GraphQLError
from .media_extractor import tweet_media

_runtime: Dict[str, Any] = {"app_root": None, "output_root": None}
_client: Optional[XGuestClient] = None
_FLOW_LOGGER_NAME = "XAndroidFlow"

try:
    from common.android_flow_logger import AndroidFlowLogger, url_preview
    from common.android_utils import build_error_response
except ImportError:  # 桌面验证时 common 不在包路径
    AndroidFlowLogger = None

    def url_preview(text, limit=48):
        return (text or "")[:limit]

    def build_error_response(message, *, output_root=None, timings=None,
                             traceback_text=""):
        response = {
            "ok": False, "message": message, "error": message,
            "output_dir": str(output_root) if output_root else "",
            "files": [], "success": 0, "failed": 1, "skipped": 0,
            "timings": timings or {}, "download_metrics": [], "api_metrics": [],
        }
        if traceback_text:
            response["traceback"] = traceback_text
        return response


def _logger():
    return AndroidFlowLogger(_FLOW_LOGGER_NAME) if AndroidFlowLogger else None


def warm_up(app_data_dir: str, output_dir: str, cookie_header: str) -> str:
    global _client
    flow = _logger()
    try:
        if flow:
            flow.info("warm_up.begin", output_dir=output_dir)
        app_root = Path(app_data_dir) / "x"
        out_root = Path(output_dir) / "X"
        app_root.mkdir(parents=True, exist_ok=True)
        out_root.mkdir(parents=True, exist_ok=True)
        os.environ.setdefault("HOME", str(app_root))
        os.environ.setdefault("TMPDIR", str(app_root / "tmp"))
        os.environ.setdefault("PYTHONUTF8", "1")
        Path(os.environ["TMPDIR"]).mkdir(parents=True, exist_ok=True)
        package_root = Path(__file__).resolve().parents[1]
        if str(package_root) not in sys.path:
            sys.path.insert(0, str(package_root))
        _runtime["app_root"] = app_root
        _runtime["output_root"] = out_root
        _client = XGuestClient()
        if flow:
            flow.mark_total()
        return json.dumps({"ok": True}, ensure_ascii=False)
    except Exception as exc:
        if flow:
            flow.error("warm_up.failed", error=str(exc))
        return json.dumps(
            {"ok": False, "error": str(exc)}, ensure_ascii=False)


def refresh_cookies(cookie_header: str) -> str:
    """X 走 guest 免登录，cookie 仅预留（阶段 3 Cookie 会话用）。"""
    return json.dumps({"ok": True}, ensure_ascii=False)


def resolve(input_text: str) -> str:
    flow = _logger()
    try:
        result = _resolve_sync(input_text)
    except Exception as exc:
        if flow:
            flow.error("resolve.failed", error=str(exc),
                       input=url_preview(input_text))
        result = {
            "schema_version": 2, "ok": False,
            "message": str(exc), "error": str(exc),
            "source": {"platform": "x", "input_url": input_text,
                       "resolved_url": "", "id": ""},
            "work": None,
        }
    return json.dumps(result, ensure_ascii=False)


def _resolve_sync(input_text: str) -> dict:
    client = _client or XGuestClient()
    normalized = normalize_input(input_text)
    tweet_id = normalized["tweet_id"]
    tweet = client.tweet_result(tweet_id)
    if not tweet:
        raise ValueError(f"推文不存在或不可见：{tweet_id}")
    media = tweet_media(tweet)
    author = author_name(tweet)
    text = plain_text(tweet)
    source_url = (
        f"https://x.com/{author['handle']}/status/{tweet_id}"
        if author.get("handle") else normalized["url"]
    )
    return {
        "schema_version": 2,
        "ok": True,
        "message": "",
        "error": "",
        "source": {
            "platform": "x",
            "input_url": input_text,
            "resolved_url": source_url,
            "id": tweet_id,
        },
        "work": {
            "type": work_type(media),
            "title": text[:64] or f"X 推文 {tweet_id}",
            "author": {"id": author.get("id", ""), "name": author.get("name", "")},
            "capabilities": capabilities(media),
            "counts": counts(media),
            "resources": resources(media),
        },
    }


def download(input_text: str, *args) -> str:
    request_json = None
    progress_callback = None
    for arg in args:
        if arg is None:
            continue
        if isinstance(arg, str):
            request_json = arg
        else:
            progress_callback = arg
    flow = _logger()
    try:
        result = asyncio.run(
            _download_async(input_text, request_json, progress_callback, flow))
    except Exception as exc:
        if flow:
            flow.error("download.failed", error=str(exc))
        result = build_error_response(
            str(exc), output_root=_runtime.get("output_root"),
            timings={}, traceback_text=traceback.format_exc(limit=8))
    return json.dumps(result, ensure_ascii=False)


async def _download_async(input_text, request_json, progress_callback, flow):
    output_root: Optional[Path] = _runtime.get("output_root")
    if output_root is None:
        return build_error_response("Python runtime 未初始化")
    started = time.time()
    selection = parse_selection(request_json)
    resolved = _resolve_sync(input_text)
    resources = collect_selected(resolved, selection)
    if not resources:
        return build_error_response("没有可下载的资源", output_root=output_root)

    output_root.mkdir(parents=True, exist_ok=True)
    tweet_id = resolved["source"]["id"]
    files: List[str] = []
    download_metrics: List[dict] = []
    failed = 0
    async with httpx.AsyncClient(follow_redirects=True, timeout=30) as http:
        for index, resource in enumerate(resources):
            name = f"x_{tweet_id}_{index}.{resource['format_hint'] or 'mp4'}"
            target = output_root / name
            try:
                metric = await _download_one(
                    http, resource["download_urls"][0], target, progress_callback)
                files.append(str(target))
                download_metrics.append(metric)
            except Exception as exc:
                failed += 1
                if flow:
                    flow.error("download.file_failed", file=name, error=str(exc))
    success = len(files)
    timings = {"total_ms": int((time.time() - started) * 1000)}
    return {
        "ok": success > 0,
        "message": f"下载完成，新增 {success} 个文件" if success else "下载失败",
        "error": "" if success else "未下载到文件",
        "output_dir": str(output_root),
        "files": files,
        "success": success,
        "failed": failed,
        "skipped": 0,
        "timings": timings,
        "download_metrics": download_metrics,
        "api_metrics": [],
    }


async def _download_one(http, url, target: Path, progress_callback) -> dict:
    import aiofiles
    started = time.perf_counter()
    async with http.stream("GET", url) as response:
        response.raise_for_status()
        total = int(response.headers.get("content-length") or 0)
        downloaded = 0
        first_chunk_ms = None
        async with aiofiles.open(target, "wb") as fp:
            async for chunk in response.aiter_bytes(64 * 1024):
                await fp.write(chunk)
                if first_chunk_ms is None:
                    first_chunk_ms = int((time.perf_counter() - started) * 1000)
                downloaded += len(chunk)
                if progress_callback is not None and total:
                    percent = min(100, downloaded * 100 // total)
                    speed = int(downloaded / max(time.perf_counter() - started, 0.001))
                    try:
                        progress_callback.onProgress(
                            percent, downloaded, total, speed)
                    except Exception:
                        pass
    duration_ms = max(1, int((time.perf_counter() - started) * 1000))
    return {
        "ok": True,
        "host": "video.twimg.com",
        "final_host": "video.twimg.com",
        "bytes": downloaded,
        "duration_ms": duration_ms,
        "first_chunk_ms": first_chunk_ms or 0,
        "speed_kbps": int(downloaded / 1024 / (duration_ms / 1000)),
    }


# ---- selection / 资源过滤 -------------------------------------------------- #

def parse_selection(request_json):
    if not request_json:
        return None
    try:
        request = json.loads(request_json)
    except (TypeError, ValueError):
        return None
    selection = request.get("selection") or {}
    return {
        "resource_type": selection.get("resource_type") or "",
        "resource_ids": set(selection.get("resource_ids") or []),
    }


def collect_selected(resolved: dict, selection) -> List[dict]:
    """按 selection 过滤资源；无 selection 时取 work_type 对应的默认组。"""
    work = resolved.get("work") or {}
    res = work.get("resources") or {}
    if selection and selection["resource_type"]:
        group = res.get(f"{selection['resource_type']}s") or []
        wanted = selection["resource_ids"]
        if wanted:
            group = [r for r in group if r.get("id") in wanted]
    else:
        group = (res.get("videos") if work.get("type") == "video"
                 else res.get("images")) or []
    return [r for r in group if r.get("download_urls")]


# ---- 复用 media_extractor 的小工具 ------------------------------------------ #

def normalize_input(input_text: str) -> dict:
    from .link_normalizer import normalize as _normalize
    return _normalize(input_text)


def author_name(tweet: dict) -> dict:
    core = tweet.get("core") or {}
    result = (core.get("user_results") or {}).get("result") or {}
    legacy = result.get("legacy") or {}
    return {
        "id": result.get("rest_id", ""),
        "name": legacy.get("name", ""),
        "handle": legacy.get("screen_name", ""),
    }


def plain_text(tweet: dict) -> str:
    return ((tweet.get("legacy") or {}).get("full_text") or "").strip()


def work_type(media: List[dict]) -> str:
    if any(m.get("type") in ("video", "animated_gif") for m in media):
        return "video"
    return "gallery"


def capabilities(media: List[dict]) -> dict:
    videos = [m for m in media if m.get("type") in ("video", "animated_gif")]
    images = [m for m in media if m.get("type") == "photo"]
    return {
        "has_video": bool(videos),
        "has_images": bool(images),
        "has_cover": False,
        "has_audio": False,
        "has_live_video": False,
    }


def counts(media: List[dict]) -> dict:
    videos = [m for m in media if m.get("type") in ("video", "animated_gif")]
    images = [m for m in media if m.get("type") == "photo"]
    return {"videos": len(videos), "images": len(images),
            "covers": 0, "audios": 0, "live_videos": 0}


def resources(media: List[dict]) -> dict:
    videos: List[dict] = []
    images: List[dict] = []
    for index, m in enumerate(media):
        mtype = m.get("type")
        if mtype in ("video", "animated_gif"):
            variants = sorted(
                (v for v in (m.get("video_info") or {}).get("variants") or []
                 if v.get("content_type") == "video/mp4"),
                key=lambda v: v.get("bitrate") or 0, reverse=True)
            if not variants:
                continue
            top = variants[0]
            sizes = (m.get("sizes") or {}).get("large") or {}
            videos.append({
                "id": f"video-{index}",
                "index": index,
                "type": "video",
                "title": (m.get("ext_alt_text") or "X 视频").strip(),
                "preview_urls": [m.get("media_url_https", "")],
                "download_urls": [top["url"]],
                "width": sizes.get("w"),
                "height": sizes.get("h"),
                "duration_ms": (m.get("video_info") or {}).get("duration_millis"),
                "format_hint": "mp4",
            })
        elif mtype == "photo":
            base = m.get("media_url_https") or ""
            sizes = (m.get("sizes") or {}).get("large") or {}
            images.append({
                "id": f"image-{index}",
                "index": index,
                "type": "image",
                "title": (m.get("ext_alt_text") or "X 图片").strip(),
                "preview_urls": [f"{base}?name=small"],
                "download_urls": [f"{base}?name=orig"],
                "width": sizes.get("w"),
                "height": sizes.get("h"),
                "duration_ms": None,
                "format_hint": "jpg",
            })
    return {"videos": videos, "images": images, "covers": [], "audios": []}
