"""Android V2 entry for Douyin single-work and author-media resolve/download."""

from __future__ import annotations

import asyncio
import json
import sys
import traceback
from pathlib import Path
from typing import Any, Dict, Optional
from urllib.parse import urlparse

_DY_ROOT = Path(__file__).resolve().parents[1]
if str(_DY_ROOT) not in sys.path:
    sys.path.insert(0, str(_DY_ROOT))

from .dy_anonymous_downloader import AnonymousMediaDownloader
from . import dy_anonymous_share as anonymous_share
from .dy_anonymous_share import AnonymousDouyinError, AnonymousDouyinResult, resolve_public_douyin
from .dy_resource_normalizer import build_resolve_response, normalize_aweme
from .dy_profile import PROFILE_MEDIA_HOSTS, canonical_profile_url, profile_user_id, resolve_profile
from .dy_selected_downloader import (
    build_snapshot_download_context,
    download_selected_resources,
    parse_download_request,
    select_download_source_url,
)
from common.android_flow_logger import AndroidFlowLogger, new_flow_logger, url_preview
from common.android_progress_reporter import AndroidProgressReporter
from common.android_utils import build_error_response, extract_first_url, redact_sensitive_text
from config import ConfigLoader
from utils.cookie_utils import parse_cookie_header, sanitize_cookies

_ANDROID_RETRY_TIMES = 2


class AndroidGlobalConfig:
    def __init__(self):
        self.config_loader: Optional[ConfigLoader] = None


_android_global_config = AndroidGlobalConfig()


def warm_up(app_data_dir: str, output_dir: str, cookie_header: str) -> str:
    """Initialize anonymous paths and retain an optional encrypted-WebView cookie snapshot."""
    flow = new_flow_logger()
    try:
        flow.info("warm_up.begin", app_data_dir=app_data_dir, output_dir=output_dir)
        app_root = Path(app_data_dir)
        output_root = Path(output_dir)
        app_root.mkdir(parents=True, exist_ok=True)
        output_root.mkdir(parents=True, exist_ok=True)
        config = ConfigLoader(None)
        config.update(
            path=str(output_root),
            proxy=None,
            retry_times=_ANDROID_RETRY_TIMES,
            database=False,
            force_download=True,
            auto_cookie=False,
            browser_fallback={"enabled": False},
        )
        config.update(cookies=_parse_cookies(cookie_header))
        _android_global_config.config_loader = config
        flow.mark_total()
        return json.dumps({"ok": True, "anonymous": True}, ensure_ascii=False)
    except Exception as exc:
        flow.mark_total()
        flow.error("warm_up.failed", error=str(exc))
        return json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False)


def refresh_cookies(cookie_header: str) -> str:
    """Refresh the optional WebView cookie snapshot without changing anonymous defaults."""
    config = _android_global_config.config_loader
    if config is None:
        raise RuntimeError("Python runtime is not initialized")
    config.update(cookies=_parse_cookies(cookie_header))
    return json.dumps({"ok": True, "cookie_fallback": bool(_runtime_cookies(config))}, ensure_ascii=False)


def resolve(input_text: str, cursor=None) -> str:
    flow = new_flow_logger()
    diagnostics = _new_diagnostics(input_text)
    try:
        flow.info("resolve.begin", input=url_preview(input_text), auth_mode="anonymous")
        result = asyncio.run(_resolve_async(input_text, flow, cursor=cursor))
    except Exception as exc:
        flow.mark_total()
        flow.error("resolve.failed", total_ms=flow.timings.get("total_ms"), error=str(exc))
        diagnostics["error"] = _safe_diagnostic_text(str(exc))
        diagnostics["response_summary"] = "bridge exception"
        result = _error(str(exc), timings=dict(flow.timings), diagnostics=diagnostics)
        result["traceback"] = _safe_diagnostic_traceback(traceback.format_exc(limit=12))
    # ResolveResultParser requires v2 even for errors, otherwise the actionable
    # login/network message is replaced with a missing-schema protocol error.
    result.setdefault("schema_version", 2)
    return json.dumps(result, ensure_ascii=False)


