"""Anonymous Douyin single-work resolver used by the Android V2 bridge.

The implementation intentionally stays inside ``dy/cli`` so the vendored core
remains untouched. It resolves public share pages and reads the page's router
payload; no login Cookie, msToken, X-Bogus or a_bogus is required.
"""

from __future__ import annotations

import ast
import asyncio
import copy
import html as html_module
import ipaddress
import json
import re
from dataclasses import dataclass
from typing import Any, Dict, Iterable, List, Optional, Tuple
from urllib.parse import unquote, urlencode, urljoin, urlparse

import aiohttp

from common.android_utils import extract_first_url
from .dy_resource_normalizer import _build_live_video, _gallery_items, normalize_aweme

_USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
)
_MAX_REDIRECTS = 5
_MAX_HTML_BYTES = 4 * 1024 * 1024
_ROUTE_TYPES = ("video", "note", "slides")
_ROUTE_RE = re.compile(r"/(?:share/)?(video|note|slides)/(\d{6,})", re.IGNORECASE)
_ID_RE = re.compile(r"(?<!\d)(\d{15,22})(?!\d)")
_ROUTER_MARKER_RE = re.compile(r"window\._ROUTER_DATA\s*=\s*")
_EMBEDDED_ASSIGNMENT_MARKERS = (
    "window.__UNIVERSAL_DATA_FOR_REHYDRATION__",
    "window.__INIT_PROPS__",
    "window.__INITIAL_STATE__",
)
_EMBEDDED_SCRIPT_RE = re.compile(
    r"<script[^>]+id=[\"'](?P<name>RENDER_DATA|SIGI_STATE)[\"'][^>]*>(?P<body>.*?)</script>",
    re.IGNORECASE | re.DOTALL,
)
_RESULT_KEYS = ("videoInfoRes", "noteDetailRes", "slidesInfoRes")
_ITEM_LIST_KEYS = ("item_list", "aweme_details", "aweme_list")
_STORY_UNAVAILABLE_ERROR = "日常作品已过期、删除或当前不可见，抖音分享页未提供媒体数据"
_WAF_MARKERS = ("lf-waf-js.byted-static.com", "out-sha256.js")
_TEMPORARY_WAF_ERROR = "抖音公开分享页触发临时风控，请稍后重试"
_PREFERRED_SHARE_ATTEMPTS = 2
_OPEN_DETAIL_TIMEOUT_SECONDS = 6
_OPEN_DETAIL_VISIBILITY_ERROR = "抖音当前将该作品标记为审核中或仅自己可见，公开接口暂未返回媒体"
_LIVE_IMAGE_FIELDS = (
    "video", "live_video", "live_video_info", "video_info", "live_photo",
    "motion_photo", "video_play_addr", "video_download_addr", "live_video_url",
    "live_video_addr", "clip_type", "live_photo_type",
)


@dataclass(frozen=True)
class AnonymousDouyinResult:
    input_url: str
    resolved_url: str
    source_id: str
    share_type: str
    aweme_data: Dict[str, Any]


class AnonymousDouyinError(ValueError):
    pass


class AnonymousDouyinVisibilityError(AnonymousDouyinError):
    pass


class AnonymousDouyinProfile(AnonymousDouyinError):
    """Share redirect resolved to an author, not an individual work."""

    def __init__(self, resolved_url: str, user_id: str):
        super().__init__("此分享链接指向抖音博主主页")
        self.resolved_url = resolved_url
        self.user_id = user_id


