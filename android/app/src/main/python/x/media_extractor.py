# -*- coding: utf-8 -*-
"""从 TweetResultByRestId 的 result 里提取媒体资源。

视频：extended_entities.media[].video_info.variants 过滤 mp4，按 bitrate
降序取最高（v1 无画质选择）；GIF（animated_gif）同 video_info 走 mp4。
图片：media_url_https + ``?name=orig`` 取原图，单推最多 4 张。
"""
from typing import Any

VIDEO_TYPES = ("video", "animated_gif")
IMAGE_NAME_SUFFIX = "?name=orig"


def _walk_media(node: Any):
    """深度遍历响应树，产出所有含 legacy 的推文 dict。"""
    if isinstance(node, dict):
        legacy = node.get("legacy")
        if isinstance(legacy, dict) and "full_text" in legacy:
            yield node
        for value in node.values():
            yield from _walk_media(value)
    elif isinstance(node, list):
        for item in node:
            yield from _walk_media(item)


def tweet_media(tweet_result: dict) -> list[dict]:
    """单推 result → media 实体列表（extended_entities 优先）。"""
    if not tweet_result:
        return []
    legacy = tweet_result.get("legacy") or {}
    extended = legacy.get("extended_entities") or {}
    return extended.get("media") or []


def video_variants(media: dict) -> list[dict]:
    """media 实体 → mp4 变体列表（bitrate 降序）。"""
    info = media.get("video_info") or {}
    variants = [
        v for v in info.get("variants") or []
        if v.get("content_type") == "video/mp4"
    ]
    variants.sort(key=lambda v: v.get("bitrate") or 0, reverse=True)
    return variants


def best_video_url(media: dict) -> str | None:
    variants = video_variants(media)
    return variants[0]["url"] if variants else None


def extract_resources(tweet_result: dict) -> dict:
    """推文 result → v2 resolve 的 resources 分组。

    返回 {'videos': [...], 'images': [...], 'covers': [], 'audios': []}
    以及统计 counts 与 capabilities 所需的原始计数。
    """
    videos: list[dict] = []
    images: list[dict] = []
    tweet_id = str(tweet_result.get("rest_id") or "")
    author = _author_name(tweet_result)
    text = _plain_text(tweet_result)

    for index, media in enumerate(tweet_media(tweet_result)):
        media_type = media.get("type")
        preview = media.get("media_url_https") or ""
        if media_type in VIDEO_TYPES:
            variants = video_variants(media)
            if not variants:
                continue
            top = variants[0]
            videos.append({
                "id": f"video-{index}",
                "index": index,
                "type": "video",
                "title": text or f"X 视频 {tweet_id}",
                "preview_urls": [f"{preview}?name=small"] if preview else [],
                "download_urls": [top["url"]],
                "width": (media.get("video_info") or {}).get("width"),
                "height": None,
                "duration_ms": int(
                    (media.get("video_info") or {}).get("duration_millis") or 0
                ) or None,
                "format_hint": "mp4",
            })
        elif media_type == "photo":
            images.append({
                "id": f"image-{index}",
                "index": index,
                "type": "image",
                "title": text or f"X 图片 {tweet_id}",
                "preview_urls": [f"{preview}?name=small"] if preview else [],
                "download_urls": [f"{preview}{IMAGE_NAME_SUFFIX}"] if preview else [],
                "width": (media.get("sizes") or {}).get("orig", {}).get("w"),
                "height": (media.get("sizes") or {}).get("orig", {}).get("h"),
                "duration_ms": None,
                "format_hint": "jpg",
            })

    counts = {
        "videos": len(videos),
        "images": len(images),
        "covers": 0,
        "audios": 0,
        "live_videos": 0,
    }
    capabilities = {
        "has_video": bool(videos),
        "has_images": bool(images),
        "has_cover": False,
        "has_audio": False,
        "has_live_video": False,
    }
    work_type = _work_type(videos, images)
    return {
        "tweet_id": tweet_id,
        "author": author,
        "text": text,
        "work_type": work_type,
        "capabilities": capabilities,
        "counts": counts,
        "resources": {
            "videos": videos,
            "images": images,
            "covers": [],
            "audios": [],
        },
    }


def _author_name(tweet_result: dict) -> str:
    core = tweet_result.get("core") or {}
    user = (core.get("user_results") or {}).get("result") or {}
    legacy = user.get("legacy") or {}
    return legacy.get("screen_name") or legacy.get("name") or ""


def _plain_text(tweet_result: dict) -> str:
    legacy = tweet_result.get("legacy") or {}
    text = legacy.get("full_text") or ""
    return text.strip()


def _work_type(videos: list, images: list) -> str:
    if videos and images:
        return "mixed"
    if videos:
        return "video"
    if images:
        return "image"
    return "unknown"