def download(input_text: str, request_json=None, progress_callback=None) -> str:
    flow = new_flow_logger()
    diagnostics = _new_diagnostics(input_text)
    try:
        if request_json is not None and not isinstance(request_json, str):
            progress_callback = request_json
            request_json = None
        request = _parse_entry_download_request(request_json)
        flow.info("download.begin", input=url_preview(input_text), auth_mode="anonymous")
        result = asyncio.run(_download_async(input_text, flow, progress_callback, request))
    except Exception as exc:
        flow.mark_total()
        flow.error("download.failed", total_ms=flow.timings.get("total_ms"), error=str(exc))
        diagnostics["error"] = _safe_diagnostic_text(str(exc))
        diagnostics["response_summary"] = "bridge exception"
        result = _error(str(exc), timings=dict(flow.timings), diagnostics=diagnostics)
        result["traceback"] = _safe_diagnostic_traceback(traceback.format_exc(limit=12))
    return json.dumps(result, ensure_ascii=False)


def _parse_entry_download_request(request_json):
    if request_json:
        try:
            request = json.loads(request_json)
        except (TypeError, ValueError):
            return parse_download_request(request_json)
        if isinstance(request, dict) and str((request.get("source") or {}).get("id") or "").startswith("profile-"):
            return request  # The shared collection downloader validates this protocol.
    return parse_download_request(request_json)


async def _resolve_profile_response(input_text, user_id, config, flow, cursor):
    try:
        with flow.stage("resolve_author_posts"):
            result = await resolve_profile(input_text, user_id, cookies=_runtime_cookies(config),
                                           proxy=config.get("proxy"), cursor=cursor)
    except Exception as exc:
        diagnostics = _new_diagnostics(input_text, response_summary="profile posts unavailable")
        diagnostics["channel"] = "douyin_signed_profile"
        diagnostics["error"] = _safe_diagnostic_text(str(exc))
        _append_stage(diagnostics, "resolve_author_posts", "failed", flow.timings.get("resolve_author_posts_ms"), exc)
        result = _error(diagnostics["error"], diagnostics=diagnostics)
        result["source"] = {"platform": "douyin", "input_url": input_text,
                            "resolved_url": canonical_profile_url(user_id), "id": f"profile-{user_id}"}
    flow.mark_total()
    result.update({"schema_version": 2, "timings": dict(flow.timings), "download_metrics": [], "api_metrics": []})
    return result


async def _resolve_async(input_text: str, flow: AndroidFlowLogger, cursor=None) -> Dict[str, Any]:
    diagnostics = _new_diagnostics(input_text)
    config = _android_global_config.config_loader
    if config is None:
        diagnostics["error"] = "Python runtime is not initialized"
        return _error("Python runtime is not initialized", diagnostics=diagnostics)
    if not str(input_text or "").strip():
        diagnostics["error"] = "请先粘贴抖音分享文本或链接"
        return _error("请先粘贴抖音分享文本或链接", diagnostics=diagnostics)

    user_id = profile_user_id(input_text)
    if user_id:
        return await _resolve_profile_response(input_text, user_id, config, flow, cursor)
    try:
        resolved = await _resolve_anonymous_or_cookie_fallback(input_text, config, flow, diagnostics)
    except anonymous_share.AnonymousDouyinProfile as profile:
        return await _resolve_profile_response(input_text, profile.user_id, config, flow, cursor)
    if cursor is not None:
        return _error("只有博主主页支持继续提取", diagnostics=diagnostics)
    if resolved is None:
        return _error(diagnostics["error"], timings=dict(flow.timings), diagnostics=diagnostics)

    flow.mark_total()
    response = _build_response(resolved)
    response.update({"timings": dict(flow.timings), "download_metrics": [], "api_metrics": [], "diagnostics": diagnostics})
    return response


