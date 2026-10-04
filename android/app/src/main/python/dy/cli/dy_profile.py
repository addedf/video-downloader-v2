"""Bounded author-post pagination using the existing Douyin signed client.

Endpoint and parameters are taken from the locally vendored DYDownloader
DouyinDownloader.buildAccountListParams/fetchAccountAwemeList implementation.
Only this Android CLI adapter extends the vendor API; no core files are patched.
"""
from __future__ import annotations

import asyncio
import re
import time
from typing import Any, Dict, Optional
from urllib.parse import parse_qs, urlparse

from . import dy_anonymous_share as anonymous_share
from .dy_resource_normalizer import normalize_aweme

MAX_PAGES = 5
PAGE_SIZE = 18
SCAN_SECONDS = 30
PAGE_TIMEOUT_SECONDS = 15
_USER_ID = re.compile(r"[A-Za-z0-9_-]{3,200}")
_PROFILE_PATH = re.compile(r"^/(?:share/)?user/([A-Za-z0-9_-]+)/?$")
PROFILE_MEDIA_HOSTS = (
    "douyin.com", "iesdouyin.com", "douyinvod.com", "byteimg.com",
    "douyinpic.com", "pstatp.com", "snssdk.com", "bytedance.com", "bytecdn.cn",
)


def profile_user_id(value: str) -> Optional[str]:
    """Return a validated public profile ID; modal links still mean one work."""
    url = re.split(r"[，。；\s]", anonymous_share._normalize_input_url(value), maxsplit=1)[0]
    parsed = urlparse(url)
    host = (parsed.hostname or "").lower()
    if not anonymous_share._is_douyin_host(host):
        return None
    params = parse_qs(parsed.query)
    if params.get("modal_id") or anonymous_share._extract_route(url):
        return None
    match = _PROFILE_PATH.fullmatch(parsed.path)
    if match is None:
        return None
    anonymous_share._validate_douyin_url(url)
    if parsed.username or parsed.password or parsed.port not in (None, 443):
        raise ValueError("抖音主页地址无效")
    user_id = next((params[key][0] for key in ("sec_uid", "sec_user_id", "secUid")
                    if params.get(key)), match.group(1))
    if not _USER_ID.fullmatch(user_id) or user_id == "self":
        raise ValueError("请粘贴博主的公开抖音主页链接")
    return user_id


def canonical_profile_url(user_id: str) -> str:
    if not _USER_ID.fullmatch(user_id) or user_id == "self":
        raise ValueError("抖音主页 ID 无效")
    return f"https://www.douyin.com/user/{user_id}"


def parse_cursor(cursor: Optional[str]) -> str:
    value = str(cursor if cursor is not None else "0")
    if not re.fullmatch(r"\d{1,20}", value):
        raise ValueError("抖音主页分页游标无效，请重新解析")
    return value


async def _get_profile_page(client, user_id: str, cursor: str) -> Dict[str, Any]:
    params = await client._default_query()
    params.update({
        "sec_user_id": user_id, "max_cursor": cursor, "count": str(PAGE_SIZE),
        "locate_query": "false", "show_live_replay_strategy": "1", "need_time_list": "1",
        "time_list_query": "0", "whale_cut_token": "", "cut_version": "1",
        "publish_video_strategy_type": "2", "from_user_page": "1",
    })
    data = await client._request_json("/aweme/v1/web/aweme/post/", params,
                                      suppress_error=True, max_retries=1)
    if (not isinstance(data, dict) or data.get("status_code", 0) not in (0, "0")
            or not isinstance(data.get("aweme_list"), list)
            or data.get("has_more") not in (0, 1, "0", "1", False, True)):
        raise ValueError("抖音未返回可访问的主页作品列表，可能需要登录或触发风控；请在抖音登录后重试")
    return data


def _new_client(cookies, proxy):
    from core import DouyinAPIClient
    return DouyinAPIClient(cookies, proxy=proxy)