async def resolve_public_douyin(
    input_text: str,
    *,
    proxy: Optional[str] = None,
    session: Optional[aiohttp.ClientSession] = None,
) -> AnonymousDouyinResult:
    input_url = _normalize_input_url(input_text)
    _validate_douyin_url(input_url)

    owns_session = session is None
    if session is None:
        timeout = aiohttp.ClientTimeout(total=20, connect=6, sock_read=12)
        session = aiohttp.ClientSession(
            timeout=timeout,
            headers={"User-Agent": _USER_AGENT, "Accept-Language": "zh-CN,zh;q=0.9"},
        )

    try:
        resolved_url = input_url
        route = _extract_route(resolved_url)
        if route is None or _is_short_host(urlparse(resolved_url).hostname or ""):
            resolved_url = await _expand_share_url(session, input_url, proxy=proxy)
            _validate_douyin_url(resolved_url)
            route = _extract_route(resolved_url)

        # Reuse the validated share redirect, avoiding a second network request
        # just to distinguish short links to profiles from links to one work.
        from .dy_profile import profile_user_id
        user_id = profile_user_id(resolved_url)
        if user_id:
            raise AnonymousDouyinProfile(resolved_url, user_id)

        source_id = route[1] if route else _extract_id(resolved_url)
        if not source_id:
            raise AnonymousDouyinError("无法从抖音链接中提取作品 ID")

        preferred_type = route[0] if route else None
        errors: List[str] = []
        for share_type in _ordered_types(preferred_type):
            share_url = f"https://www.iesdouyin.com/share/{share_type}/{source_id}/"
            if share_type == "slides":
                try:
                    item = await _fetch_slides_item(
                        session,
                        source_id,
                        proxy=proxy,
                        referer=share_url,
                    )
                    aweme_data = canonicalize_aweme(item, fallback_id=source_id)
                    aweme_data = await _enrich_live_photos(
                        session, aweme_data, source_id=source_id, proxy=proxy
                    )
                    return AnonymousDouyinResult(
                        input_url=input_url,
                        resolved_url=share_url,
                        source_id=str(aweme_data.get("aweme_id") or source_id),
                        share_type=share_type,
                        aweme_data=aweme_data,
                    )
                except AnonymousDouyinError as exc:
                    errors.append(f"slidesinfo: {exc}")

            attempts = _PREFERRED_SHARE_ATTEMPTS if share_type == preferred_type else 1
            for attempt in range(attempts):
                try:
                    html, final_url = await _request_text(
                        session,
                        share_url,
                        proxy=proxy,
                        method="GET",
                        require_html=True,
                    )
                    router_data = extract_router_data(html)
                    item = extract_aweme_item(router_data)
                    aweme_data = canonicalize_aweme(item, fallback_id=source_id)
                    aweme_data = await _enrich_live_photos(
                        session, aweme_data, source_id=source_id, proxy=proxy
                    )
                    return AnonymousDouyinResult(
                        input_url=input_url,
                        resolved_url=final_url or resolved_url,
                        source_id=str(aweme_data.get("aweme_id") or source_id),
                        share_type=share_type,
                        aweme_data=aweme_data,
                    )
                except AnonymousDouyinError as exc:
                    errors.append(f"{share_type}: {exc}")
                    if str(exc) != _TEMPORARY_WAF_ERROR or attempt + 1 >= attempts:
                        break
                    await asyncio.sleep(0.25)

        # Share reflow responses sometimes contain no work at all while the
        # public detail endpoint still has it. Try that endpoint once, with
        # the same bounded timeout and strict work/media validation used for
        # Live Photo enrichment. It never reuses an earlier cached response.
        detail_aweme = await _fetch_open_detail(session, source_id=source_id, proxy=proxy)
        if detail_aweme is not None:
            detail_type = "slides" if _gallery_items(detail_aweme) else "video"
            return AnonymousDouyinResult(
                input_url=input_url,
                resolved_url=f"https://www.douyin.com/{detail_type}/{source_id}",
                source_id=source_id,
                share_type=detail_type,
                aweme_data=detail_aweme,
            )

        if any(_TEMPORARY_WAF_ERROR in error for error in errors):
            raise AnonymousDouyinError(_TEMPORARY_WAF_ERROR)
        if any(_STORY_UNAVAILABLE_ERROR in error for error in errors):
            raise AnonymousDouyinError(_STORY_UNAVAILABLE_ERROR)
        detail = "；".join(errors[-3:])
        raise AnonymousDouyinError(f"公开分享页未返回可解析作品数据{f'（{detail}）' if detail else ''}")
    finally:
        if owns_session:
            await session.close()


