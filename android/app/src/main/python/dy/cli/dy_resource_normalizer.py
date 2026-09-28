"""Normalize unstable Douyin detail responses into the Android v2 protocol."""

from __future__ import annotations

from typing import Any, Dict, Iterable, List, Optional, Tuple
from urllib.parse import urlencode, urlparse


GALLERY_AWEME_TYPES = {2, 68, 150}
_NO_WATERMARK_PLAY_ENDPOINT = "https://aweme.snssdk.com/aweme/v1/play/"


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
    raw_urls = _collect_urls(
        preferred,
        video.get("play_addr_h264"),
        video.get("play_addr"),
        video.get("download_addr"),
    )
    # Anonymous share pages commonly expose only `/aweme/v1/playwm/`. Build
    # the public play endpoint with `watermark=0` from the returned video URI
    # and keep the watermarked URL only as the final fallback. This URL is
    # stored in the preview snapshot as well, so the later save operation does
    # not lose the no-watermark candidate.
    clean_urls = [url for url in raw_urls if not _is_watermarked_media_url(url)]
    generated_url = build_no_watermark_url(aweme_data)
    urls = _deduplicate(clean_urls + ([generated_url] if generated_url else []) + raw_urls)
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
    # Live Photo payloads have changed shape several times.  Keep the
    # per-image association, but accept the video under the image itself, a
    # live/motion container, or one of the flattened compatibility fields.
    sources = _live_video_sources(item)
    preferred_sources = [_highest_quality_play_addr(source) for source in sources]
    raw_urls: List[str] = []
    generated_urls: List[str] = []
    for source, preferred in zip(sources, preferred_sources):
        raw_urls.extend(
            _collect_urls(
                preferred,
                source.get("play_addr_h264"),
                source.get("play_addr_265"),
                source.get("play_addr"),
                source.get("download_addr"),
                source,
            )
        )
        generated = build_no_watermark_video_url(source)
        if generated:
            generated_urls.append(generated)

    for key in ("video_play_addr", "video_download_addr", "live_video_url", "live_video_addr"):
        raw_urls.extend(_collect_urls(item.get(key)))

    # Prefer a clean CDN address or the generated watermark=0 endpoint. Keep
    # the original playwm URL only as the final fallback for compatibility.
    clean_urls = [url for url in raw_urls if not _is_watermarked_media_url(url)]
    urls = _deduplicate(clean_urls + generated_urls + raw_urls)
    width, height = _dimensions(
        *(source for source in sources),
        *(preferred for preferred in preferred_sources if preferred),
    )
    duration = next(
        (
            _duration_ms(source.get("duration") or source.get("duration_ms"))
            for source in sources
            if source.get("duration") or source.get("duration_ms")
        ),
        None,
    )
    return {
        "available": bool(urls),
        "download_urls": urls,
        "width": width,
        "height": height,
        "duration_ms": duration,
        "format_hint": "mp4" if urls else None,
    }


def _live_video_sources(item: Dict[str, Any]) -> List[Dict[str, Any]]:
    sources: List[Dict[str, Any]] = []
    seen: set[int] = set()

    def add(value: Any) -> None:
        if not isinstance(value, dict) or id(value) in seen:
            return
        if any(
            key in value
            for key in (
                "play_addr",
                "play_addr_h264",
                "play_addr_265",
                "download_addr",
                "bit_rate",
                "vid",
                "uri",
            )
        ):
            seen.add(id(value))
            sources.append(value)

    for key in ("video", "live_video", "live_video_info", "video_info"):
        add(item.get(key))
    for container_key in ("live_photo", "motion_photo"):
        container = item.get(container_key)
        if not isinstance(container, dict):
            continue
        add(container.get("video"))
        add(container.get("video_info"))
        add(container.get("live_video"))
        add(container)

    # Some responses flatten only the address on the image item.
    flattened: Dict[str, Any] = {}
    for key in ("video_play_addr", "video_download_addr", "live_video_url", "live_video_addr"):
        if key in item:
            flattened[key] = item[key]
    if flattened:
        add(flattened)
    return sources


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


def build_no_watermark_video_url(video: Dict[str, Any]) -> Optional[str]:
    """Return a clean URL for any Douyin video-shaped payload."""
    if not isinstance(video, dict):
        return None
    preferred = _highest_quality_play_addr(video)
    sources = (
        preferred,
        video.get("play_addr_h264"),
        video.get("play_addr_265"),
        video.get("play_addr"),
        video.get("download_addr"),
    )
    for source in sources:
        for url in _extract_urls(source):
            if url.startswith("https://") and not _is_watermarked_media_url(url):
                return url

    uri = _first_value(*sources) or video.get("vid")
    if not isinstance(uri, str) or not uri.strip():
        return None
    params = {
        "video_id": uri.strip(),
        "ratio": "1080p",
        "line": "0",
        "is_play_url": "1",
        "watermark": "0",
        "source": "PackSourceEnum_PUBLISH",
    }
    return _NO_WATERMARK_PLAY_ENDPOINT + "?" + urlencode(params)


def build_no_watermark_url(aweme_data: Dict[str, Any]) -> Optional[str]:
    """Return a public no-watermark candidate for the main work video."""
    video = aweme_data.get("video") if isinstance(aweme_data.get("video"), dict) else {}
    return build_no_watermark_video_url(video)


def _first_value(*sources: Any) -> Optional[str]:
    for source in sources:
        if not isinstance(source, dict):
            continue
        for key in ("uri", "vid"):
            value = source.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
    return None


def _is_watermarked_media_url(url: str) -> bool:
    normalized = str(url or "").lower()
    return any(
        marker in normalized
        for marker in (
            "tplv-dy-water",
            "dy-water",
            "owner_watermark",
            "watermark_image",
            "watermark=1",
            "playwm",
        )
    )


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
