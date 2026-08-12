"""Normalize unstable Douyin detail responses into the Android v2 protocol."""

from __future__ import annotations

from typing import Any, Dict, Iterable, List, Optional, Tuple
from urllib.parse import urlparse


GALLERY_AWEME_TYPES = {2, 68, 150}


def build_resolve_response(
    aweme_data: Dict[str, Any],
    *,
    input_url: str,
    resolved_url: str,
    source_id: str,
) -> Dict[str, Any]:
    return {
        "schema_version": 2,
        "ok": True,
        "message": "解析成功",
        "error": None,
        "source": {
            "platform": "douyin",
            "input_url": input_url,
            "resolved_url": resolved_url,
            "id": str(source_id or aweme_data.get("aweme_id") or ""),
        },
        "work": normalize_aweme(aweme_data),
    }


def normalize_aweme(aweme_data: Dict[str, Any]) -> Dict[str, Any]:
    author = aweme_data.get("author") if isinstance(aweme_data.get("author"), dict) else {}
    title = str(aweme_data.get("desc") or "无标题作品").strip() or "无标题作品"
    author_name = str(author.get("nickname") or "未知作者").strip() or "未知作者"
    author_id = str(author.get("sec_uid") or author.get("uid") or "")

    images = _build_image_resources(aweme_data)
    covers = _build_cover_resources(aweme_data, images)
    cover_previews = covers[0]["preview_urls"] if covers else []
    videos = _build_video_resources(aweme_data, cover_previews)
    audios = _build_audio_resources(aweme_data, cover_previews)
    live_video_count = sum(
        1 for image in images if image.get("live_video", {}).get("available")
    )

    if images:
        work_type = "live_photo" if live_video_count else "gallery"
    else:
        work_type = "video"

    capabilities = {
        "has_video": bool(videos),
        "has_images": bool(images),
        "has_cover": bool(covers),
        "has_audio": bool(audios),
        "has_live_video": live_video_count > 0,
    }
    counts = {
        "videos": len(videos),
        "images": len(images),
        "covers": len(covers),
        "audios": len(audios),
        "live_videos": live_video_count,
    }

    return {
        "type": work_type,
        "title": title,
        "author": {"id": author_id, "name": author_name},
        "capabilities": capabilities,
        "counts": counts,
        "resources": {
            "videos": videos,
            "images": images,
            "covers": covers,
            "audios": audios,
        },
        "diagnostics": {
            "aweme_type": aweme_data.get("aweme_type"),
            "gallery_hint": _has_gallery_hint(aweme_data),
        },
    }


def _build_video_resources(
    aweme_data: Dict[str, Any], cover_previews: List[str]
) -> List[Dict[str, Any]]:
    if _gallery_items(aweme_data):
        return []
    video = aweme_data.get("video") if isinstance(aweme_data.get("video"), dict) else {}
    preferred = _highest_quality_play_addr(video)
    urls = _collect_urls(
        preferred,
        video.get("play_addr_h264"),
        video.get("play_addr"),
        video.get("download_addr"),
    )
    if not urls:
        return []
    width, height = _dimensions(preferred, video)
    return [
        _resource(
            resource_id="video_1",
            index=1,
            resource_type="video",
            title="无水印视频",
            preview_urls=cover_previews,
            download_urls=urls,
            width=width,
            height=height,
            duration_ms=_duration_ms(video.get("duration") or aweme_data.get("duration")),
            format_hint="mp4",
        )
    ]


def _build_image_resources(aweme_data: Dict[str, Any]) -> List[Dict[str, Any]]:
    resources: List[Dict[str, Any]] = []
    for index, item in enumerate(_gallery_items(aweme_data), start=1):
        if not isinstance(item, dict):
            continue
        download_urls = _collect_urls(
            item.get("watermark_free_download_url_list"),
            item,
            item.get("origin_image"),
            item.get("display_image"),
            item.get("download_url"),
            item.get("download_addr"),
            item.get("download_url_list"),
            item.get("owner_watermark_image"),
        )
        if not download_urls:
            continue
        preview_urls = _collect_urls(
            item.get("display_image"), item.get("origin_image"), download_urls
        )
        live_video = _build_live_video(item)
        width, height = _dimensions(
            item.get("origin_image"), item.get("display_image"), item
        )
        resources.append(
            {
                **_resource(
                    resource_id=f"image_{index}",
                    index=index,
                    resource_type="image",
                    title=f"原图 {index:02d}",
                    preview_urls=preview_urls,
                    download_urls=download_urls,
                    width=width,
                    height=height,
                    duration_ms=None,
                    format_hint=_format_hint(download_urls, "jpg"),
                ),
                "live_video": live_video,
            }
        )
    return resources


