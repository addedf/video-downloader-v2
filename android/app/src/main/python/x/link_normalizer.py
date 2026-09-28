# -*- coding: utf-8 -*-
"""X 链接归一化：x.com / twitter.com / t.co / vxtwitter / fixupx → 推文 id。

归一化规则：
- host 白名单：x.com、twitter.com（含 www./mobile. 前缀）、vxtwitter.com、
  fixupx.com 及其 www. 变体；
- t.co 短链需要网络重定向解析，由调用方处理（``needs_redirect=True``）；
- 路径取 ``/<user>/status(es)/<id>``，容忍末尾 ``/video/1``、``/photo/1``
  与 query（``?s=20`` 等）；
- 直接粘贴纯数字 id 也接受。
"""
import re
from urllib.parse import urlparse

X_HOSTS = {
    "x.com",
    "www.x.com",
    "mobile.x.com",
    "twitter.com",
    "www.twitter.com",
    "mobile.twitter.com",
    "vxtwitter.com",
    "www.vxtwitter.com",
    "fixupx.com",
    "www.fixupx.com",
}
SHORT_HOSTS = {"t.co", "www.t.co"}
_STATUS_ID_RE = re.compile(r"/status(?:es)?/(\d+)")
_URL_RE = re.compile(r"https?://[^\s\"'<>，。；）)】]+")
_TRAILING = ".,;，。；)）】"


def extract_first_url(text: str) -> str | None:
    """从分享文本里抽第一条 URL，去掉中文/英文尾随标点。"""
    match = _URL_RE.search(text or "")
    if not match:
        return None
    return match.group(0).rstrip(_TRAILING)


def normalize(text: str) -> dict:
    """分享文本 → {'platform': 'x', 'tweet_id': str, 'url': str, 'needs_redirect': bool}。

    解析失败抛 ValueError。
    """
    url = extract_first_url(text or "")
    candidate = (url or (text or "").strip()).strip()
    if not candidate:
        raise ValueError("分享内容为空")

    if candidate.isdigit():
        return {
            "platform": "x",
            "tweet_id": candidate,
            "url": f"https://x.com/i/status/{candidate}",
            "needs_redirect": False,
        }

    parsed = urlparse(candidate if "://" in candidate else f"https://{candidate}")
    host = (parsed.hostname or "").lower()
    path = parsed.path or ""

    if host in SHORT_HOSTS:
        if not path or path == "/":
            raise ValueError(f"t.co 短链不完整：{candidate}")
        return {
            "platform": "x",
            "tweet_id": "",
            "url": f"https://t.co{path}",
            "needs_redirect": True,
        }

    if host not in X_HOSTS:
        raise ValueError(f"不是 X/Twitter 链接：{candidate}")

    match = _STATUS_ID_RE.search(path)
    if not match:
        raise ValueError(f"链接里没有推文 id：{candidate}")
    tweet_id = match.group(1)
    screen_name = path.split("/status", 1)[0].strip("/") or None
    canonical = (
        f"https://x.com/{screen_name}/status/{tweet_id}"
        if screen_name
        else f"https://x.com/i/status/{tweet_id}"
    )
    return {
        "platform": "x",
        "tweet_id": tweet_id,
        "url": canonical,
        "needs_redirect": False,
    }


def from_redirect(location: str) -> dict:
    """t.co 重定向落点 → 归一化结果（复用同一套 host/路径规则）。"""
    return normalize(location)
