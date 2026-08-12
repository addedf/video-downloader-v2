import asyncio
import logging
import os
import time
from typing import Any, Dict, Optional

from common.android_utils import elapsed_ms, host, speed_bytes_per_second, speed_kbps
from auth import MsTokenManager
from core import DouyinAPIClient
from core.downloader_base import BaseDownloader
from storage import FileManager
from utils.logger import setup_logger

logger = setup_logger("DyApiDiagnostics", console_level=logging.INFO)

_PATCHED = False
_ANDROID_DOWNLOAD_CHUNK_SIZE = 1024 * 1024
_ANDROID_TOTAL_TIMEOUT_SECONDS = 180
_ANDROID_CONNECT_TIMEOUT_SECONDS = 10
_ANDROID_IDLE_READ_TIMEOUT_SECONDS = 30
_ANDROID_CDN_PROBE_BYTES = 256 * 1024
_ANDROID_CDN_PROBE_CONNECT_TIMEOUT_SECONDS = 2
_ANDROID_CDN_PROBE_READ_TIMEOUT_SECONDS = 4
_ANDROID_CDN_PROBE_TOTAL_TIMEOUT_SECONDS = 6
_DOWNLOAD_METRICS = []
_API_METRICS = []


def reset_android_metrics() -> None:
    _DOWNLOAD_METRICS.clear()
    _API_METRICS.clear()


def consume_android_metrics() -> Dict[str, Any]:
    return {
        "download_metrics": list(_DOWNLOAD_METRICS),
        "api_metrics": list(_API_METRICS),
    }


def install_android_api_diagnostics() -> None:
    global _PATCHED
    if _PATCHED:
        return
    _patch_ms_token_manager()
    _patch_api_client()
    _patch_force_download()
    _patch_asset_downloads()
    _PATCHED = True
    logger.info("android_api_diagnostics.installed")


def _patch_ms_token_manager() -> None:
    original_ensure_ms_token = MsTokenManager.ensure_ms_token
    original_gen_real_ms_token = MsTokenManager.gen_real_ms_token
    original_load_conf = MsTokenManager._load_f2_ms_token_conf

    def ensure_ms_token(self: MsTokenManager, cookies: Dict[str, str]) -> str:
        started_at = time.perf_counter()
        current = (cookies or {}).get("msToken", "").strip()
        if current:
            logger.info(
                "ms_token.use_cookie duration_ms=%s length=%s",
                elapsed_ms(started_at),
                len(current),
            )
            return current

        token = self.gen_false_ms_token()
        logger.info(
            "ms_token.use_fallback reason=android_skip_github duration_ms=%s length=%s",
            elapsed_ms(started_at),
            len(token),
        )
        return token

    def gen_real_ms_token(self: MsTokenManager) -> Optional[str]:
        started_at = time.perf_counter()
        token = original_gen_real_ms_token(self)
        logger.info(
            "ms_token.real.done duration_ms=%s ok=%s",
            elapsed_ms(started_at),
            bool(token),
        )
        return token

    def load_f2_ms_token_conf(self: MsTokenManager) -> Optional[Dict[str, Any]]:
        started_at = time.perf_counter()
        conf = original_load_conf(self)
        logger.info(
            "ms_token.github_conf.done duration_ms=%s ok=%s",
            elapsed_ms(started_at),
            bool(conf),
        )
        return conf

    MsTokenManager.ensure_ms_token = ensure_ms_token
    MsTokenManager.gen_real_ms_token = gen_real_ms_token
    MsTokenManager._load_f2_ms_token_conf = load_f2_ms_token_conf