def _build_live_video(item: Dict[str, Any]) -> Dict[str, Any]:
    video = item.get("video") if isinstance(item.get("video"), dict) else {}
    live_photo = item.get("live_photo") if isinstance(item.get("live_photo"), dict) else {}
    motion_photo = item.get("motion_photo") if isinstance(item.get("motion_photo"), dict) else {}
    live_video = live_photo.get("video") if isinstance(live_photo.get("video"), dict) else {}
    motion_video = (
        motion_photo.get("video") if isinstance(motion_photo.get("video"), dict) else {}
    )
    preferred = _highest_quality_play_addr(video)
    urls = _collect_urls(
        preferred,
        video.get("play_addr_h264"),
        video.get("play_addr"),
        video.get("download_addr"),
        live_video.get("play_addr"),
        live_video.get("download_addr"),
        motion_video.get("play_addr"),
        motion_video.get("download_addr"),
        video,
        item.get("video_play_addr"),
        item.get("video_download_addr"),
    )
    width, height = _dimensions(preferred, video, live_video, motion_video)
    return {
        "available": bool(urls),
        "download_urls": urls,
        "width": width,
        "height": height,
        "duration_ms": _duration_ms(
            video.get("duration")
            or live_video.get("duration")
            or motion_video.get("duration")
        ),
        "format_hint": "mp4" if urls else None,
    }


def _build_cover_resources(
    aweme_data: Dict[str, Any], image_resources: Optional[List[Dict[str, Any]]] = None
) -> List[Dict[str, Any]]:
    video = aweme_data.get("video") if isinstance(aweme_data.get("video"), dict) else {}
    gallery_items = _gallery_items(aweme_data)
    first_image = gallery_items[0] if gallery_items and isinstance(gallery_items[0], dict) else {}
    first_image_resource = image_resources[0] if image_resources else {}

    # A gallery/Live Photo cover represents its first image. Reuse the exact
    # normalized image candidates instead of rebuilding a smaller candidate
    # set here: some Douyin responses expose the usable no-watermark URL on the
    # image item itself while their dedicated cover/origin fields are already
    # watermarked. This keeps "save cover" identical to saving image 01.
    gallery_cover_urls = list(first_image_resource.get("download_urls") or [])
    if gallery_items and not gallery_cover_urls:
        gallery_cover_urls = _collect_urls(
            first_image.get("watermark_free_download_url_list"),
            first_image,
            first_image.get("origin_image"),
            first_image.get("display_image"),
            first_image.get("download_url"),
            first_image.get("download_addr"),
            first_image.get("download_url_list"),
            first_image.get("owner_watermark_image"),
        )

    # `video.cover` is commonly a display thumbnail and may be center-cropped.
    # Galleries use the complete first content image; standalone videos prefer
    # the original platform cover before display or animated variants.
    urls = _deduplicate(
        gallery_cover_urls
        + _collect_urls(video.get("origin_cover"))
        + _collect_urls(video.get("cover"))
        + _collect_urls(video.get("dynamic_cover"))
    )
    if not urls:
        return []
    fallback_width, fallback_height = _dimensions(
        first_image.get("origin_image"),
        first_image.get("display_image"),
        video.get("origin_cover"),
        video.get("cover"),
        video,
    )
    width = _int_value(first_image_resource.get("width")) or fallback_width
    height = _int_value(first_image_resource.get("height")) or fallback_height
    return [
        _resource(
            resource_id="cover_1",
            index=1,
            resource_type="cover",
            title="作品封面",
            preview_urls=urls,
            download_urls=urls,
            width=width,
            height=height,
            duration_ms=None,
            format_hint=_format_hint(urls, "jpg"),
        )
    ]