def extract_router_data(html: str) -> Dict[str, Any]:
    """Extract one of the public share page's embedded state containers.

    Most pages expose ``_ROUTER_DATA``.  Some daily/video landing pages use
    ``RENDER_DATA`` or hydration state instead, so the resolver treats all of
    them as equivalent input and lets ``extract_aweme_item`` find the work.
    A malformed or unrelated candidate must not prevent a later candidate
    from being tried: Douyin occasionally leaves more than one state blob in
    the same HTML document.
    """
    text = str(html or "")
    if any(marker in text for marker in _WAF_MARKERS):
        raise AnonymousDouyinError(_TEMPORARY_WAF_ERROR)

    candidates: List[Tuple[str, bool]] = []
    router_match = _ROUTER_MARKER_RE.search(text)
    if router_match:
        candidates.append((text[router_match.end():], False))

    candidates.extend(
        (match.group("body"), True) for match in _EMBEDDED_SCRIPT_RE.finditer(text)
    )

    for marker in _EMBEDDED_ASSIGNMENT_MARKERS:
        match = re.search(re.escape(marker) + r"\s*=\s*", text)
        if match:
            candidates.append((text[match.end():], False))

    parsed_values: List[Dict[str, Any]] = []
    saw_json_error = False
    for fragment, url_decode in candidates:
        try:
            value = _decode_embedded_json(fragment, url_decode=url_decode)
        except AnonymousDouyinError:
            saw_json_error = True
            continue
        if isinstance(value, dict):
            parsed_values.append(value)
            # A page can expose a generic router state before the actual
            # detail state. Prefer the first payload that already contains a
            # recognizable work, rather than returning an empty container and
            # making the later RENDER_DATA/SIGI_STATE unreachable.
            try:
                extract_aweme_item(value)
            except AnonymousDouyinError:
                continue
            return value
        if isinstance(value, list):
            # Keep the public function's dictionary contract while allowing
            # pages that serialize one or more state objects as an array.
            wrapped = {"__embedded_state__": value}
            try:
                extract_aweme_item(wrapped)
            except AnonymousDouyinError:
                continue
            return wrapped

    if parsed_values:
        return parsed_values[0]

    if saw_json_error:
        raise AnonymousDouyinError("分享页嵌入 JSON 不是有效 JSON")
    raise AnonymousDouyinError("分享页缺少可用作品 JSON")


def _decode_embedded_json(fragment: str, *, url_decode: bool = False) -> Any:
    text = str(fragment or "").lstrip()
    if url_decode:
        # RENDER_DATA is commonly percent-encoded once, and a few pages put
        # the encoded value inside a quoted JSON string. Two bounded passes
        # cover both forms without turning arbitrary page text into a loop.
        for _ in range(2):
            decoded = unquote(text)
            if decoded == text:
                break
            text = decoded
    text = html_module.unescape(text).lstrip()
    if text.startswith(("\"", "'")):
        quote = text[0]
        end = _find_quoted_end(text, quote)
        if end < 0:
            raise AnonymousDouyinError("分享页嵌入 JSON 字符串未闭合")
        quoted = text[: end + 1]
        try:
            decoded = json.loads(quoted) if quote == "\"" else ast.literal_eval(quoted)
        except (ValueError, SyntaxError, json.JSONDecodeError) as exc:
            raise AnonymousDouyinError("分享页嵌入 JSON 字符串格式错误") from exc
        if not isinstance(decoded, str):
            return decoded
        text = html_module.unescape(decoded).lstrip()
        for _ in range(2):
            unquoted = unquote(text)
            if unquoted == text:
                break
            text = unquoted
    json_start = next(
        (index for index, char in enumerate(text) if char in "[{"),
        -1,
    )
    if json_start < 0:
        raise AnonymousDouyinError("分享页嵌入 JSON 格式错误")
    try:
        value, _ = json.JSONDecoder().raw_decode(text[json_start:])
    except json.JSONDecodeError as exc:
        raise AnonymousDouyinError("分享页嵌入 JSON 不是有效 JSON") from exc
    return value


