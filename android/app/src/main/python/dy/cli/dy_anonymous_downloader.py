"""Small unsigned media downloader for public Douyin resolve results."""

from __future__ import annotations

import ipaddress
import os
import time
from pathlib import Path
from types import SimpleNamespace
from typing import Any, Dict, Optional, Tuple
from urllib.parse import urljoin, urlparse

from dy.cli.dy_resource_normalizer import build_no_watermark_url

import aiohttp

_USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
)
_CONTENT_SUFFIXES = {
    "image/jpeg": ".jpg",
    "image/png": ".png",
    "image/webp": ".webp",
    "image/gif": ".gif",
}


class AnonymousMediaDownloader:
    """Compatibility adapter for ``download_selected_resources`` without signing."""

    def __init__(
        self,
        progress_reporter=None,
        *,
        proxy: Optional[str] = None,
        retries: int = 2,
        cookies: Optional[Dict[str, str]] = None,
    ):
        self.progress_reporter = progress_reporter
        self.proxy = proxy
        self.retries = max(1, int(retries or 1))
        self.cookies = dict(cookies or {})
        self.cookie_header = "; ".join(
            f"{key}={value}" for key, value in self.cookies.items() if value
        )
        self.file_manager = SimpleNamespace(_android_progress_reporter=progress_reporter)
        self.api_client = self
        self._session: Optional[aiohttp.ClientSession] = None

    async def __aenter__(self):
        timeout = aiohttp.ClientTimeout(total=90, connect=8, sock_read=45)
        self._session = aiohttp.ClientSession(timeout=timeout, headers=self._download_headers())
        return self

    async def __aexit__(self, exc_type, exc, tb):
        if self._session is not None:
            await self._session.close()
            self._session = None

    async def get_session(self) -> aiohttp.ClientSession:
        if self._session is None:
            raise RuntimeError("AnonymousMediaDownloader is not started")
        return self._session

    def _download_headers(self, user_agent: Optional[str] = None) -> Dict[str, str]:
        return {
            "User-Agent": user_agent or _USER_AGENT,
            "Referer": "https://www.douyin.com/",
            "Accept": "*/*",
        }

    def _build_no_watermark_url(self, aweme_data: Dict[str, Any]):
        url = build_no_watermark_url(aweme_data)
        return (url, self._download_headers()) if url else None

    async def _download_with_retry(
        self,
        url: str,
        save_path: Path,
        session: aiohttp.ClientSession,
        *,
        headers: Optional[Dict[str, str]] = None,
        prefer_response_content_type: bool = False,
        return_saved_path: bool = False,
        **_kwargs,
    ):
        for attempt in range(self.retries):
            tmp_path = save_path.with_suffix(save_path.suffix + ".part")
            try:
                response, final_url = await self._open_validated(session, url, headers=headers)
                async with response:
                    if response.status not in {200, 206}:
                        continue
                    target = self._target_path(save_path, response, prefer_response_content_type)
                    tmp_path = target.with_suffix(target.suffix + ".part")
                    target.parent.mkdir(parents=True, exist_ok=True)
                    total = int(response.headers.get("Content-Length") or 0)
                    downloaded = 0
                    started = time.monotonic()
                    with open(tmp_path, "wb") as output:
                        async for chunk in response.content.iter_chunked(256 * 1024):
                            if not chunk:
                                continue
                            output.write(chunk)
                            downloaded += len(chunk)
                            elapsed = max(0.001, time.monotonic() - started)
                            if self.progress_reporter:
                                self.progress_reporter.on_file_progress(
                                    downloaded,
                                    total,
                                    int(downloaded / elapsed),
                                )
                    os.replace(tmp_path, target)
                    if self.progress_reporter:
                        elapsed = max(0.001, time.monotonic() - started)
                        self.progress_reporter.on_file_progress(
                            downloaded,
                            total or downloaded,
                            int(downloaded / elapsed),
                            force=True,
                        )
                    return target if return_saved_path else True
            except (aiohttp.ClientError, OSError, ValueError):
                if tmp_path.exists():
                    tmp_path.unlink(missing_ok=True)
                if attempt + 1 >= self.retries:
                    return False
        return False

    async def _open_validated(
        self,
        session: aiohttp.ClientSession,
        url: str,
        *,
        headers: Optional[Dict[str, str]],
    ) -> Tuple[aiohttp.ClientResponse, str]:
        current = url
        for _ in range(6):
            _validate_media_url(current)
            request_headers = dict(headers or self._download_headers())
            if self.cookie_header and _is_cookie_host(urlparse(current).hostname or ""):
                request_headers["Cookie"] = self.cookie_header
            response = await session.get(
                current,
                headers=request_headers,
                allow_redirects=False,
                proxy=self.proxy,
            )
            if response.status in {301, 302, 303, 307, 308}:
                location = response.headers.get("Location")
                response.release()
                if not location:
                    raise ValueError("媒体跳转缺少目标地址")
                current = urljoin(current, location)
                continue
            _validate_media_url(str(response.url))
            return response, str(response.url)
        raise ValueError("媒体跳转次数过多")

    @staticmethod
    def _target_path(
        save_path: Path,
        response: aiohttp.ClientResponse,
        prefer_response_content_type: bool,
    ) -> Path:
        if not prefer_response_content_type:
            return save_path
        content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
        suffix = _CONTENT_SUFFIXES.get(content_type)
        return save_path.with_suffix(suffix) if suffix else save_path


def _validate_media_url(url: str) -> None:
    parsed = urlparse(url)
    if parsed.scheme.lower() != "https":
        raise ValueError("媒体下载仅允许 HTTPS")
    host = (parsed.hostname or "").lower().rstrip(".")
    if not host or host in {"localhost", "localhost.localdomain"}:
        raise ValueError("媒体下载地址无效")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        return
    if not address.is_global:
        raise ValueError("媒体下载不允许私网地址")


def _is_cookie_host(host: str) -> bool:
    normalized = str(host or "").lower().rstrip(".")
    return normalized.endswith((".douyin.com", ".iesdouyin.com", ".douyinvod.com"))
