import asyncio
import json
import sys
import time
import traceback
from pathlib import Path
from typing import Any, Dict, List, Optional

_DY_ROOT = Path(__file__).resolve().parents[1]
if str(_DY_ROOT) not in sys.path:
    sys.path.insert(0, str(_DY_ROOT))

from .dy_api_diagnostics import (
    consume_android_metrics,
    install_android_api_diagnostics,
    reset_android_metrics,
)
from .dy_resource_normalizer import build_resolve_response, normalize_aweme
from .dy_selected_downloader import (
    download_selected_resources,
    parse_download_request,
    select_download_source_url,
)
from common.android_flow_logger import AndroidFlowLogger, new_flow_logger, url_preview
from common.android_progress_reporter import AndroidProgressReporter
from common.android_utils import (
    TMP_SUFFIX,
    build_error_response,
    changed_files_since,
    extract_first_url,
)
from auth import CookieManager
from config import ConfigLoader
from control import QueueManager, RateLimiter, RetryHandler
from core import DouyinAPIClient, DownloaderFactory, URLParser
from core.downloader_base import BaseDownloader
from storage import Database, FileManager
from utils.cookie_utils import parse_cookie_header, sanitize_cookies
from utils.validators import is_short_url, normalize_short_url

_ANDROID_USE_DATABASE = False
_ANDROID_THREAD_COUNT = 4
_ANDROID_RATE_LIMIT = 4.0
_ANDROID_RETRY_TIMES = 2


class AndroidGlobalConfig:
    def __init__(self):
        self.config_loader: Optional[ConfigLoader] = None
        self.cookie_manager: Optional[CookieManager] = None
        self.database: Optional[Database] = None
        self.androidProgressReporter: AndroidProgressReporter = AndroidProgressReporter()


_android_global_config = AndroidGlobalConfig()


def warm_up(app_data_dir: str, output_dir: str, cookie_header: str) -> str:
    flow = new_flow_logger()
    try:
        flow.info("warm_up.begin", app_data_dir=app_data_dir, output_dir=output_dir)
        with flow.stage("prepare_runtime"):
            _prepare_runtime()
        with flow.stage("init_config", has_cookie=bool(cookie_header)):
            asyncio.run(_init_config(app_data_dir, output_dir, cookie_header))
        flow.mark_total()
        flow.info("warm_up.done", total_ms=flow.timings.get("total_ms"))
        return json.dumps({"ok": True}, ensure_ascii=False)
    except Exception as exc:
        flow.mark_total()
        flow.error("warm_up.failed", total_ms=flow.timings.get("total_ms"), error=str(exc))
        return json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False)


def _prepare_runtime() -> None:
    install_android_api_diagnostics()


async def _init_config(app_data_dir: str, output_dir: str, cookie_header: str):
    app_root = Path(app_data_dir)
    output_root = Path(output_dir)
    app_root.mkdir(parents=True, exist_ok=True)
    output_root.mkdir(parents=True, exist_ok=True)
    cookies = _parse_cookies(cookie_header)

    config = _build_config(cookies, output_root, app_root)
    cookie_manager = CookieManager(str(app_root / ".cookies.json"))
    cookie_manager.set_cookies(cookies)

    _android_global_config.config_loader = config
    _android_global_config.cookie_manager = cookie_manager
    _android_global_config.database = None

    if config.get("database"):
        database = Database(db_path=str(app_root / "dy_downloader.db"))
        _android_global_config.database = database
        await database.initialize()


def refresh_cookies(cookie_header: str):
    flow = new_flow_logger()
    if _android_global_config.cookie_manager is None:
        raise RuntimeError("Python runtime cookie_manager is not initialized")
    with flow.stage("refresh_cookies", has_cookie=bool(cookie_header)):
        cookie_manager = _android_global_config.cookie_manager
        cookies = _parse_cookies(cookie_header)
        cookie_manager.set_cookies(cookies)
    if _android_global_config.config_loader is None:
        raise RuntimeError("Python runtime config_loader is not initialized")
    config_loader = _android_global_config.config_loader
    config_loader.update(cookies=cookies)