def _find_quoted_end(text: str, quote: str) -> int:
    escaped = False
    for index in range(1, len(text)):
        char = text[index]
        if char == quote and not escaped:
            return index
        if char == "\\" and not escaped:
            escaped = True
        else:
            escaped = False
    return -1


def extract_aweme_item(router_data: Dict[str, Any]) -> Dict[str, Any]:
    item = _extract_item_from_result(router_data)
    if item:
        return item
    for node in _walk_dicts(router_data):
        for result_key in _RESULT_KEYS:
            result = node.get(result_key)
            item = _extract_item_from_result(result)
            if item:
                return item

    # RENDER_DATA/SIGI_STATE and daily landing pages may put the aweme object
    # directly under app.videoDetail, item, data, or another dynamic key.
    for node in _walk_dicts(router_data):
        if _looks_like_aweme_item(node):
            return node
    if _contains_unavailable_story(router_data):
        raise AnonymousDouyinError(_STORY_UNAVAILABLE_ERROR)
    raise AnonymousDouyinError("分享页未找到作品详情")


def _looks_like_aweme_item(value: Dict[str, Any]) -> bool:
    if not isinstance(value, dict):
        return False
    aweme_id = value.get("aweme_id") or value.get("awemeId")
    if not isinstance(aweme_id, (str, int)) or not str(aweme_id).isdigit():
        return False
    media_keys = {
        "video",
        "videoInfo",
        "video_info",
        "images",
        "image_list",
        "imageList",
        "image_post_info",
        "imagePostInfo",
    }
    return bool(media_keys.intersection(value))


def _contains_unavailable_story(value: Any) -> bool:
    """Recognize the empty response used for expired/hidden daily stories."""
    for node in _walk_dicts(value):
        for key in ("filter_list", "filterList"):
            filters = node.get(key)
            if not isinstance(filters, list):
                continue
            for entry in filters:
                if not isinstance(entry, dict):
                    continue
                reason = str(entry.get("filter_reason") or entry.get("filterReason") or "")
                if reason == "story_25_filter":
                    return True
    return False


def canonicalize_aweme(item: Dict[str, Any], *, fallback_id: str = "") -> Dict[str, Any]:
    """Convert only media-related camelCase aliases to the existing v2 shape."""
    data = copy.deepcopy(item)
    _alias(data, "aweme_id", "awemeId", default=fallback_id)
    _alias(data, "create_time", "createTime")
    _alias(data, "aweme_type", "awemeType")
    _alias(data, "image_post_info", "imagePostInfo")
    _alias(data, "video", "videoInfo")
    _alias(data, "video", "video_info")

    author = _dict_value(data, "author")
    if author:
        _alias(author, "sec_uid", "secUid")
        _alias(author, "unique_id", "uniqueId")

    video = _dict_value(data, "video")
    if video:
        data["video"] = _canonicalize_video(video)

    music = _dict_value(data, "music")
    if music:
        _alias(music, "play_url", "playUrl")
        if isinstance(music.get("play_url"), dict):
            music["play_url"] = _canonicalize_address(music["play_url"], media_kind="audio")

    image_post = _dict_value(data, "image_post_info")
    if image_post:
        _alias(image_post, "image_list", "imageList")
        for key in ("images", "image_list"):
            if isinstance(image_post.get(key), list):
                image_post[key] = [_canonicalize_image(value) for value in image_post[key]]

    for key in ("images", "image_list", "imageList"):
        if isinstance(data.get(key), list):
            canonical_key = "image_list" if key == "imageList" else key
            data[canonical_key] = [_canonicalize_image(value) for value in data[key]]
            if canonical_key != key:
                data.pop(key, None)

    return data