def _patch_api_client() -> None:
    original_ensure_ms_token = DouyinAPIClient._ensure_ms_token
    original_default_query = DouyinAPIClient._default_query
    original_request_json = DouyinAPIClient._request_json
    original_get_video_detail = DouyinAPIClient.get_video_detail

    async def ensure_ms_token(self: DouyinAPIClient) -> str:
        started_at = time.perf_counter()
        token = await original_ensure_ms_token(self)
        logger.info(
            "api.ms_token.ready duration_ms=%s length=%s",
            elapsed_ms(started_at),
            len(token or ""),
        )
        return token

    async def default_query(self: DouyinAPIClient) -> Dict[str, Any]:
        started_at = time.perf_counter()
        query = await original_default_query(self)
        logger.info("api.default_query.done duration_ms=%s", elapsed_ms(started_at))
        return query

    async def request_json(
        self: DouyinAPIClient,
        path: str,
        params: Dict[str, Any],
        *,
        suppress_error: bool = False,
        max_retries: int = 3,
    ) -> Dict[str, Any]:
        started_at = time.perf_counter()
        logger.info(
            "api.request.begin path=%s aid=%s max_retries=%s",
            path,
            params.get("aid"),
            max_retries,
        )
        data = await original_request_json(
            self,
            path,
            params,
            suppress_error=suppress_error,
            max_retries=max_retries,
        )
        duration_ms = elapsed_ms(started_at)
        logger.info(
            "api.request.done path=%s aid=%s duration_ms=%s ok=%s",
            path,
            params.get("aid"),
            duration_ms,
            bool(data),
        )
        _API_METRICS.append(
            {
                "name": path,
                "ok": bool(data),
                "duration_ms": duration_ms,
                "attempts": [
                    {
                        "aid": str(params.get("aid") or ""),
                        "ok": bool(data),
                        "duration_ms": duration_ms,
                    }
                ],
            }
        )
        return data

    async def get_video_detail(
        self: DouyinAPIClient,
        aweme_id: str,
        *,
        suppress_error: bool = False,
    ):
        started_at = time.perf_counter()
        logger.info("video_detail.begin aweme_id=%s", aweme_id)
        detail = await original_get_video_detail(
            self,
            aweme_id,
            suppress_error=suppress_error,
        )
        duration_ms = elapsed_ms(started_at)
        logger.info(
            "video_detail.done aweme_id=%s duration_ms=%s ok=%s",
            aweme_id,
            duration_ms,
            bool(detail),
        )
        _API_METRICS.append(
            {
                "name": "get_video_detail",
                "ok": bool(detail),
                "duration_ms": duration_ms,
                "attempts": [],
            }
        )
        return detail

    DouyinAPIClient._ensure_ms_token = ensure_ms_token
    DouyinAPIClient._default_query = default_query
    DouyinAPIClient._request_json = request_json
    DouyinAPIClient.get_video_detail = get_video_detail


def _patch_force_download() -> None:
    original_should_download = BaseDownloader._should_download

    async def should_download(self: BaseDownloader, aweme_id: str) -> bool:
        if self.config.get("force_download", False):
            logger.info("force_download.enabled aweme_id=%s", aweme_id)
            return True
        return await original_should_download(self, aweme_id)

    BaseDownloader._should_download = should_download