async def resolve_profile(input_text: str, user_id: str, *, cookies=None, proxy=None,
                          cursor=None, client=None) -> Dict[str, Any]:
    if client is None:
        async with _new_client(cookies or {}, proxy) as owned:
            return await resolve_profile(input_text, user_id, cookies=cookies, proxy=proxy,
                                         cursor=cursor, client=owned)
    current = parse_cursor(cursor)
    groups = {"videos": [], "images": [], "covers": [], "audios": []}
    seen_posts, seen_cursors = set(), {current}
    pages, idle_pages = 0, 0
    complete, warning = False, ""
    author = {"id": user_id, "name": "抖音博主"}
    started = time.monotonic()
    while pages < MAX_PAGES and time.monotonic() - started < SCAN_SECONDS:
        try:
            page = await asyncio.wait_for(_get_profile_page(client, user_id, current),
                                          timeout=min(PAGE_TIMEOUT_SECONDS,
                                                      max(0.1, SCAN_SECONDS - (time.monotonic() - started))))
        except (ValueError, asyncio.TimeoutError, OSError, anonymous_share.aiohttp.ClientError) as exc:
            if not pages:
                if isinstance(exc, ValueError):
                    raise
                raise ValueError("抖音主页请求超时或网络不可用，请稍后重试") from exc
            warning = "后续作品暂不可访问，已保留当前媒体；请稍后或在抖音登录后继续提取"
            break
        pages += 1
        added_posts, missing_media = 0, 0
        for item in page["aweme_list"]:
            if not isinstance(item, dict):
                missing_media += 1
                continue
            post_id = str(item.get("aweme_id") or "")
            if not re.fullmatch(r"\d{6,22}", post_id):
                missing_media += 1
                continue
            if post_id in seen_posts:
                continue
            item_author = item.get("author") if isinstance(item.get("author"), dict) else {}
            if item_author.get("sec_uid") and item_author["sec_uid"] != user_id:
                continue
            work = normalize_aweme(item)
            if not work["resources"]["videos"] and not work["resources"]["images"]:
                missing_media += 1
                continue
            seen_posts.add(post_id)
            added_posts += 1
            if item_author.get("nickname"):
                author["name"] = str(item_author["nickname"])
            for kind in ("videos", "images"):
                for resource in work["resources"][kind]:
                    resource = dict(resource)
                    resource["id"] = f"{post_id}-{resource['id']}"
                    resource["index"] = len(groups[kind]) + 1
                    resource["title"] = f"{work['title'][:50]} · {resource['title']}"
                    groups[kind].append(resource)
        if missing_media:
            warning = "部分作品未返回可下载媒体，已保留当前结果；可稍后继续重试该页"
            break  # Retry this page rather than silently skipping unavailable assets.
        has_more = page["has_more"] in (1, "1", True)
        if not has_more:
            complete, current = True, None
            break
        next_cursor = str(page.get("max_cursor") or "")
        if not re.fullmatch(r"\d{1,20}", next_cursor) or next_cursor in seen_cursors:
            warning = "抖音未返回有效的新分页游标，已停止；当前结果可能不完整，请稍后重新解析"
            current = None
            break
        current = next_cursor
        seen_cursors.add(current)
        idle_pages = idle_pages + 1 if not added_posts else 0
        if idle_pages >= 2:
            warning = "连续多页没有新增媒体，已暂停；不能确认取全，可稍后继续提取"
            break
        if pages < MAX_PAGES:
            await asyncio.sleep(0.25)
    if not seen_posts and complete and cursor is None:
        raise ValueError("未找到该博主当前可访问的公开媒体；私密、删除及登录不可见作品无法提取")
    message = warning or ("已提取接口当前可访问的全部发布媒体；不包含私密、删除或不可见作品" if complete
                          else "已自动提取一批发布媒体，可继续提取更早内容；尚未取全")
    live_count = sum(bool(item.get("live_video", {}).get("available")) for item in groups["images"])
    return {
        "schema_version": 2, "ok": True, "message": message, "error": None,
        "source": {"platform": "douyin", "input_url": input_text,
                   "resolved_url": canonical_profile_url(user_id), "id": f"profile-{user_id}"},
        "work": {"type": "mixed" if groups["videos"] and groups["images"] else
                         "video" if groups["videos"] else "live_photo" if live_count else "gallery",
                 "title": f"{author['name']} 的主页媒体", "author": author, "resources": groups,
                 "counts": {**{key: len(items) for key, items in groups.items()}, "live_videos": live_count},
                 "capabilities": {"has_video": bool(groups["videos"]), "has_images": bool(groups["images"]),
                                  "has_cover": False, "has_audio": False, "has_live_video": bool(live_count)}},
        "collection": {"next_cursor": current, "complete": complete, "pages": pages, "posts": len(seen_posts)},
        "diagnostics": {"channel": "douyin_signed_profile", "response_summary": message,
                        "cookie_available": bool(cookies)},
    }