async def _fetch_slides_item(
    session: aiohttp.ClientSession,
    source_id: str,
    *,
    proxy: Optional[str],
    referer: str,
) -> Dict[str, Any]:
    query = urlencode({"aweme_ids": f"[{source_id}]", "request_source": "200"})
    api_url = f"https://www.iesdouyin.com/web/api/v2/aweme/slidesinfo/?{query}"
    payload_text, _ = await _request_text(
        session,
        api_url,
        proxy=proxy,
        method="GET",
        require_html=False,
        request_headers={
            "Accept": "application/json, text/plain, */*",
            "Referer": referer,
        },
    )
    try:
        payload = json.loads(payload_text)
    except json.JSONDecodeError as exc:
        raise AnonymousDouyinError("slidesinfo 返回了无效 JSON") from exc
    if not isinstance(payload, dict):
        raise AnonymousDouyinError("slidesinfo 返回格式错误")
    item = _extract_item_from_result(payload)
    if not item:
        raise AnonymousDouyinError("slidesinfo 未找到作品详情")
    return item


async def _enrich_live_photos(
    session: aiohttp.ClientSession,
    aweme_data: Dict[str, Any],
    *,
    source_id: str,
    proxy: Optional[str],
) -> Dict[str, Any]:
    """Recover motion omitted by the reflow API without replacing its stills.

    The public slides response can label Live Photos as static ImageClip=2
    and omit every per-image video. The public web detail response retains
    those videos. Treat it as an optional supplement, paired by image URI;
    an unavailable endpoint must not turn a usable gallery into a failure.
    """
    images = _gallery_items(aweme_data)
    if not any(
        _image_uri(item) and not _build_live_video(item)["available"]
        for item in images
    ):
        return aweme_data
    if str(aweme_data.get("aweme_id") or "") != str(source_id):
        return aweme_data
    try:
        detail = await _fetch_open_detail(session, source_id=source_id, proxy=proxy)
    except AnonymousDouyinVisibilityError:
        return aweme_data
    return _merge_live_photo_detail(aweme_data, detail) if detail is not None else aweme_data


async def _fetch_open_detail(
    session: aiohttp.ClientSession,
    *,
    source_id: str,
    proxy: Optional[str],
) -> Optional[Dict[str, Any]]:
    """Fetch one matching public work; unavailable optional requests return None."""
    query = urlencode({"aweme_id": source_id, "aid": "6383"})
    try:
        text, _ = await asyncio.wait_for(
            _request_text(
                session,
                f"https://www.douyin.com/aweme/v1/web/aweme/detail/?{query}",
                proxy=proxy,
                method="GET",
                require_html=False,
                request_headers={
                    "User-Agent": _USER_AGENT,
                    "Accept": "application/json, text/plain, */*",
                    "Origin": "https://open.douyin.com",
                    "Referer": "https://open.douyin.com/",
                },
            ),
            timeout=_OPEN_DETAIL_TIMEOUT_SECONDS,
        )
        payload = json.loads(text)
        if not isinstance(payload, dict) or payload.get("status_code") != 0:
            return None
        filtered = payload.get("filter_detail")
        if (
            isinstance(filtered, dict)
            and str(filtered.get("aweme_id") or "") == str(source_id)
            and filtered.get("filter_reason") == "status_audit_self_see"
        ):
            raise AnonymousDouyinVisibilityError(_OPEN_DETAIL_VISIBILITY_ERROR)
        detail = payload.get("aweme_detail")
        if not isinstance(detail, dict):
            return None
        detail = canonicalize_aweme(detail)
        if str(detail.get("aweme_id") or "") != str(source_id):
            return None
        work = normalize_aweme(detail)
        primary_resources = work["resources"]["images"] + work["resources"]["videos"]
        if not any(
            _is_visual_media_url(url)
            for resource in primary_resources
            for url in resource.get("download_urls", [])
        ):
            return None
        return detail
    except AnonymousDouyinVisibilityError:
        raise
    except (AnonymousDouyinError, asyncio.TimeoutError, ValueError):
        return None