def _patch_asset_downloads() -> None:
    original_download_aweme_assets = BaseDownloader._download_aweme_assets
    original_download_with_retry = BaseDownloader._download_with_retry
    original_build_no_watermark_url = BaseDownloader._build_no_watermark_url

    async def download_aweme_assets(
        self: BaseDownloader,
        aweme_data: Dict[str, Any],
        author_name: str,
        mode: Optional[str] = None,
        *,
        db_batch=None,
    ) -> bool:
        started_at = time.perf_counter()
        aweme_id = aweme_data.get("aweme_id")
        media_type = _detect_media_type_for_log(aweme_data)
        logger.info(
            "asset.aweme.begin aweme_id=%s media_type=%s mode=%s author=%s",
            aweme_id,
            media_type,
            mode,
            author_name,
        )
        setattr(self.file_manager, "_android_progress_reporter", self.progress_reporter)
        selected_cdn = await _select_fastest_no_watermark_candidate(
            self, aweme_data, original_build_no_watermark_url
        )
        original_instance_builder = getattr(self, "_build_no_watermark_url")
        if selected_cdn is not None:
            self._build_no_watermark_url = lambda _aweme_data: selected_cdn
        try:
            ok = await original_download_aweme_assets(
                self,
                aweme_data,
                author_name,
                mode,
                db_batch=db_batch,
            )
        finally:
            self._build_no_watermark_url = original_instance_builder
        logger.info(
            "asset.aweme.done aweme_id=%s media_type=%s duration_ms=%s ok=%s",
            aweme_id,
            media_type,
            elapsed_ms(started_at),
            ok,
        )
        return ok

    async def download_with_retry(
        self: BaseDownloader,
        url: str,
        save_path,
        session,
        *,
        headers=None,
        optional: bool = False,
        prefer_response_content_type: bool = False,
        return_saved_path: bool = False,
    ):
        started_at = time.perf_counter()
        logger.info(
            "asset.retry.begin file=%s host=%s optional=%s",
            getattr(save_path, "name", save_path),
            host(url),
            optional,
        )
        result = await original_download_with_retry(
            self,
            url,
            save_path,
            session,
            headers=headers,
            optional=optional,
            prefer_response_content_type=prefer_response_content_type,
            return_saved_path=return_saved_path,
        )
        logger.info(
            "asset.retry.done file=%s host=%s duration_ms=%s ok=%s",
            getattr(save_path, "name", save_path),
            host(url),
            elapsed_ms(started_at),
            bool(result),
        )
        return result

    async def download_file(
        self: FileManager,
        url: str,
        save_path,
        session=None,
        headers=None,
        proxy=None,
        *,
        prefer_response_content_type: bool = False,
        return_saved_path: bool = False,
    ):
        started_at = time.perf_counter()
        first_chunk_ms: Optional[int] = None
        final_url = url
        final_path = save_path
        tmp_path = save_path.with_suffix(save_path.suffix + ".tmp")
        written = 0
        should_close = False
        logger.info(
            "asset.file.begin file=%s host=%s suffix=%s return_path=%s",
            getattr(save_path, "name", save_path),
            host(url),
            getattr(save_path, "suffix", ""),
            return_saved_path,
        )
        if session is None:
            import aiohttp

            default_headers = headers or {
                "User-Agent": "Mozilla/5.0 (Linux; Android 15; Mobile) "
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36",
                "Referer": "https://www.douyin.com/",
                "Accept": "*/*",
            }
            session = aiohttp.ClientSession(headers=default_headers)
            should_close = True

        try:
            import aiofiles
            import aiohttp

            async with session.get(
                url,
                timeout=aiohttp.ClientTimeout(
                    total=_ANDROID_TOTAL_TIMEOUT_SECONDS,
                    sock_connect=_ANDROID_CONNECT_TIMEOUT_SECONDS,
                    sock_read=_ANDROID_IDLE_READ_TIMEOUT_SECONDS,
                ),
                headers=headers,
                proxy=proxy or None,
            ) as response:
                final_url = str(response.url)
                expected_size = response.content_length
                logger.info(
                    "asset.file.response file=%s status=%s final_host=%s expected_bytes=%s response_ms=%s",
                    getattr(save_path, "name", save_path),
                    response.status,
                    host(final_url),
                    expected_size or 0,
                    elapsed_ms(started_at),
                )
                if response.status != 200:
                    duration_ms = elapsed_ms(started_at)
                    logger.info(
                        "asset.file.done file=%s host=%s duration_ms=%s ok=False status=%s bytes=0",
                        getattr(save_path, "name", save_path),
                        host(url),
                        duration_ms,
                        response.status,
                    )
                    _DOWNLOAD_METRICS.append(
                        {
                            "file_name": getattr(save_path, "name", str(save_path)),
                            "ok": False,
                            "status": int(response.status or 0),
                            "host": host(url),
                            "final_host": host(final_url),
                            "bytes": 0,
                            "expected_bytes": int(expected_size or 0),
                            "duration_ms": duration_ms,
                            "first_chunk_ms": 0,
                            "speed_kbps": 0,
                        }
                    )
                    return False

                final_path = self._resolve_save_path_from_content_type(
                    save_path,
                    response.headers,
                    prefer_response_content_type=prefer_response_content_type,
                )
                tmp_path = final_path.with_suffix(final_path.suffix + ".tmp")
                reporter = getattr(self, "_android_progress_reporter", None)
                async with aiofiles.open(tmp_path, "wb") as file:
                    async for chunk in response.content.iter_chunked(_ANDROID_DOWNLOAD_CHUNK_SIZE):
                        if first_chunk_ms is None:
                            first_chunk_ms = elapsed_ms(started_at)
                        await file.write(chunk)
                        written += len(chunk)
                        _report_file_progress(
                            reporter,
                            written,
                            expected_size,
                            started_at,
                        )

                if expected_size is not None and written != expected_size:
                    duration_ms = elapsed_ms(started_at)
                    tmp_path.unlink(missing_ok=True)
                    logger.info(
                        "asset.file.done file=%s host=%s duration_ms=%s ok=False bytes=%s expected_bytes=%s first_chunk_ms=%s reason=size_mismatch",
                        getattr(final_path, "name", final_path),
                        host(url),
                        duration_ms,
                        written,
                        expected_size,
                        first_chunk_ms or 0,
                    )
                    _DOWNLOAD_METRICS.append(
                        {
                            "file_name": getattr(final_path, "name", str(final_path)),
                            "ok": False,
                            "status": 200,
                            "host": host(url),
                            "final_host": host(final_url),
                            "bytes": int(written or 0),
                            "expected_bytes": int(expected_size or 0),
                            "duration_ms": duration_ms,
                            "first_chunk_ms": int(first_chunk_ms or 0),
                            "speed_kbps": speed_kbps(written, duration_ms),
                            "error": "size_mismatch",
                        }
                    )
                    return False

                os.replace(str(tmp_path), str(final_path))
                duration_ms = elapsed_ms(started_at)
                _report_file_progress(
                    reporter,
                    written,
                    expected_size,
                    started_at,
                    force=True,
                )
                logger.info(
                    "asset.file.done file=%s host=%s final_host=%s duration_ms=%s ok=True bytes=%s expected_bytes=%s first_chunk_ms=%s speed_kbps=%s",
                    getattr(final_path, "name", final_path),
                    host(url),
                    host(final_url),
                    duration_ms,
                    written,
                    expected_size or 0,
                    first_chunk_ms or 0,
                    speed_kbps(written, duration_ms),
                )
                _DOWNLOAD_METRICS.append(
                    {
                        "file_name": getattr(final_path, "name", str(final_path)),
                        "ok": True,
                        "status": 200,
                        "host": host(url),
                        "final_host": host(final_url),
                        "bytes": int(written or 0),
                        "expected_bytes": int(expected_size or 0),
                        "duration_ms": duration_ms,
                        "first_chunk_ms": int(first_chunk_ms or 0),
                        "speed_kbps": speed_kbps(written, duration_ms),
                    }
                )
                return final_path if return_saved_path else True
        except Exception as exc:
            duration_ms = elapsed_ms(started_at)
            tmp_path.unlink(missing_ok=True)
            logger.info(
                "asset.file.done file=%s host=%s final_host=%s duration_ms=%s ok=False bytes=%s first_chunk_ms=%s error=%s",
                getattr(final_path, "name", final_path),
                host(url),
                host(final_url),
                duration_ms,
                written,
                first_chunk_ms or 0,
                type(exc).__name__,
            )
            _DOWNLOAD_METRICS.append(
                {
                    "file_name": getattr(final_path, "name", str(final_path)),
                    "ok": False,
                    "status": 0,
                    "host": host(url),
                    "final_host": host(final_url),
                    "bytes": int(written or 0),
                    "expected_bytes": 0,
                    "duration_ms": duration_ms,
                    "first_chunk_ms": int(first_chunk_ms or 0),
                    "speed_kbps": speed_kbps(written, duration_ms),
                    "error": type(exc).__name__,
                }
            )
            return False
        finally:
            if should_close:
                await session.close()

    BaseDownloader._download_aweme_assets = download_aweme_assets
    BaseDownloader._download_with_retry = download_with_retry
    FileManager.download_file = download_file