async def _download_async(
    input_text: str,
    flow: AndroidFlowLogger,
    progress_callback=None,
    request: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    config = _android_global_config.config_loader
    if config is None:
        return _error("Python runtime is not initialized", diagnostics=_new_diagnostics(input_text))

    if isinstance(request, dict) and str((request.get("source") or {}).get("id") or "").startswith("profile-"):
        from common.profile_download import download_collection_snapshot
        source_url = str((request.get("source") or {}).get("url") or "")
        user_id = profile_user_id(source_url)
        input_id = profile_user_id(input_text)
        if not user_id or (input_id and input_id != user_id):
            raise ValueError("下载主页与预览不一致，请重新解析")
        if not input_id:
            normalized_input = anonymous_share._normalize_input_url(input_text)
            anonymous_share._validate_douyin_url(normalized_input)
            if not anonymous_share._is_short_host(urlparse(normalized_input).hostname or ""):
                raise ValueError("下载主页与当前链接不一致，请重新解析")
        return await download_collection_snapshot(
            request, Path(config.get("path")) / "Douyin", progress_callback,
            platform="douyin", expected_source_id=f"profile-{user_id}",
            allowed_host_suffixes=PROFILE_MEDIA_HOSTS,
            headers={"Referer": "https://www.douyin.com/", "User-Agent": anonymous_share._USER_AGENT},
        )

    source_input = select_download_source_url(input_text, request)
    if not str(source_input or "").strip():
        diagnostics = _new_diagnostics(input_text)
        diagnostics["error"] = "请先粘贴抖音分享文本或链接"
        return _error("请先粘贴抖音分享文本或链接", diagnostics=diagnostics)

    output_root = Path(config.get("path"))
    reporter = AndroidProgressReporter(progress_callback)
    retries = int(config.get("retry_times", _ANDROID_RETRY_TIMES) or _ANDROID_RETRY_TIMES)
    cookies = _runtime_cookies(config)
    snapshot_context = build_snapshot_download_context(request)
    if snapshot_context is not None:
        snapshot_aweme, snapshot_work = snapshot_context
        with flow.stage(
            "download_preview_snapshot",
            resource_type=request["selection"]["resource_type"],
        ):
            snapshot_result = await _download_with_context(
                request=request,
                aweme_data=snapshot_aweme,
                work=snapshot_work,
                reporter=reporter,
                retries=retries,
                proxy=config.get("proxy"),
                output_root=output_root,
                cookies=cookies,
            )
        if snapshot_result.get("ok") or int(snapshot_result.get("success") or 0) > 0:
            flow.mark_total()
            snapshot_result.update(
                {
                    "url": request["source"]["url"],
                    "type": request["expected_work_type"],
                    "timings": dict(flow.timings),
                    "download_metrics": [],
                    "api_metrics": [],
                    "used_preview_snapshot": True,
                    "diagnostics": _new_diagnostics(source_input, response_summary="preview snapshot download"),
                }
            )
            return snapshot_result

        # A preview URL may eventually expire. Only when every snapshot asset
        # failed do we refresh the public page once as a compatibility fallback.
        flow.info("download_preview_snapshot.exhausted", fallback="resolve_public_share")

    diagnostics = _new_diagnostics(source_input)
    resolved = await _resolve_anonymous_or_cookie_fallback(source_input, config, flow, diagnostics)
    if resolved is None:
        return _error(
            diagnostics["error"],
            output_root=output_root,
            timings=dict(flow.timings),
            diagnostics=diagnostics,
        )

    requested_id = str((request or {}).get("source", {}).get("id") or "")
    if requested_id and requested_id != resolved.source_id:
        return _error("下载链接与已解析作品不一致，请重新解析", output_root=output_root)

    work = normalize_aweme(resolved.aweme_data)
    effective_request = request or _default_download_request(resolved, work)
    with flow.stage(
        "download_selected",
        resource_type=effective_request["selection"]["resource_type"],
    ):
        result = await _download_with_context(
            request=effective_request,
            aweme_data=resolved.aweme_data,
            work=work,
            reporter=reporter,
            retries=retries,
            proxy=config.get("proxy"),
            output_root=output_root,
            cookies=cookies,
        )

    flow.mark_total()
    result.update(
        {
            "url": resolved.resolved_url,
            "type": resolved.share_type,
            "timings": dict(flow.timings),
            "download_metrics": [],
            "api_metrics": [],
            "diagnostics": diagnostics,
        }
    )
    return result


async def _download_with_context(
    *,
    request: Dict[str, Any],
    aweme_data: Dict[str, Any],
    work: Dict[str, Any],
    reporter: AndroidProgressReporter,
    retries: int,
    proxy: Optional[str],
    output_root: Path,
    cookies: Optional[Dict[str, str]] = None,
) -> Dict[str, Any]:
    async with AnonymousMediaDownloader(
        reporter,
        proxy=proxy,
        retries=retries,
        cookies=cookies,
    ) as downloader:
        return await download_selected_resources(
            request=request,
            aweme_data=aweme_data,
            work=work,
            downloader=downloader,
            output_root=output_root,
        )


def _parse_cookies(cookie_header: str) -> Dict[str, str]:
    raw = str(cookie_header or "").strip()
    if not raw:
        return {}
    try:
        if raw.startswith("{"):
            parsed = json.loads(raw)
            if isinstance(parsed, dict):
                return sanitize_cookies(parsed)
    except (TypeError, ValueError, json.JSONDecodeError):
        pass
    return sanitize_cookies(parse_cookie_header(raw))


def _runtime_cookies(config: ConfigLoader) -> Dict[str, str]:
    return sanitize_cookies(config.get_cookies() or {})


async def _resolve_anonymous_or_cookie_fallback(
    input_text: str,
    config: Any,
    flow: AndroidFlowLogger,
    diagnostics: Dict[str, Any],
) -> Optional[AnonymousDouyinResult]:
    """匿名分享解析；失败且配置了 Cookie 时降级签名客户端再试一次。

    成功返回 AnonymousDouyinResult；两条路线都没拿到作品数据时返回 None，
    失败细节（stage/error/response_summary）写入传入的 diagnostics。
    """
    try:
        with flow.stage("resolve_public_share", input=url_preview(input_text)):
            resolved = await resolve_public_douyin(input_text, proxy=config.get("proxy"))
    except anonymous_share.AnonymousDouyinProfile:
        raise
    except AnonymousDouyinError as exc:
        _append_stage(diagnostics, "resolve_public_share", "failed", flow.timings.get("resolve_public_share_ms"), exc)
        diagnostics["response_summary"] = _response_summary(str(exc), "public_share")
        cookies = _runtime_cookies(config)
        if not cookies:
            diagnostics["error"] = _safe_diagnostic_text(str(exc))
            return None
        diagnostics["retry_count"] = 1
        diagnostics["fallback_used"] = True
        diagnostics["channel"] = "cookie_fallback"
        try:
            with flow.stage("resolve_cookie_fallback", input=url_preview(input_text)):
                resolved = await _resolve_with_cookies(
                    input_text,
                    cookies=cookies,
                    proxy=config.get("proxy"),
                )
        except Exception as fallback_exc:
            _append_stage(
                diagnostics,
                "resolve_cookie_fallback",
                "failed",
                flow.timings.get("resolve_cookie_fallback_ms"),
                fallback_exc,
            )
            flow.warning("resolve_cookie_fallback.failed", error=fallback_exc.__class__.__name__)
            resolved = None
        if resolved is None:
            if not any(stage.get("name") == "resolve_cookie_fallback" for stage in diagnostics["stages"]):
                _append_stage(
                    diagnostics,
                    "resolve_cookie_fallback",
                    "failed",
                    flow.timings.get("resolve_cookie_fallback_ms"),
                    AnonymousDouyinError("cookie detail response: no work data"),
                )
            diagnostics["error"] = _safe_diagnostic_text(str(exc))
            return None
        _append_stage(diagnostics, "resolve_cookie_fallback", "done", flow.timings.get("resolve_cookie_fallback_ms"))
        diagnostics["response_summary"] = "cookie detail response: work data available"
        return resolved
    except Exception as exc:
        _append_stage(diagnostics, "resolve_public_share", "failed", flow.timings.get("resolve_public_share_ms"), exc)
        diagnostics["error"] = _safe_diagnostic_text(str(exc))
        diagnostics["response_summary"] = _response_summary(str(exc), "public_share")
        return None
    else:
        _append_stage(diagnostics, "resolve_public_share", "done", flow.timings.get("resolve_public_share_ms"))
        diagnostics["response_summary"] = "public share response: work data available"
        return resolved


async def _resolve_with_cookies(
    input_text: str,
    *,
    cookies: Dict[str, str],
    proxy: Optional[str],
) -> Optional[AnonymousDouyinResult]:
    # Keep the anonymous Android entry lightweight; the legacy signed client
    # pulls optional desktop-only dependencies and is loaded only on fallback.
    from core import DouyinAPIClient, URLParser

    normalized = anonymous_share._normalize_input_url(input_text)
    resolved_url = normalized
    async with DouyinAPIClient(cookies, proxy=proxy) as api_client:
        if anonymous_share._is_short_host(urlparse(normalized).hostname or ""):
            resolved_url = await api_client.resolve_short_url(normalized) or normalized
        parsed = URLParser.parse(resolved_url)
        source_id = str((parsed or {}).get("aweme_id") or (parsed or {}).get("note_id") or "")
        if not source_id:
            route = anonymous_share._extract_route(resolved_url)
            source_id = route[1] if route else anonymous_share._extract_id(resolved_url)
        if not source_id:
            return None
        aweme_data = await api_client.get_video_detail(source_id, suppress_error=True)
    if not aweme_data:
        return None
    parsed_type = str((parsed or {}).get("type") or "video")
    return AnonymousDouyinResult(
        input_url=normalized,
        resolved_url=resolved_url,
        source_id=str(aweme_data.get("aweme_id") or source_id),
        share_type=parsed_type,
        aweme_data=aweme_data,
    )


def _build_response(resolved: AnonymousDouyinResult) -> Dict[str, Any]:
    return build_resolve_response(
        resolved.aweme_data,
        input_url=resolved.input_url,
        resolved_url=resolved.resolved_url,
        source_id=resolved.source_id,
    )


def _default_download_request(
    resolved: AnonymousDouyinResult,
    work: Dict[str, Any],
) -> Dict[str, Any]:
    work_type = str(work.get("type") or "")
    resource_type = "video" if work_type == "video" else "image"
    bucket = "videos" if resource_type == "video" else "images"
    resources = work.get("resources", {}).get(bucket, [])
    return {
        "schema_version": 2,
        "source": {
            "platform": "douyin",
            "url": resolved.resolved_url,
            "id": resolved.source_id,
        },
        "expected_work_type": work_type,
        "selection": {
            "resource_type": resource_type,
            "resource_ids": [str(item.get("id") or "") for item in resources if item.get("id")],
            "include_live_video": bool(
                resource_type == "image"
                and work.get("capabilities", {}).get("has_live_video")
            ),
        },
    }


def _error(
    message: str,
    output_root: Optional[Path] = None,
    timings: Optional[Dict[str, int]] = None,
    diagnostics: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    result = build_error_response(
        message,
        output_root=output_root,
        timings=timings,
        total=0,
    )
    if diagnostics is not None:
        result["diagnostics"] = diagnostics
    return result


def _new_diagnostics(
    input_text: str,
    *,
    response_summary: str = "",
) -> Dict[str, Any]:
    return {
        "channel": "anonymous",
        "input_url": _safe_diagnostic_url(input_text),
        "stages": [],
        "error": "",
        "response_summary": _safe_diagnostic_text(response_summary),
        "retry_count": 0,
        "fallback_used": False,
    }


def _append_stage(
    diagnostics: Dict[str, Any],
    name: str,
    status: str,
    duration_ms: Optional[int] = None,
    exc: Optional[BaseException] = None,
) -> None:
    stage: Dict[str, Any] = {"name": str(name), "status": str(status)}
    if duration_ms is not None:
        stage["duration_ms"] = max(0, int(duration_ms))
    if exc is not None:
        stage["error"] = _safe_diagnostic_text(str(exc))
        stage["error_type"] = exc.__class__.__name__
    diagnostics.setdefault("stages", []).append(stage)


def _response_summary(error: str, source: str) -> str:
    text = _safe_diagnostic_text(error)
    return f"{source}: {text}" if text else f"{source}: no usable work data"


def _safe_diagnostic_url(value: Any) -> str:
    """Keep only the public URL origin/path; never copy query tokens or share signatures."""
    raw = str(value or "").replace("\n", " ").strip()
    candidate = extract_first_url(raw) or raw
    try:
        parsed = urlparse(candidate)
        if parsed.scheme and parsed.netloc:
            return f"{parsed.scheme}://{parsed.netloc}{parsed.path}"[:240]
    except Exception:
        pass
    return _safe_diagnostic_text(url_preview(raw, 180))


def _safe_diagnostic_text(value: Any) -> str:
    return redact_sensitive_text(value)


def _safe_diagnostic_traceback(value: Any) -> str:
    """Preserve stack context while applying the same credential/URL redaction."""
    return redact_sensitive_text(value, max_length=8000, flatten=False)