def _is_visual_media_url(url: str) -> bool:
    parsed = urlparse(url)
    # A gallery's top-level video.play_addr may actually contain only its
    # background MP3. Never accept that as the fallback's primary media.
    return bool(
        parsed.scheme == "https"
        and parsed.hostname
        and not parsed.path.lower().endswith((".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg", ".opus"))
    )


def _merge_live_photo_detail(
    aweme_data: Dict[str, Any], detail: Dict[str, Any]
) -> Dict[str, Any]:
    source_id = str(aweme_data.get("aweme_id") or "")
    if not source_id or str(detail.get("aweme_id") or "") != source_id:
        return aweme_data

    # Never pair by position: a partial or reordered response must not attach
    # a different clip to the user's selected still image.
    by_uri: Dict[str, Dict[str, Any]] = {}
    duplicate_uris: set[str] = set()
    for item in _gallery_items(detail):
        uri = _image_uri(item)
        if not uri:
            continue
        if uri in by_uri:
            duplicate_uris.add(uri)
        else:
            by_uri[uri] = item

    enriched = copy.deepcopy(aweme_data)
    for image in _gallery_items(enriched):
        uri = _image_uri(image)
        if not uri or uri in duplicate_uris or _build_live_video(image)["available"]:
            continue
        candidate = by_uri.get(uri)
        if not candidate or not _build_live_video(candidate)["available"]:
            continue
        for field in _LIVE_IMAGE_FIELDS:
            if field in candidate:
                image[field] = copy.deepcopy(candidate[field])
    return enriched


def _image_uri(item: Any) -> str:
    if not isinstance(item, dict):
        return ""
    for address in (item, item.get("origin_image"), item.get("display_image")):
        if isinstance(address, dict) and isinstance(address.get("uri"), str):
            uri = address["uri"].strip()
            if uri:
                return uri
    return ""


async def _expand_share_url(
    session: aiohttp.ClientSession,
    url: str,
    *,
    proxy: Optional[str],
) -> str:
    try:
        _, final_url = await _request_text(
            session,
            url,
            proxy=proxy,
            method="HEAD",
            require_html=False,
            read_body=False,
        )
        if final_url and final_url != url:
            return final_url
    except AnonymousDouyinError:
        pass

    _, final_url = await _request_text(
        session,
        url,
        proxy=proxy,
        method="GET",
        require_html=False,
        read_body=False,
    )
    return final_url or url