def download(input_text: str, request_json=None, progress_callback=None) -> str:
    flow = new_flow_logger()
    try:
        # Keep compatibility with the historical two-argument Python entry.
        if request_json is not None and not isinstance(request_json, str):
            progress_callback = request_json
            request_json = None
        request = parse_download_request(request_json)
        reset_android_metrics()
        flow.info("download.begin", input=url_preview(input_text))
        result = asyncio.run(_download_async(input_text, flow, progress_callback, request))
    except Exception as exc:
        flow.mark_total()
        flow.error("download.failed", total_ms=flow.timings.get("total_ms"), error=str(exc))
        result = {
            "ok": False,
            "error": str(exc),
            "traceback": traceback.format_exc(limit=12),
            "files": [],
            "timings": dict(flow.timings),
            **consume_android_metrics(),
        }
    return json.dumps(result, ensure_ascii=False)


def resolve(input_text: str) -> str:
    flow = new_flow_logger()
    try:
        reset_android_metrics()
        flow.info("resolve.begin", input=url_preview(input_text))
        result = asyncio.run(_resolve_async(input_text, flow))
    except Exception as exc:
        flow.mark_total()
        flow.error("resolve.failed", total_ms=flow.timings.get("total_ms"), error=str(exc))
        result = {
            "ok": False,
            "error": str(exc),
            "message": str(exc),
            "timings": dict(flow.timings),
            **consume_android_metrics(),
        }
    return json.dumps(result, ensure_ascii=False)


async def _resolve_async(input_text: str, flow: AndroidFlowLogger) -> Dict[str, Any]:
    cookie_manager = _android_global_config.cookie_manager
    if cookie_manager is None or _android_global_config.config_loader is None:
        return _error("Python runtime is not initialized")
    cookies = cookie_manager.get_cookies()
    if not cookies:
        return _error("请先登录抖音获取 Cookie")

    input_text = str(input_text or "").strip()
    if not input_text:
        return _error("请先粘贴抖音分享文本或链接")

    async with DouyinAPIClient(cookies, proxy=_android_global_config.config_loader.get("proxy")) as api_client:
        with flow.stage("resolve_input_url", input=url_preview(input_text)):
            resolved_url = await _resolve_input_url(input_text, api_client, flow)
        with flow.stage("parse_url", resolved=url_preview(resolved_url)):
            parsed = URLParser.parse(resolved_url)
        if not parsed:
            return _error(f"无法识别链接类型: {input_text}")

        url_type = parsed.get("type")
        if url_type not in ("video", "gallery"):
            return _error("当前版本仅支持抖音单作品链接")

        aweme_id = parsed.get("aweme_id") or parsed.get("note_id")
        if not aweme_id:
            return _error("无法从链接中提取作品 ID")

        with flow.stage("get_video_detail", aweme_id=aweme_id):
            aweme_data = await api_client.get_video_detail(str(aweme_id))
        if not aweme_data:
            return _error("解析失败，请检查链接权限或重新登录")

        flow.mark_total()
        response = build_resolve_response(
            aweme_data,
            input_url=input_text,
            resolved_url=resolved_url,
            source_id=str(aweme_id),
        )
        response.update(
            {
                "timings": dict(flow.timings),
                **consume_android_metrics(),
            }
        )
        return response


def _build_resource_preview(aweme_data: Dict[str, Any], source_url: str) -> Dict[str, Any]:
    author = aweme_data.get("author") or {}
    title = (aweme_data.get("desc") or "无标题作品").strip() or "无标题作品"
    author_name = author.get("nickname") or "未知作者"
    source_id = str(aweme_data.get("aweme_id") or "")
    media_type = _detect_preview_media_type(aweme_data)
    cover_url = _extract_cover_url(aweme_data)

    resources: List[Dict[str, Any]] = []
    if media_type == "gallery":
        image_candidates = _collect_preview_image_candidates(aweme_data)
        for index, candidates in enumerate(image_candidates, start=1):
            resources.append(
                {
                    "title": f"图片 {index}",
                    "media_type": "image",
                    "download_urls": candidates,
                    "selected": True,
                }
            )
        if cover_url:
            resources.append(
                {
                    "title": "封面",
                    "media_type": "cover",
                    "download_urls": [cover_url],
                    "selected": False,
                }
            )
    else:
        video_urls = _collect_preview_video_urls(aweme_data)
        resources.append(
            {
                "title": "视频",
                "media_type": "video",
                "download_urls": video_urls,
                "selected": True,
            }
        )
        if cover_url:
            resources.append(
                {
                    "title": "封面",
                    "media_type": "cover",
                    "download_urls": [cover_url],
                    "selected": False,
                }
            )

    return {
        "title": title,
        "author": author_name,
        "cover_url": cover_url,
        "source_url": source_url,
        "source_id": source_id,
        "media_type": media_type,
        "resources": resources,
    }


