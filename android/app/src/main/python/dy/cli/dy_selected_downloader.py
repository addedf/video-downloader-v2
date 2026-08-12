"""Android-only selected resource downloader for Resolve/Download protocol v2."""

from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import urlparse

from utils.validators import is_short_url, sanitize_filename


SUPPORTED_RESOURCE_TYPES = {"video", "image", "cover", "audio"}


def select_download_source_url(
    input_text: str, request: Optional[Dict[str, Any]]
) -> str:
    """Prefer the canonical URL captured by resolve protocol v2.

    A selected download starts from a successfully resolved preview, so its
    request source is more reliable than resolving the pasted short link a
    second time. Keep the original input as the compatibility fallback for
    legacy downloads and malformed/short request sources.
    """
    fallback = str(input_text or "").strip()
    if not isinstance(request, dict):
        return fallback
    source = request.get("source")
    if not isinstance(source, dict):
        return fallback
    source_url = str(source.get("url") or "").strip()
    if not source_url or is_short_url(source_url):
        return fallback
    return source_url


def parse_download_request(raw: Optional[str]) -> Optional[Dict[str, Any]]:
    if raw is None or not str(raw).strip():
        return None
    try:
        request = json.loads(str(raw))
    except json.JSONDecodeError as exc:
        raise ValueError("下载选择参数不是有效 JSON") from exc
    if not isinstance(request, dict):
        raise ValueError("下载选择参数格式错误")
    if request.get("schema_version") != 2:
        raise ValueError("不支持的下载选择协议版本")
    source = request.get("source")
    if not isinstance(source, dict):
        raise ValueError("下载选择缺少 source")
    if source.get("platform") != "douyin":
        raise ValueError("下载选择来源不是抖音")
    if not _non_empty_string(source.get("url")) or not _non_empty_string(source.get("id")):
        raise ValueError("下载选择缺少有效的作品链接或 ID")
    expected_work_type = request.get("expected_work_type")
    if expected_work_type not in {"video", "gallery", "live_photo"}:
        raise ValueError("下载选择包含无效的作品类型")
    selection = request.get("selection")
    if not isinstance(selection, dict):
        raise ValueError("下载选择缺少 selection")
    resource_type = selection.get("resource_type")
    if resource_type not in SUPPORTED_RESOURCE_TYPES:
        raise ValueError("不支持的保存内容类型")
    resource_ids = selection.get("resource_ids", [])
    if not isinstance(resource_ids, list) or not all(isinstance(item, str) for item in resource_ids):
        raise ValueError("resource_ids 必须是字符串数组")
    if any(not item.strip() for item in resource_ids):
        raise ValueError("resource_ids 不能包含空值")
    if not isinstance(selection.get("include_live_video", False), bool):
        raise ValueError("include_live_video 必须是布尔值")
    return request