def _file_size(path) -> int:
    try:
        return int(path.stat().st_size)
    except Exception:
        return 0


def _collect_no_watermark_candidates(
    downloader: BaseDownloader,
    aweme_data: Dict[str, Any],
) -> list:
    video = aweme_data.get("video", {})
    play_addr = downloader._pick_highest_quality_play_addr(video) or video.get("play_addr", {})
    url_candidates = [candidate for candidate in (play_addr.get("url_list") or []) if candidate]
    url_candidates.sort(key=lambda url: 0 if "watermark=0" in url else 1)

    candidates = []
    for candidate in url_candidates:
        if downloader._is_watermarked_media_url(candidate):
            continue
        parsed = host(candidate)
        headers = downloader._download_headers()
        if parsed.endswith("douyin.com") and "X-Bogus=" not in candidate:
            signed_url, user_agent = downloader.api_client.sign_url(candidate)
            candidates.append((signed_url, downloader._download_headers(user_agent=user_agent)))
        else:
            candidates.append((candidate, headers))
    return candidates


def _deduplicate_candidates(candidates: list) -> list:
    deduped = []
    seen = set()
    for candidate in candidates:
        if not candidate:
            continue
        url, headers = candidate
        if not url or url in seen:
            continue
        seen.add(url)
        deduped.append((url, headers))
    return deduped