def _build_audio_resources(
    aweme_data: Dict[str, Any], cover_previews: List[str]
) -> List[Dict[str, Any]]:
    music = aweme_data.get("music") if isinstance(aweme_data.get("music"), dict) else {}
    urls = _collect_urls(music.get("play_url"))
    if not urls:
        return []
    return [
        _resource(
            resource_id="audio_1",
            index=1,
            resource_type="audio",
            title=str(music.get("title") or "作品原声"),
            preview_urls=cover_previews,
            download_urls=urls,
            width=None,
            height=None,
            duration_ms=_duration_ms(music.get("duration") or aweme_data.get("duration")),
            format_hint=_format_hint(urls, "m4a"),
        )
    ]


def _resource(
    *,
    resource_id: str,
    index: int,
    resource_type: str,
    title: str,
    preview_urls: List[str],
    download_urls: List[str],
    width: Optional[int],
    height: Optional[int],
    duration_ms: Optional[int],
    format_hint: Optional[str],
) -> Dict[str, Any]:
    return {
        "id": resource_id,
        "index": index,
        "type": resource_type,
        "title": title,
        "preview_urls": _deduplicate(preview_urls),
        "download_urls": _deduplicate(download_urls),
        "width": width,
        "height": height,
        "duration_ms": duration_ms,
        "format_hint": format_hint,
    }


def _gallery_items(aweme_data: Dict[str, Any]) -> List[Any]:
    image_post = aweme_data.get("image_post_info")
    if isinstance(image_post, dict):
        for key in ("images", "image_list"):
            candidate = image_post.get(key)
            if isinstance(candidate, list) and candidate:
                return candidate
    for key in ("images", "image_list"):
        candidate = aweme_data.get(key)
        if isinstance(candidate, list) and candidate:
            return candidate
    return []


def _has_gallery_hint(aweme_data: Dict[str, Any]) -> bool:
    if _gallery_items(aweme_data):
        return True
    return aweme_data.get("aweme_type") in GALLERY_AWEME_TYPES


def _highest_quality_play_addr(video: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    entries = video.get("bit_rate") if isinstance(video, dict) else None
    if not isinstance(entries, list):
        return None
    ranked: List[Tuple[int, int, Dict[str, Any]]] = []
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("play_addr"), dict):
            continue
        play_addr = entry["play_addr"]
        ranked.append(
            (
                _int_value(entry.get("bit_rate")) or 0,
                _int_value(play_addr.get("width") or entry.get("width")) or 0,
                play_addr,
            )
        )
    return max(ranked, key=lambda item: (item[0], item[1]))[2] if ranked else None


def _collect_urls(*sources: Any) -> List[str]:
    urls: List[str] = []
    for source in sources:
        urls.extend(_extract_urls(source))
    return _deduplicate(sorted(urls, key=_url_priority))


def _extract_urls(source: Any) -> List[str]:
    if isinstance(source, str):
        return [source] if source.startswith(("http://", "https://")) else []
    if isinstance(source, list):
        return [item for item in source if isinstance(item, str) and item.startswith(("http://", "https://"))]
    if isinstance(source, dict):
        value = source.get("url_list") or source.get("urlList")
        return _extract_urls(value)
    return []


def _deduplicate(values: Iterable[str]) -> List[str]:
    result: List[str] = []
    seen = set()
    for value in values:
        if not value or value in seen:
            continue
        seen.add(value)
        result.append(value)
    return result


def _url_priority(url: str) -> int:
    normalized = url.lower()
    watermark = any(
        marker in normalized
        for marker in ("tplv-dy-water", "owner_watermark", "watermark=1", "playwm")
    )
    return (100 if watermark else 0) + (1 if ".webp" in normalized else 0)


def _dimensions(*sources: Any) -> Tuple[Optional[int], Optional[int]]:
    for source in sources:
        if not isinstance(source, dict):
            continue
        width = _int_value(source.get("width"))
        height = _int_value(source.get("height"))
        if width or height:
            return width, height
    return None, None


def _duration_ms(value: Any) -> Optional[int]:
    number = _int_value(value)
    if not number or number <= 0:
        return None
    return number * 1000 if number < 1000 else number


def _int_value(value: Any) -> Optional[int]:
    try:
        return int(value) if value not in (None, "") else None
    except (TypeError, ValueError):
        return None


def _format_hint(urls: List[str], fallback: str) -> str:
    allowed = {"mp4", "mov", "m4a", "mp3", "jpg", "jpeg", "png", "webp", "gif"}
    for url in urls:
        suffix = urlparse(url).path.rsplit(".", 1)[-1].lower()
        if suffix in allowed:
            return "jpg" if suffix == "jpeg" else suffix
    return fallback
