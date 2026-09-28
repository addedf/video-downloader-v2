from __future__ import annotations

import re
from typing import Any, List
from urllib.parse import urlsplit, urlunsplit

SHORT_XHS_URL_PATTERN = re.compile(
    r"(?:https?://)?(?:www\.)?xhslink\.(?:com|cn)/[^\s\"<>\\^`{|}，。；！？、【】《》]+"
)

_HTTPS_UPGRADE_SUFFIXES = (
    "xiaohongshu.com",
    "xhscdn.com",
)


def normalize_xhs_media_url(value: Any) -> str:
    """Return a usable HTTPS media URL without logging or decoding its query."""
    text = str(value or "").strip()
    if not text:
        return ""
    try:
        parsed = urlsplit(text)
    except ValueError:
        return ""
    host = (parsed.hostname or "").lower()
    if parsed.scheme == "https" and host:
        return text
    if parsed.scheme == "http" and _matches_suffix(host, _HTTPS_UPGRADE_SUFFIXES):
        return urlunsplit(("https", parsed.netloc, parsed.path, parsed.query, parsed.fragment))
    return ""


def normalize_xhs_media_urls(value: Any, *, keep_empty: bool = False) -> List[str]:
    if isinstance(value, str):
        values = value.split()
    elif isinstance(value, (list, tuple)):
        values = list(value)
    else:
        values = []
    normalized = [normalize_xhs_media_url(item) for item in values]
    return normalized if keep_empty else [item for item in normalized if item]


def _matches_suffix(host: str, suffixes: tuple[str, ...]) -> bool:
    return any(host == suffix or host.endswith(f".{suffix}") for suffix in suffixes)