async def _request_text(
    session: aiohttp.ClientSession,
    url: str,
    *,
    proxy: Optional[str],
    method: str,
    require_html: bool,
    read_body: bool = True,
    request_headers: Optional[Dict[str, str]] = None,
) -> Tuple[str, str]:
    current = url
    for _ in range(_MAX_REDIRECTS + 1):
        _validate_douyin_url(current)
        try:
            headers = {"Referer": "https://www.douyin.com/", "Accept": "text/html,*/*"}
            if request_headers:
                headers.update(request_headers)
            async with session.request(
                method,
                current,
                allow_redirects=False,
                proxy=proxy,
                headers=headers,
            ) as response:
                if response.status in {301, 302, 303, 307, 308}:
                    location = response.headers.get("Location")
                    if not location:
                        raise AnonymousDouyinError("抖音短链跳转缺少目标地址")
                    current = urljoin(current, location)
                    method = "GET" if response.status in {301, 302, 303} else method
                    continue
                if response.status >= 400:
                    raise AnonymousDouyinError(f"分享页请求失败（HTTP {response.status}）")
                if require_html:
                    content_type = response.headers.get("Content-Type", "").lower()
                    if content_type and not any(value in content_type for value in ("text/html", "application/json", "text/plain")):
                        raise AnonymousDouyinError("分享页返回了非 HTML 内容")
                if not read_body or method == "HEAD":
                    return "", str(response.url)
                chunks: List[bytes] = []
                size = 0
                async for chunk in response.content.iter_chunked(64 * 1024):
                    size += len(chunk)
                    if size > _MAX_HTML_BYTES:
                        raise AnonymousDouyinError("分享页内容过大")
                    chunks.append(chunk)
                charset = response.charset or "utf-8"
                return b"".join(chunks).decode(charset, errors="replace"), str(response.url)
        except aiohttp.ClientError as exc:
            raise AnonymousDouyinError(f"无法访问抖音分享页：{exc.__class__.__name__}") from exc
    raise AnonymousDouyinError("抖音链接跳转次数过多")


def _normalize_input_url(input_text: str) -> str:
    raw = extract_first_url(str(input_text or "")) or str(input_text or "").strip()
    raw = raw.rstrip(".,;，。；)）")
    if raw.startswith("//"):
        raw = "https:" + raw
    elif not raw.startswith(("http://", "https://")):
        raw = "https://" + raw
    return raw


def _validate_douyin_url(url: str) -> None:
    parsed = urlparse(url)
    if parsed.scheme.lower() != "https":
        raise AnonymousDouyinError("仅支持 HTTPS 抖音链接")
    host = (parsed.hostname or "").lower().rstrip(".")
    if not _is_douyin_host(host):
        raise AnonymousDouyinError("链接不是受支持的抖音域名")
    _reject_local_host(host)


def _is_douyin_host(host: str) -> bool:
    return host in {"douyin.com", "iesdouyin.com"} or host.endswith((".douyin.com", ".iesdouyin.com"))


def _is_short_host(host: str) -> bool:
    return host.lower() in {"v.douyin.com"}


def _reject_local_host(host: str) -> None:
    if host in {"localhost", "localhost.localdomain"}:
        raise AnonymousDouyinError("不允许访问本机地址")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        return
    if not address.is_global:
        raise AnonymousDouyinError("不允许访问私网地址")


def _extract_route(url: str) -> Optional[Tuple[str, str]]:
    match = _ROUTE_RE.search(urlparse(url).path)
    return (match.group(1).lower(), match.group(2)) if match else None


def _extract_id(url: str) -> str:
    match = _ID_RE.search(url)
    return match.group(1) if match else ""


def _ordered_types(preferred: Optional[str]) -> Iterable[str]:
    if preferred in _ROUTE_TYPES:
        yield preferred
    for value in _ROUTE_TYPES:
        if value != preferred:
            yield value


def _walk_dicts(value: Any) -> Iterable[Dict[str, Any]]:
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from _walk_dicts(child)
    elif isinstance(value, list):
        for child in value:
            yield from _walk_dicts(child)


def _extract_item_from_result(result: Any) -> Optional[Dict[str, Any]]:
    if not isinstance(result, dict):
        return None
    for key in _ITEM_LIST_KEYS:
        values = result.get(key)
        if isinstance(values, list):
            for value in values:
                if isinstance(value, dict) and value:
                    return value
    detail = result.get("aweme_detail") or result.get("awemeDetail")
    return detail if isinstance(detail, dict) and detail else None