def _detect_preview_media_type(aweme_data: Dict[str, Any]) -> str:
    if aweme_data.get("image_post_info") or aweme_data.get("images") or aweme_data.get("image_list"):
        return "gallery"
    aweme_type = aweme_data.get("aweme_type")
    if isinstance(aweme_type, int) and aweme_type in {2, 68, 150}:
        return "gallery"
    return "video"


def _extract_cover_url(aweme_data: Dict[str, Any]) -> Optional[str]:
    video = aweme_data.get("video") if isinstance(aweme_data.get("video"), dict) else {}
    return BaseDownloader._extract_first_url(video.get("cover") or video.get("origin_cover"))


def _collect_preview_video_urls(aweme_data: Dict[str, Any]) -> List[str]:
    video = aweme_data.get("video") if isinstance(aweme_data.get("video"), dict) else {}
    play_addr = BaseDownloader._pick_highest_quality_play_addr(video) or video.get("play_addr", {})
    return BaseDownloader._collect_media_urls(play_addr, video.get("download_addr"))


def _collect_preview_image_candidates(aweme_data: Dict[str, Any]) -> List[List[str]]:
    image_urls: List[List[str]] = []
    for item in BaseDownloader._iter_gallery_items(aweme_data):
        if not isinstance(item, dict):
            continue
        candidates = BaseDownloader._collect_media_urls(
            item.get("watermark_free_download_url_list"),
            item,
            item.get("origin_image"),
            item.get("display_image"),
            item.get("download_url"),
            item.get("download_addr"),
            item.get("download_url_list"),
            item.get("owner_watermark_image"),
        )
        if candidates:
            image_urls.append(candidates)
    return image_urls