async def _select_fastest_no_watermark_candidate(
    downloader: BaseDownloader,
    aweme_data: Dict[str, Any],
    original_build_no_watermark_url,
):
    selected = original_build_no_watermark_url(downloader, aweme_data)
    candidates = _collect_no_watermark_candidates(downloader, aweme_data)
    if selected is not None:
        candidates.insert(0, selected)
    candidates = _deduplicate_candidates(candidates)
    if len(candidates) <= 1:
        return selected

    probe_results = await _probe_candidates(downloader, candidates)
    successful_results = [result for result in probe_results if result.get("ok")]
    if not successful_results:
        logger.info(
            "cdn_probe.all_failed count=%s fallback_host=%s",
            len(candidates),
            host(candidates[0][0]),
        )
        return candidates[0]

    best = max(successful_results, key=lambda item: item.get("score", 0))
    logger.info(
        "cdn_probe.selected host=%s bytes=%s duration_ms=%s speed_kbps=%s candidates=%s",
        host(best["url"]),
        best.get("bytes", 0),
        best.get("duration_ms", 0),
        speed_kbps(best.get("bytes", 0), best.get("duration_ms", 1)),
        len(candidates),
    )
    return best["url"], best["headers"]


async def _probe_candidates(downloader: BaseDownloader, candidates: list) -> list:
    session = await downloader.api_client.get_session()
    tasks = [_probe_candidate(downloader, session, candidate) for candidate in candidates]
    return await asyncio.gather(*tasks, return_exceptions=False)


async def _probe_candidate(downloader: BaseDownloader, session, candidate: tuple) -> Dict[str, Any]:
    url, headers = candidate
    probe_headers = dict(headers or {})
    probe_headers["Range"] = f"bytes=0-{_ANDROID_CDN_PROBE_BYTES - 1}"
    started_at = time.perf_counter()
    bytes_read = 0
    status = 0
    try:
        import aiohttp

        async with session.get(
            url,
            headers=probe_headers,
            proxy=getattr(downloader.api_client, "proxy", None),
            timeout=aiohttp.ClientTimeout(
                total=_ANDROID_CDN_PROBE_TOTAL_TIMEOUT_SECONDS,
                sock_connect=_ANDROID_CDN_PROBE_CONNECT_TIMEOUT_SECONDS,
                sock_read=_ANDROID_CDN_PROBE_READ_TIMEOUT_SECONDS,
            ),
        ) as response:
            status = int(response.status or 0)
            if status not in {200, 206}:
                return _cdn_probe_result(url, headers, status, bytes_read, started_at)
            async for chunk in response.content.iter_chunked(64 * 1024):
                bytes_read += len(chunk)
                if bytes_read >= _ANDROID_CDN_PROBE_BYTES:
                    break
        result = _cdn_probe_result(url, headers, status, bytes_read, started_at)
        logger.info(
            "cdn_probe.result host=%s status=%s bytes=%s duration_ms=%s speed_kbps=%s",
            host(url),
            status,
            bytes_read,
            result["duration_ms"],
            speed_kbps(bytes_read, result["duration_ms"]),
        )
        return result
    except Exception as exc:
        result = _cdn_probe_result(url, headers, status, bytes_read, started_at, type(exc).__name__)
        logger.info(
            "cdn_probe.failed host=%s status=%s duration_ms=%s error=%s",
            host(url),
            status,
            result["duration_ms"],
            result["error"],
        )
        return result


def _cdn_probe_result(
    url: str,
    headers: Dict[str, str],
    status: int,
    bytes_read: int,
    started_at: float,
    error: str = "",
) -> Dict[str, Any]:
    duration_ms = max(1, elapsed_ms(started_at))
    return {
        "url": url,
        "headers": headers,
        "status": status,
        "bytes": int(bytes_read or 0),
        "duration_ms": duration_ms,
        "ok": status in {200, 206} and bytes_read > 0,
        "score": (bytes_read or 0) / max(duration_ms / 1000, 0.001),
        "error": error,
    }


def _report_file_progress(
    reporter,
    written: int,
    expected_size: Optional[int],
    started_at: float,
    *,
    force: bool = False,
) -> None:
    if reporter is None:
        return
    progress = getattr(reporter, "on_file_progress", None)
    if not callable(progress):
        return
    progress(
        int(written or 0),
        int(expected_size or 0),
        speed_bytes_per_second(written, started_at),
        force=force,
    )


def _detect_media_type_for_log(aweme_data: Dict[str, Any]) -> str:
    if (
        aweme_data.get("image_post_info")
        or aweme_data.get("images")
        or aweme_data.get("image_list")
    ):
        return "gallery"
    return "video"
