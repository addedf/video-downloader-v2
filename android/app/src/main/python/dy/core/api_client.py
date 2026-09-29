from __future__ import annotations

import asyncio
import random
from typing import Any, Dict, Optional, Tuple
from urllib.parse import urlencode

import aiohttp

from auth import MsTokenManager
from utils.cookie_utils import sanitize_cookies
from utils.logger import setup_logger
from utils.xbogus import XBogus

try:
    from utils.abogus import ABogus, BrowserFingerprintGenerator
except Exception:  # pragma: no cover - optional dependency
    ABogus = None
    BrowserFingerprintGenerator = None

logger = setup_logger("APIClient")

_USER_AGENT_POOL = [
    (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
    ),
    (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
    ),
]


class DouyinAPIClient:
    BASE_URL = "https://www.douyin.com"
    _BROWSER_COOKIE_BLOCKLIST = {
        "sessionid",
        "sessionid_ss",
        "sid_tt",
        "sid_guard",
        "uid_tt",
        "uid_tt_ss",
        "passport_auth_status",
        "passport_auth_status_ss",
        "passport_assist_user",
        "passport_auth_mix_state",
        "passport_mfa_token",
        "login_time",
    }

    def __init__(self, cookies: Dict[str, str], proxy: Optional[str] = None):
        self.cookies = sanitize_cookies(cookies or {})
        self.proxy = str(proxy or "").strip()
        self._session: Optional[aiohttp.ClientSession] = None
        self._browser_post_aweme_items: Dict[str, Dict[str, Any]] = {}
        self._browser_post_stats: Dict[str, int] = {}
        selected_ua = random.choice(_USER_AGENT_POOL)
        self.headers = {
            "User-Agent": selected_ua,
            "Referer": "https://www.douyin.com/?recommend=1",
            "Accept": "*/*",
            "Accept-Encoding": "gzip, deflate",
            "Accept-Language": "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
        }
        self._signer = XBogus(self.headers["User-Agent"])
        self._ms_token_manager = MsTokenManager(user_agent=self.headers["User-Agent"])
        self._ms_token = (self.cookies.get("msToken") or "").strip()
        self._abogus_enabled = ABogus is not None and BrowserFingerprintGenerator is not None

    async def __aenter__(self) -> "DouyinAPIClient":
        await self._ensure_session()
        return self

    async def __aexit__(self, exc_type, exc, tb):
        await self.close()

    async def _ensure_session(self):
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(
                headers=self.headers,
                cookies=self.cookies,
                timeout=aiohttp.ClientTimeout(total=30),
                raise_for_status=False,
            )

    async def close(self):
        if self._session and not self._session.closed:
            await self._session.close()

    async def get_session(self) -> aiohttp.ClientSession:
        await self._ensure_session()
        if self._session is None:
            raise RuntimeError("Failed to create aiohttp session")
        return self._session

    async def _ensure_ms_token(self) -> str:
        if self._ms_token:
            return self._ms_token

        token = await asyncio.to_thread(
            self._ms_token_manager.ensure_ms_token,
            self.cookies,
        )
        self._ms_token = token.strip()
        if self._ms_token:
            self.cookies["msToken"] = self._ms_token
            if self._session and not self._session.closed:
                self._session.cookie_jar.update_cookies({"msToken": self._ms_token})
        return self._ms_token

    async def _default_query(self) -> Dict[str, Any]:
        ms_token = await self._ensure_ms_token()
        return {
            "device_platform": "webapp",
            "aid": "6383",
            "channel": "channel_pc_web",
            "update_version_code": "170400",
            "pc_client_type": "1",
            "pc_libra_divert": "Windows",
            "version_code": "290100",
            "version_name": "29.1.0",
            "cookie_enabled": "true",
            "screen_width": "1536",
            "screen_height": "864",
            "browser_language": "zh-CN",
            "browser_platform": "Win32",
            "browser_name": "Chrome",
            "browser_version": "139.0.0.0",
            "browser_online": "true",
            "engine_name": "Blink",
            "engine_version": "139.0.0.0",
            "os_name": "Windows",
            "os_version": "10",
            "cpu_core_num": "16",
            "device_memory": "8",
            "platform": "PC",
            "downlink": "10",
            "effective_type": "4g",
            "round_trip_time": "200",
            "support_h265": "1",
            "support_dash": "1",
            "uifid": "",
            "msToken": ms_token,
        }

    def sign_url(self, url: str) -> Tuple[str, str]:
        signed_url, _xbogus, ua = self._signer.build(url)
        return signed_url, ua

    def build_signed_path(self, path: str, params: Dict[str, Any]) -> Tuple[str, str]:
        query = urlencode(params)
        base_url = f"{self.BASE_URL}{path}"
        ab_signed = self._build_abogus_url(base_url, query)
        if ab_signed:
            return ab_signed
        return self.sign_url(f"{base_url}?{query}")

    def _build_abogus_url(self, base_url: str, query: str) -> Optional[Tuple[str, str]]:
        if not self._abogus_enabled:
            return None

        try:
            browser_fp = BrowserFingerprintGenerator.generate_fingerprint("Chrome")
            signer = ABogus(fp=browser_fp, user_agent=self.headers["User-Agent"])
            params_with_ab, _ab, ua, _body = signer.generate_abogus(query, "")
            return f"{base_url}?{params_with_ab}", ua
        except Exception as exc:
            logger.warning("Failed to generate a_bogus, fallback to X-Bogus: %s", exc)
            return None

    async def _request_json(
        self,
        path: str,
        params: Dict[str, Any],
        *,
        suppress_error: bool = False,
        max_retries: int = 3,
    ) -> Dict[str, Any]:
        await self._ensure_session()
        delays = [1, 2, 5]
        last_exc: Optional[Exception] = None

        for attempt in range(max_retries):
            signed_url, ua = self.build_signed_path(path, params)
            try:
                async with self._session.get(
                    signed_url,
                    headers={**self.headers, "User-Agent": ua},
                    proxy=self.proxy or None,
                ) as response:
                    if response.status == 200:
                        body = await response.read()
                        if not body:
                            # Empty 200 response is a common anti-bot signal
                            # from Douyin. Retry with a fresh signature.
                            logger.warning(
                                "Empty 200 response for %s (attempt %d/%d), "
                                "likely anti-bot; will retry",
                                path,
                                attempt + 1,
                                max_retries,
                            )
                            last_exc = RuntimeError(f"Empty 200 response for {path} (anti-bot)")
                            if attempt < max_retries - 1:
                                delay = delays[min(attempt, len(delays) - 1)]
                                await asyncio.sleep(delay)
                            continue
                        try:
                            data = await response.json(content_type=None)
                        except Exception:
                            import json as _json

                            try:
                                data = _json.loads(body)
                            except Exception:
                                logger.warning(
                                    "Non-JSON 200 response for %s, length=%d",
                                    path,
                                    len(body),
                                )
                                return {}
                        return data if isinstance(data, dict) else {}
                    if response.status < 500 and response.status != 429:
                        log_fn = logger.debug if suppress_error else logger.error
                        log_fn(
                            "Request failed: path=%s, status=%s",
                            path,
                            response.status,
                        )
                        return {}
                    last_exc = RuntimeError(f"HTTP {response.status} for {path}")
            except Exception as exc:
                last_exc = exc

            if attempt < max_retries - 1:
                delay = delays[min(attempt, len(delays) - 1)]
                logger.debug(
                    "Request retry %d/%d for %s in %ds",
                    attempt + 1,
                    max_retries,
                    path,
                    delay,
                )
                await asyncio.sleep(delay)

        log_fn = logger.debug if suppress_error else logger.error
        log_fn("Request failed after %d attempts: path=%s, error=%s", max_retries, path, last_exc)
        return {}

    # aid=1128 works for videos but filters out image/note content;
    # aid=6383 works for notes/gallery but may miss some video content.
    _DETAIL_AID_CANDIDATES = ("6383", "1128")

    async def get_video_detail(
        self, aweme_id: str, *, suppress_error: bool = False
    ) -> Optional[Dict[str, Any]]:
        for aid in self._DETAIL_AID_CANDIDATES:
            params = await self._default_query()
            params.update(
                {
                    "aweme_id": aweme_id,
                    "aid": aid,
                }
            )

            data = await self._request_json(
                "/aweme/v1/web/aweme/detail/",
                params,
                suppress_error=(suppress_error or aid != self._DETAIL_AID_CANDIDATES[-1]),
            )
            if not data:
                continue

            detail = data.get("aweme_detail")
            if detail:
                return detail

            # API returned data but aweme_detail is null — check if content was
            # filtered (e.g. filter_reason="images_base" for note/gallery).
            filter_info = data.get("filter_detail")
            if isinstance(filter_info, dict) and filter_info.get("filter_reason"):
                logger.info(
                    "Aweme %s filtered with aid=%s (reason=%s), retrying",
                    aweme_id,
                    aid,
                    filter_info["filter_reason"],
                )
                continue

            # aweme_detail is null without a filter reason — no retry needed
            break

        return None

    async def resolve_short_url(
        self, short_url: str, *, timeout_seconds: float = 10.0
    ) -> Optional[str]:
        """跟随短链 302，返回最终 URL。失败时返回 None。

        单独设置较短超时（默认 10s），避免被目标站挂死后拖慢整轮下载。
        HTTP 状态码 ≥ 400 时视为解析失败，返回 None 以避免把错误页 URL
        继续喂给下游 parser，从而在下游触发更隐晦的 "Unsupported URL" 噪声。
        """
        try:
            await self._ensure_session()
            async with self._session.get(
                short_url,
                allow_redirects=True,
                timeout=aiohttp.ClientTimeout(total=timeout_seconds),
                proxy=self.proxy or None,
            ) as response:
                final_url = str(response.url)
                if response.status >= 400:
                    logger.warning(
                        "Short URL resolved with HTTP %s (treated as failure): %s -> %s",
                        response.status,
                        short_url,
                        final_url,
                    )
                    return None
                return final_url
        except asyncio.TimeoutError:
            logger.error(
                "Timeout resolving short URL after %.1fs: %s",
                timeout_seconds,
                short_url,
            )
            return None
        except Exception as e:
            logger.error("Failed to resolve short URL: %s, error: %s", short_url, e)
            return None

