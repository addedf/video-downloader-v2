"""Android-only selected resource downloader for Resolve/Download protocol v2."""

from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import urlparse

from utils.validators import is_short_url, sanitize_filename


SUPPORTED_RESOURCE_TYPES = {"video", "image", "cover", "audio"}
SUPPORTED_WORK_TYPES = {"video", "gallery", "live_photo"}
_MAX_SNAPSHOT_RESOURCES = 100
_MAX_URLS_PER_RESOURCE = 8
_MAX_URL_LENGTH = 4096


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
    if expected_work_type not in SUPPORTED_WORK_TYPES:
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
    snapshot = request.get("snapshot")
    if snapshot is not None:
        request["snapshot"] = _validate_snapshot(snapshot, source, expected_work_type)
    return request


def build_snapshot_download_context(
    request: Optional[Dict[str, Any]],
) -> Optional[Tuple[Dict[str, Any], Dict[str, Any]]]:
    """Build the downloader's minimal aweme/work inputs from a validated snapshot."""
    if not isinstance(request, dict):
        return None
    snapshot = request.get("snapshot")
    if not isinstance(snapshot, dict):
        return None

    grouped: Dict[str, List[Dict[str, Any]]] = {
        "videos": [],
        "images": [],
        "covers": [],
        "audios": [],
    }
    for resource in snapshot.get("resources") or []:
        resource_type = str(resource.get("type") or "")
        grouped[_resource_bucket(resource_type)].append(resource)

    live_video_count = sum(
        1
        for resource in grouped["images"]
        if isinstance(resource.get("live_video"), dict)
        and resource["live_video"].get("available")
    )
    work = {
        "type": snapshot["work_type"],
        "title": snapshot.get("title") or "无标题作品",
        "author": {"id": "", "name": snapshot.get("author") or "未知作者"},
        "capabilities": {
            "has_video": bool(grouped["videos"]),
            "has_images": bool(grouped["images"]),
            "has_cover": bool(grouped["covers"]),
            "has_audio": bool(grouped["audios"]),
            "has_live_video": live_video_count > 0,
        },
        "counts": {
            "videos": len(grouped["videos"]),
            "images": len(grouped["images"]),
            "covers": len(grouped["covers"]),
            "audios": len(grouped["audios"]),
            "live_videos": live_video_count,
        },
        "resources": grouped,
    }
    aweme_data = {"aweme_id": snapshot["source_id"]}
    return aweme_data, work


def _validate_snapshot(
    snapshot: Any,
    source: Dict[str, Any],
    expected_work_type: str,
) -> Dict[str, Any]:
    if not isinstance(snapshot, dict):
        raise ValueError("下载快照格式错误")
    source_id = snapshot.get("source_id")
    if not _non_empty_string(source_id) or source_id.strip() != str(source.get("id") or "").strip():
        raise ValueError("下载快照与作品 ID 不一致")
    work_type = snapshot.get("work_type")
    if work_type not in SUPPORTED_WORK_TYPES or work_type != expected_work_type:
        raise ValueError("下载快照包含无效的作品类型")
    title = snapshot.get("title", "")
    author = snapshot.get("author", "")
    if not isinstance(title, str) or not isinstance(author, str):
        raise ValueError("下载快照标题或作者格式错误")
    resources = snapshot.get("resources")
    if not isinstance(resources, list) or not resources:
        raise ValueError("下载快照没有可用资源")
    if len(resources) > _MAX_SNAPSHOT_RESOURCES:
        raise ValueError("下载快照资源数量过多")

    normalized_resources: List[Dict[str, Any]] = []
    seen_ids = set()
    for resource in resources:
        normalized = _validate_snapshot_resource(resource)
        if normalized["id"] in seen_ids:
            raise ValueError("下载快照包含重复资源 ID")
        seen_ids.add(normalized["id"])
        normalized_resources.append(normalized)
    return {
        "source_id": source_id.strip(),
        "title": title[:500],
        "author": author[:200],
        "work_type": work_type,
        "resources": normalized_resources,
    }


def _validate_snapshot_resource(resource: Any) -> Dict[str, Any]:
    if not isinstance(resource, dict):
        raise ValueError("下载快照资源格式错误")
    resource_id = resource.get("id")
    resource_type = resource.get("type")
    if not _non_empty_string(resource_id) or len(resource_id.strip()) > 128:
        raise ValueError("下载快照资源 ID 无效")
    if resource_type not in SUPPORTED_RESOURCE_TYPES:
        raise ValueError("下载快照资源类型无效")
    urls = _validate_snapshot_urls(resource.get("download_urls"), required=True)
    live = resource.get("live_video")
    normalized_live = None
    if live is not None:
        if not isinstance(live, dict) or not isinstance(live.get("available", False), bool):
            raise ValueError("下载快照 Live 视频格式错误")
        live_urls = _validate_snapshot_urls(
            live.get("download_urls", []), required=bool(live.get("available"))
        )
        normalized_live = {
            "available": bool(live.get("available")),
            "download_urls": live_urls,
            "width": _optional_non_negative_int(live.get("width")),
            "height": _optional_non_negative_int(live.get("height")),
            "duration_ms": _optional_non_negative_int(live.get("duration_ms")),
            "format_hint": str(live.get("format_hint") or "")[:16],
        }
    return {
        "id": resource_id.strip(),
        "index": _optional_non_negative_int(resource.get("index")) or 0,
        "type": resource_type,
        "title": str(resource.get("title") or "")[:200],
        "download_urls": urls,
        "width": _optional_non_negative_int(resource.get("width")),
        "height": _optional_non_negative_int(resource.get("height")),
        "duration_ms": _optional_non_negative_int(resource.get("duration_ms")),
        "format_hint": str(resource.get("format_hint") or "")[:16],
        "live_video": normalized_live,
    }


def _validate_snapshot_urls(value: Any, *, required: bool) -> List[str]:
    if not isinstance(value, list) or len(value) > _MAX_URLS_PER_RESOURCE:
        raise ValueError("下载快照媒体地址格式错误")
    urls: List[str] = []
    for raw_url in value:
        if not isinstance(raw_url, str) or len(raw_url) > _MAX_URL_LENGTH:
            raise ValueError("下载快照媒体地址无效")
        parsed = urlparse(raw_url)
        if (
            parsed.scheme.lower() != "https"
            or not parsed.hostname
            or parsed.username is not None
            or parsed.password is not None
        ):
            raise ValueError("下载快照只允许 HTTPS 媒体地址")
        if raw_url not in urls:
            urls.append(raw_url)
    if required and not urls:
        raise ValueError("下载快照资源缺少 HTTPS 媒体地址")
    return urls


def _optional_non_negative_int(value: Any) -> Optional[int]:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError("下载快照资源尺寸或时长无效")
    return value


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
        if not url.startswith("https://"):
            continue
        result.append((url, downloader._download_headers()))
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