async def download_selected_resources(
    *,
    request: Dict[str, Any],
    aweme_data: Dict[str, Any],
    work: Dict[str, Any],
    downloader,
    output_root: Path,
) -> Dict[str, Any]:
    selection = request["selection"]
    resource_type = selection["resource_type"]
    include_live_video = bool(selection.get("include_live_video", False))
    expected_work_type = str(request.get("expected_work_type") or "")
    work_type = str(work.get("type") or "")

    if expected_work_type and expected_work_type != work_type:
        return _failure(f"作品类型已从 {expected_work_type} 变为 {work_type}，请重新解析", output_root)
    validation_error = _validate_combination(work, resource_type, include_live_video)
    if validation_error:
        return _failure(validation_error, output_root)

    resources = work.get("resources", {}).get(_resource_bucket(resource_type), [])
    requested_ids = set(selection.get("resource_ids") or [])
    if requested_ids:
        resources = [item for item in resources if item.get("id") in requested_ids]
        found_ids = {str(item.get("id") or "") for item in resources}
        missing_ids = requested_ids - found_ids
        if missing_ids:
            return _failure("部分所选资源已失效，请重新解析", output_root)
    if not resources:
        return _failure("当前作品没有可保存的所选资源", output_root)

    author = work.get("author") if isinstance(work.get("author"), dict) else {}
    author_name = sanitize_filename(str(author.get("name") or "未知作者"))
    source_id = str(aweme_data.get("aweme_id") or request.get("source", {}).get("id") or "")
    title = sanitize_filename(str(work.get("title") or "无标题作品"))
    publish_date = _publish_date(aweme_data.get("create_time"))
    stem = sanitize_filename(f"{publish_date}_{title}_{source_id}")
    save_dir = output_root / author_name / stem
    save_dir.mkdir(parents=True, exist_ok=True)

    downloader.file_manager._android_progress_reporter = downloader.progress_reporter
    session = await downloader.api_client.get_session()
    saved_assets: List[Dict[str, str]] = []
    failed = 0

    total_assets = len(resources)
    if resource_type == "image" and include_live_video:
        total_assets += sum(1 for item in resources if item.get("live_video", {}).get("available"))
    if downloader.progress_reporter:
        downloader.progress_reporter.set_item_total(total_assets, "按所选内容保存")

    for resource in resources:
        saved_path = await _download_resource(
            downloader=downloader,
            session=session,
            resource=resource,
            save_dir=save_dir,
            stem=stem,
            suffix_label=_suffix_label(resource_type, resource),
            aweme_data=aweme_data,
        )
        if saved_path:
            saved_assets.append(
                {
                    "resource_id": str(resource.get("id") or ""),
                    "media_type": resource_type,
                    "path": str(saved_path),
                }
            )
            _advance(downloader, "success", str(resource.get("id") or resource_type))
        else:
            failed += 1
            _advance(downloader, "failed", str(resource.get("id") or resource_type))

        if resource_type == "image" and include_live_video:
            live = resource.get("live_video") if isinstance(resource.get("live_video"), dict) else {}
            if live.get("available"):
                live_resource = {
                    "id": f"{resource.get('id')}:live_video",
                    "type": "video",
                    "download_urls": live.get("download_urls") or [],
                    "format_hint": live.get("format_hint") or "mp4",
                }
                live_path = await _download_resource(
                    downloader=downloader,
                    session=session,
                    resource=live_resource,
                    save_dir=save_dir,
                    stem=stem,
                    suffix_label=f"image_{int(resource.get('index') or 1):02d}_live",
                    aweme_data=aweme_data,
                )
                if live_path:
                    saved_assets.append(
                        {
                            "resource_id": str(live_resource["id"]),
                            "media_type": "video",
                            "path": str(live_path),
                        }
                    )
                    _advance(downloader, "success", str(live_resource["id"]))
                else:
                    failed += 1
                    _advance(downloader, "failed", str(live_resource["id"]))

    files = [asset["path"] for asset in saved_assets]
    success = len(saved_assets)
    if success and failed:
        message = f"已保存 {success} 个文件，{failed} 个资源保存失败"
    elif success:
        message = f"下载完成，新增 {success} 个文件"
    else:
        message = "下载失败"
    return {
        "ok": success > 0,
        "message": message,
        "error": None if failed == 0 else f"有 {failed} 个资源保存失败",
        "output_dir": str(output_root),
        "files": files,
        "saved_assets": saved_assets,
        "total": total_assets,
        "success": success,
        "failed": failed,
        "skipped": 0,
    }


def _validate_combination(
    work: Dict[str, Any], resource_type: str, include_live_video: bool
) -> Optional[str]:
    work_type = work.get("type")
    if resource_type == "video" and work_type != "video":
        return "图集或 Live 图作品不能使用视频 Tab 保存"
    if resource_type == "image" and work_type not in {"gallery", "live_photo"}:
        return "视频作品不能使用图片 Tab 保存"
    if include_live_video and not (
        resource_type == "image"
        and work_type == "live_photo"
        and work.get("capabilities", {}).get("has_live_video")
    ):
        return "当前作品或保存类型不支持 Live 视频"
    return None