async def _download_async(
        input_text: str,
        flow: AndroidFlowLogger,
        progress_callback=None,
        request: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    started_wall = time.time()

    cookie_manager = _android_global_config.cookie_manager
    if cookie_manager is None or _android_global_config.config_loader is None:
        return _error("Python runtime is not initialized")
    cookies = cookie_manager.get_cookies()
    config = _android_global_config.config_loader
    database = _android_global_config.database

    output_root = Path(config.get("path"))

    if not cookies:
        return _error("请先登录抖音获取 Cookie")

    url = select_download_source_url(input_text, request)
    if not url:
        return _error("请先粘贴抖音分享文本或链接")

    flow.info("download.context", output_dir=output_root, has_cookie=bool(cookies))
    reporter = AndroidProgressReporter(progress_callback)
    try:
        with flow.stage("api_client_create"):
            api_client_context = DouyinAPIClient(cookies, proxy=config.get("proxy"))

        async with api_client_context as api_client:
            with flow.stage("resolve_input_url", input=url_preview(url)):
                resolved_url = await _resolve_input_url(url, api_client, flow)
            with flow.stage("parse_url", resolved=url_preview(resolved_url)):
                parsed = URLParser.parse(resolved_url)
            if not parsed:
                return _error(f"无法识别链接类型: {url}", output_root)

            flow.info("parse_url.result", url_type=parsed.get("type"), aweme_id=parsed.get("aweme_id"))
            with flow.stage("create_downloader", url_type=parsed.get("type")):
                file_manager = FileManager(str(output_root))
                downloader = DownloaderFactory.create(
                    parsed["type"],
                    config,
                    api_client,
                    file_manager,
                    cookie_manager,
                    database,
                    RateLimiter(max_per_second=float(config.get("rate_limit", 2) or 2)),
                    RetryHandler(max_retries=int(config.get("retry_times", 3) or 3)),
                    QueueManager(max_workers=int(config.get("thread", 2) or 2)),
                    progress_reporter=reporter,
                )
            if downloader is None:
                return _error(f"暂不支持的链接类型: {parsed.get('type')}", output_root)

            if request is not None:
                parsed_id = str(parsed.get("aweme_id") or parsed.get("note_id") or "")
                requested_id = str(request.get("source", {}).get("id") or "")
                if requested_id and parsed_id and requested_id != parsed_id:
                    return _error("下载链接与已解析作品不一致，请重新解析", output_root)
                with flow.stage("get_video_detail", aweme_id=parsed_id):
                    aweme_data = await api_client.get_video_detail(parsed_id)
                if not aweme_data:
                    return _error("解析失败，请检查链接权限或重新登录", output_root)
                with flow.stage("download_selected", resource_type=request["selection"]["resource_type"]):
                    selected_result = await download_selected_resources(
                        request=request,
                        aweme_data=aweme_data,
                        work=normalize_aweme(aweme_data),
                        downloader=downloader,
                        output_root=output_root,
                    )
                flow.mark_total()
                selected_result.update(
                    {
                        "url": resolved_url,
                        "type": parsed.get("type"),
                        "timings": dict(flow.timings),
                        **consume_android_metrics(),
                    }
                )
                return selected_result

            with flow.stage("download_content", url_type=parsed.get("type")):
                result = await downloader.download(parsed)
            with flow.stage("collect_files"):
                files = _changed_files_since(output_root, started_wall)
            flow.mark_total()
            flow.info(
                "download.done",
                total_ms=flow.timings.get("total_ms"),
                total=getattr(result, "total", 0),
                success=getattr(result, "success", 0),
                failed=getattr(result, "failed", 0),
                skipped=getattr(result, "skipped", 0),
                files=len(files),
            )
            return {
                "ok": bool(result and result.success > 0),
                "url": resolved_url,
                "type": parsed.get("type"),
                "total": int(getattr(result, "total", 0) or 0),
                "success": int(getattr(result, "success", 0) or 0),
                "failed": int(getattr(result, "failed", 0) or 0),
                "skipped": int(getattr(result, "skipped", 0) or 0),
                "output_dir": str(output_root),
                "files": files,
                "message": _summary_message(result, files),
                "timings": dict(flow.timings),
                **consume_android_metrics(),
            }
    finally:
        if database is not None:
            with flow.stage("database_close"):
                await database.close()


def _parse_cookies(cookie_header: str) -> Dict[str, str]:
    if not cookie_header:
        return {}
    cookie_header = cookie_header.strip()
    try:
        if cookie_header.startswith("{"):
            raw = json.loads(cookie_header)
            if isinstance(raw, dict):
                return sanitize_cookies({str(k): str(v) for k, v in raw.items()})
    except Exception:
        pass
    return sanitize_cookies(parse_cookie_header(cookie_header))


def _build_config(cookies: Dict[str, str], output_root: Path, app_root: Path) -> ConfigLoader:
    config = ConfigLoader(None)
    config.update(
        path=str(output_root),
        cookies=cookies,
        thread=_ANDROID_THREAD_COUNT,
        rate_limit=_ANDROID_RATE_LIMIT,
        retry_times=_ANDROID_RETRY_TIMES,
        database=_ANDROID_USE_DATABASE,
        database_path=str(app_root / "dy_downloader.db"),
        force_download=True,
        music=False,
        cover=False,
        avatar=False,
        json=False,
        folderstyle=True,
        auto_cookie=False,
        browser_fallback={"enabled": False},
        transcript={"enabled": False},
        comments={"enabled": False},
        notifications={"enabled": False, "providers": []},
    )
    return config


async def _resolve_input_url(
        input_text: str,
        api_client: DouyinAPIClient,
        flow: Optional[AndroidFlowLogger] = None,
) -> str:
    explicit_url = extract_first_url(input_text) or input_text.strip()
    if is_short_url(explicit_url):
        if flow is not None:
            flow.info("short_url.detected", url=url_preview(explicit_url))
        resolved = await api_client.resolve_short_url(normalize_short_url(explicit_url))
        if resolved:
            if flow is not None:
                flow.info("short_url.resolved", resolved=url_preview(resolved))
            return resolved
        if flow is not None:
            flow.warning("short_url.resolve_empty", url=url_preview(explicit_url))
    return explicit_url

def _changed_files_since(root: Path, started_at: float) -> List[str]:
    return changed_files_since(root, started_at, ignored_suffixes={TMP_SUFFIX})


def _summary_message(result: Any, files: List[str]) -> str:
    if result is None:
        return "下载失败"
    if getattr(result, "success", 0) > 0:
        return f"下载完成，新增 {len(files)} 个文件"
    if getattr(result, "skipped", 0) > 0:
        return "作品已存在，已跳过"
    return "下载失败，请检查 Cookie 或链接权限"


def _mark_timing(timings: Dict[str, int], name: str, started_perf: float) -> None:
    timings[name] = int((time.perf_counter() - started_perf) * 1000)


def _error(
        message: str,
        output_root: Optional[Path] = None,
        timings: Optional[Dict[str, int]] = None,
) -> Dict[str, Any]:
    return build_error_response(
        message,
        output_root=output_root,
        timings=timings,
        total=0,
    )