def _canonicalize_video(value: Dict[str, Any]) -> Dict[str, Any]:
    video = copy.deepcopy(value)
    for canonical, alias in (
        ("play_addr", "playAddr"),
        ("download_addr", "downloadAddr"),
        ("play_addr_h264", "playAddrH264"),
        ("play_addr_265", "playAddr265"),
        ("origin_cover", "originCover"),
        ("dynamic_cover", "dynamicCover"),
        ("bit_rate", "bitRate"),
    ):
        _alias(video, canonical, alias)
    for key in ("play_addr", "download_addr", "play_addr_h264", "play_addr_265"):
        if isinstance(video.get(key), dict):
            video[key] = _canonicalize_address(video[key], media_kind="video")
    for key in ("cover", "origin_cover", "dynamic_cover"):
        if isinstance(video.get(key), dict):
            video[key] = _canonicalize_address(video[key], media_kind="image")
    if isinstance(video.get("bit_rate"), list):
        for entry in video["bit_rate"]:
            if isinstance(entry, dict):
                _alias(entry, "bit_rate", "bitRate")
                _alias(entry, "play_addr", "playAddr")
                if isinstance(entry.get("play_addr"), dict):
                    entry["play_addr"] = _canonicalize_address(entry["play_addr"], media_kind="video")
    return video


def _canonicalize_image(value: Any) -> Any:
    if not isinstance(value, dict):
        return value
    image = copy.deepcopy(value)
    for canonical, alias in (
        ("origin_image", "originImage"),
        ("display_image", "displayImage"),
        ("download_addr", "downloadAddr"),
        ("download_url", "downloadUrl"),
        ("download_url_list", "downloadUrlList"),
        ("watermark_free_download_url_list", "watermarkFreeDownloadUrlList"),
        ("owner_watermark_image", "ownerWatermarkImage"),
        ("live_photo", "livePhoto"),
        ("motion_photo", "motionPhoto"),
        ("live_video", "liveVideo"),
        ("live_video_info", "liveVideoInfo"),
        ("video_info", "videoInfo"),
        ("video_play_addr", "videoPlayAddr"),
        ("video_download_addr", "videoDownloadAddr"),
    ):
        _alias(image, canonical, alias)
    for key in (
        "origin_image",
        "display_image",
        "download_addr",
        "download_url",
        "owner_watermark_image",
    ):
        if isinstance(image.get(key), dict):
            image[key] = _canonicalize_address(image[key], media_kind="image")
    for key in ("video", "video_info", "live_video", "live_video_info"):
        if isinstance(image.get(key), dict):
            image[key] = _canonicalize_video(image[key])
    for key in ("live_photo", "motion_photo"):
        container = image.get(key)
        if isinstance(container, dict):
            for canonical, alias in (("video", "videoInfo"), ("video", "liveVideo")):
                _alias(container, canonical, alias)
            if isinstance(container.get("video"), dict):
                container["video"] = _canonicalize_video(container["video"])
            for address_key in ("play_addr", "download_addr", "video_play_addr", "video_download_addr"):
                if isinstance(container.get(address_key), dict):
                    container[address_key] = _canonicalize_address(container[address_key], media_kind="video")
    return image


def _canonicalize_address(value: Dict[str, Any], *, media_kind: str) -> Dict[str, Any]:
    address = copy.deepcopy(value)
    _alias(address, "url_list", "urlList")
    urls = address.get("url_list") if isinstance(address.get("url_list"), list) else []
    uri = str(address.get("uri") or "").strip()
    if uri and not urls:
        if uri.startswith("https://"):
            urls = [uri]
        elif media_kind == "video":
            urls = [f"https://www.douyin.com/aweme/v1/play/?video_id={uri}"]
    if urls:
        address["url_list"] = [str(url) for url in urls if str(url).startswith("https://")]
    return address


def _dict_value(container: Dict[str, Any], key: str) -> Dict[str, Any]:
    value = container.get(key)
    return value if isinstance(value, dict) else {}


def _alias(container: Dict[str, Any], canonical: str, alias: str, *, default: Any = None) -> None:
    if canonical not in container:
        if alias in container:
            container[canonical] = container[alias]
        elif default not in (None, ""):
            container[canonical] = default