async def _download_resource(
    *,
    downloader,
    session,
    resource: Dict[str, Any],
    save_dir: Path,
    stem: str,
    suffix_label: str,
    aweme_data: Dict[str, Any],
) -> Optional[Path]:
    extension = _safe_extension(resource.get("format_hint"), resource.get("type"))
    save_path = save_dir / f"{stem}_{suffix_label}.{extension}"
    candidates = _candidate_urls(downloader, resource, aweme_data)
    for url, headers in candidates:
        result = await downloader._download_with_retry(
            url,
            save_path,
            session,
            headers=headers,
            # Image URLs often omit a useful extension. Video and audio keep their
            # protocol-declared suffix so an audio/mp4 response cannot become .mp4.
            prefer_response_content_type=resource.get("type") in {"image", "cover"},
            return_saved_path=True,
        )
        if result:
            return result if isinstance(result, Path) else save_path
    return None


def _candidate_urls(
    downloader, resource: Dict[str, Any], aweme_data: Dict[str, Any]
) -> List[Tuple[str, Dict[str, str]]]:
    result: List[Tuple[str, Dict[str, str]]] = []
    if resource.get("type") == "video" and resource.get("id") == "video_1":
        built = downloader._build_no_watermark_url(aweme_data)
        if built:
            result.append(built)
    for url in resource.get("download_urls") or []:
        if not isinstance(url, str) or not url:
            continue
        headers = downloader._download_headers()
        host = (urlparse(url).hostname or "").lower()
        if host.endswith("douyin.com") and "X-Bogus=" not in url:
            try:
                url, user_agent = downloader.api_client.sign_url(url)
                headers = downloader._download_headers(user_agent=user_agent)
            except Exception:
                pass
        result.append((url, headers))
    deduped: List[Tuple[str, Dict[str, str]]] = []
    seen = set()
    for candidate in result:
        if candidate[0] in seen:
            continue
        seen.add(candidate[0])
        deduped.append(candidate)
    return deduped


def _resource_bucket(resource_type: str) -> str:
    return {"video": "videos", "image": "images", "cover": "covers", "audio": "audios"}[
        resource_type
    ]


def _suffix_label(resource_type: str, resource: Dict[str, Any]) -> str:
    if resource_type == "video":
        return "video"
    if resource_type == "cover":
        return "cover"
    if resource_type == "audio":
        return "audio"
    return f"image_{int(resource.get('index') or 1):02d}"


def _safe_extension(format_hint: Any, resource_type: Any) -> str:
    hint = str(format_hint or "").lower().lstrip(".")
    allowed = {"mp4", "mov", "m4a", "mp3", "jpg", "jpeg", "png", "webp", "gif"}
    if hint in allowed:
        return "jpg" if hint == "jpeg" else hint
    return {"video": "mp4", "audio": "m4a", "cover": "jpg", "image": "jpg"}.get(
        str(resource_type), "bin"
    )


def _non_empty_string(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _publish_date(value: Any) -> str:
    try:
        timestamp = int(value or 0)
        if timestamp > 0:
            return datetime.fromtimestamp(timestamp).strftime("%Y-%m-%d")
    except (TypeError, ValueError, OSError, OverflowError):
        pass
    return datetime.now().strftime("%Y-%m-%d")


def _advance(downloader, status: str, detail: str) -> None:
    if downloader.progress_reporter:
        downloader.progress_reporter.advance_item(status, detail)


def _failure(message: str, output_root: Path) -> Dict[str, Any]:
    return {
        "ok": False,
        "message": message,
        "error": message,
        "output_dir": str(output_root),
        "files": [],
        "saved_assets": [],
        "total": 0,
        "success": 0,
        "failed": 1,
        "skipped": 0,
    }
