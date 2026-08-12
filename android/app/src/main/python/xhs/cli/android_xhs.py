from __future__ import annotations

import json
from asyncio import Event, Queue, gather
from datetime import datetime
from pathlib import Path
from re import DOTALL, compile
from time import perf_counter
from types import SimpleNamespace
from urllib.parse import urlparse

from httpx import HTTPError, Timeout
from yaml import safe_load

from common.android_flow_logger import AndroidFlowLogger, url_preview
from common.android_utils import elapsed_ms, host, speed_kbps
from .xhs_source_bootstrap import install_module_exports

install_module_exports()

from source.application.download import Download
from source.application.explore import Explore
from source.application.image import Image
from source.application.request import Html
from source.application.video import Video
from source.expansion import Cleaner, Converter, Namespace, beautify_string
from source.module import (
    DataRecorder,
    ERROR,
    IDRecorder,
    INFO,
    Manager,
    MapRecorder,
    Mapping,
    ROOT,
    WARNING,
    logging,
)
from source.module.tools import print as rich_print
from source.translation import _, switch_language

__all__ = ["AndroidXHS"]


class AndroidConverter(Converter):
    INITIAL_STATE_PREFIX = "window.__INITIAL_STATE__="
    NULL_CHAR = "\x00"
    BOM_CHARS = "\ufeff\ufffe"
    INITIAL_STATE = compile(
        r"window\.__INITIAL_STATE__\s*=\s*(.*?)(?:</script>|<script|\Z)",
        DOTALL,
    )

    def _extract_object(self, html: str) -> str:
        html = self._clean_text(html)
        if not html:
            return ""
        if match := self.INITIAL_STATE.search(html):
            return f"{self.INITIAL_STATE_PREFIX}{match.group(1).strip()}"
        try:
            return super()._extract_object(html)
        except Exception:
            return ""

    @classmethod
    def _convert_object(cls, text: str) -> dict:
        cleaned = cls._clean_text(text)
        if cleaned.startswith(cls.INITIAL_STATE_PREFIX):
            cleaned = cleaned.removeprefix(cls.INITIAL_STATE_PREFIX)
        cleaned = cls.YAML_ILLEGAL.sub("", cleaned).strip().removesuffix(";")
        if not cleaned:
            return {}
        try:
            return json.loads(cleaned)
        except json.JSONDecodeError:
            pass
        try:
            return safe_load(cleaned) or {}
        except Exception:
            return {}

    @staticmethod
    def _clean_text(value: str) -> str:
        return str(value or "").replace(
            AndroidConverter.NULL_CHAR,
            "",
        ).lstrip(AndroidConverter.BOM_CHARS)


class Print:
    def __init__(self, func=rich_print):
        self.func = func

    def __call__(self):
        return self.func


class AndroidHtml(Html):
    DEFAULT_SCHEME = "https://"
    MIN_REQUEST_ATTEMPTS = 1

    async def request_url(
            self,
            url: str,
            content=True,
            cookie: str = None,
            proxy: str = None,
            **kwargs,
    ) -> str:
        result = ""
        for attempt in self._request_attempts():
            result = await self._request_url_once(
                url,
                content,
                cookie,
                log_error=attempt >= self.retry,
                **kwargs,
            )
            if result:
                return result
        return result

    def _request_attempts(self) -> range:
        return range(max(self.MIN_REQUEST_ATTEMPTS, self.retry + 1))

    async def _request_url_once(
            self,
            url: str,
            content: bool,
            cookie: str | None,
            log_error: bool,
            **kwargs,
    ) -> str:
        url = self._normalize_url(url)
        try:
            response = await self.client.get(
                url,
                headers=self.update_cookie(cookie),
                **kwargs,
            )
            response.raise_for_status()
            return response.text if content else str(response.url)
        except HTTPError as error:
            if log_error:
                logging(self.print, f"request failed: {url} {error!r}", ERROR)
            return ""

    def _normalize_url(self, url: str) -> str:
        return url if url.startswith("http") else f"{self.DEFAULT_SCHEME}{url}"


def data_cache(function):
    async def inner(self, data: dict):
        if self.manager.record_data:
            download = data["下载地址"]
            lives = data["动图地址"]
            await function(self, data)
            data["下载地址"] = download
            data["动图地址"] = lives

    return inner


class AndroidXHS:
    LINK = compile(r"(?:https?://)?www\.xiaohongshu\.com/explore/\S+")
    USER = compile(r"(?:https?://)?www\.xiaohongshu\.com/user/profile/[a-z0-9]+/\S+")
    SHARE = compile(r"(?:https?://)?www\.xiaohongshu\.com/discovery/item/\S+")
    SHORT = compile(r"(?:https?://)?xhslink\.com/[^\s\"<>\\^`{|}，。；！？、【】《》]+")
    ID = compile(r"(?:explore|item)/(\S+)?\?")
    ID_USER = compile(r"user/profile/[a-z0-9]+/(\S+)?\?")
    CLEANER = Cleaner()
    SHORT_URL_CACHE_LIMIT = 64
    SHORT_URL_HEAD_TIMEOUT_SECONDS = 3.0
    DEFAULT_SCHEME = "https://"
    VIDEO_CDN_PROBE_BYTES = 256 * 1024
    VIDEO_CDN_PROBE_CONNECT_TIMEOUT_SECONDS = 2.0
    VIDEO_CDN_PROBE_READ_TIMEOUT_SECONDS = 4.0
    VIDEO_CDN_PROBE_TOTAL_TIMEOUT_SECONDS = 6.0
    VIDEO_CDN_PROBE_MAX_CANDIDATES = 4
    VIDEO_CDN_PROBE_SUCCESS_STATUS = {200, 206}

    def __init__(
            self,
            mapping_data: dict | None = None,
            work_path: str = "",
            folder_name: str = "XHS",
            name_format: str = "发布时间 作者昵称 作品标题",
            user_agent: str | None = None,
            cookie: str = "",
            proxy: str | dict | None = None,
            timeout: int = 10,
            chunk: int = 1024 * 1024,
            max_retry: int = 3,
            record_data: bool = False,
            image_format: str = "JPEG",
            image_download: bool = True,
            video_download: bool = True,
            live_download: bool = False,
            video_preference: str = "resolution",
            folder_mode: bool = False,
            download_record: bool = True,
            author_archive: bool = False,
            write_mtime: bool = False,
            language: str = "zh_CN",
            root: Path | None = None,
            flow: AndroidFlowLogger | None = None,
            progress_reporter=None,
            short_url_cache: dict[str, str] | None = None,
    ):
        switch_language(language)
        self.flow = flow
        self.short_url_cache = short_url_cache if short_url_cache is not None else {}
        self.print = Print()
        self.manager = Manager(
            root or ROOT,
            work_path,
            folder_name,
            name_format,
            chunk,
            user_agent,
            cookie,
            proxy,
            timeout,
            max_retry,
            record_data,
            image_format,
            image_download,
            video_download,
            live_download,
            video_preference,
            download_record,
            folder_mode,
            author_archive,
            write_mtime,
            False,
            self.CLEANER,
            self.print,
        )
        self.mapping_data = mapping_data or {}
        self.manager.progress_reporter = progress_reporter
        self.map_recorder = MapRecorder(self.manager)
        self.mapping = Mapping(self.manager, self.map_recorder)
        self.html = AndroidHtml(self.manager)
        self.image = Image()
        self.video = Video()
        self.explore = Explore()
        self.convert = AndroidConverter()
        self.download = Download(self.manager)
        self.id_recorder = IDRecorder(self.manager)
        self.data_recorder = DataRecorder(self.manager)
        self.queue = Queue()
        self.event = Event()

    def __extract_image(self, container: dict, data: Namespace):
        container["下载地址"], container["动图地址"] = self.image.get_image_link(
            data, self.manager.image_format
        )

    def __extract_video(self, container: dict, data: Namespace):
        container["下载地址"] = self.video.deal_video_link(
            data,
            self.manager.video_preference,
        )
        container["动图地址"] = [None]

    async def __download_files(
            self,
            container: dict,
            download: bool,
            index,
            count: SimpleNamespace,
    ):
        name = self.__naming_rules(container)
        work_id = container["作品ID"]
        work_type = container["作品类型"]
        if (urls := container["下载地址"]) and download:
            if self.flow is not None:
                self.flow.info(
                    "download_files.ready",
                    work_id=work_id,
                    work_type=work_type,
                    urls=len(urls),
                    lives=len(container["动图地址"]),
                )
            with self._stage("download_files", work_id=work_id, work_type=work_type, urls=len(urls)):
                downloaded_files, result = await self.download.run(
                    urls,
                    container["动图地址"],
                    index,
                    container["作者ID"] + "_" + self.CLEANER.filter_name(container["作者昵称"]),
                    name,
                    work_type,
                    container["时间戳"],
                )
            if self.flow is not None:
                self.flow.info(
                    "download_files.result",
                    work_id=work_id,
                    path=downloaded_files,
                    total=len(result),
                    success=sum(1 for item in result if item),
                    failed=sum(1 for item in result if not item),
                )
            if not result:
                count.skip += 1
            elif all(result):
                count.success += 1
                await self.__add_record(work_id)
            else:
                count.fail += 1
        elif not urls:
            self.logging(_("提取作品文件下载地址失败"), ERROR)
            count.fail += 1
        await self.save_data(container)

    @data_cache
    async def save_data(self, data: dict):
        data["采集时间"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        data["下载地址"] = " ".join(data["下载地址"])
        data["动图地址"] = " ".join(i or "NaN" for i in data["动图地址"])
        data.pop("时间戳", None)
        await self.data_recorder.add(**data)

    async def __add_record(self, id_: str) -> None:
        await self.id_recorder.add(id_)

    async def extract(
            self,
            url: str,
            download: bool = False,
            index: list | tuple | None = None,
            data: bool = True,
    ) -> list[dict]:
        with self._stage("extract_links", input=url_preview(url)):
            urls = await self.extract_links(url)
        if not urls:
            self.logging(_("提取小红书作品链接失败"), WARNING)
            if self.flow is not None:
                self.flow.warning("extract_links.empty", input=url_preview(url))
            return []
        if self.flow is not None:
            self.flow.info("extract_links.result", count=len(urls), urls=[url_preview(i) for i in urls])
        statistics = SimpleNamespace(all=len(urls), success=0, fail=0, skip=0)
        items = []
        for item in urls:
            with self._stage("deal_extract", url=url_preview(item)):
                items.append(await self.__deal_extract(item, download, index, data, count=statistics))
        if self.flow is not None:
            self.flow.info(
                "extract.done",
                total=statistics.all,
                success=statistics.success,
                failed=statistics.fail,
                skipped=statistics.skip,
            )
        return items

    async def extract_links(self, url: str) -> list[str]:
        items = str(url or "").split()
        urls = self._extract_direct_links(items)
        if urls:
            if self.flow is not None:
                self.flow.info("extract_links.direct_hit", count=len(urls))
            return urls

        urls = []
        for item in items:
            if short := self.SHORT.search(item):
                short_url = short.group()
                item = await self._resolve_short_url(short_url)
            if share := self.SHARE.search(item):
                urls.append(share.group())
            elif link := self.LINK.search(item):
                urls.append(link.group())
            elif user := self.USER.search(item):
                urls.append(user.group())
        return urls

    def _extract_direct_links(self, items: list[str]) -> list[str]:
        urls = []
        for item in items:
            if share := self.SHARE.search(item):
                urls.append(share.group())
            elif link := self.LINK.search(item):
                urls.append(link.group())
            elif user := self.USER.search(item):
                urls.append(user.group())
        return urls

    async def _resolve_short_url(self, short_url: str) -> str:
        if cached := self.short_url_cache.get(short_url):
            if self.flow is not None:
                self.flow.info(
                    "short_url.cache_hit",
                    url=url_preview(short_url),
                    resolved=url_preview(cached),
                )
            return cached

        if self.flow is not None:
            self.flow.info("short_url.detected", url=url_preview(short_url))
        with self._stage("resolve_short_url", url=url_preview(short_url)):
            resolved = await self._resolve_short_url_by_head(short_url)
            if not resolved:
                resolved = await self.html.request_url(short_url, False)

        if resolved:
            if len(self.short_url_cache) >= self.SHORT_URL_CACHE_LIMIT:
                self.short_url_cache.clear()
            self.short_url_cache[short_url] = resolved
        if self.flow is not None:
            self.flow.info("short_url.resolved", resolved=url_preview(resolved))
        return resolved

    async def _resolve_short_url_by_head(self, short_url: str) -> str:
        request_url = short_url if short_url.startswith("http") else f"{self.DEFAULT_SCHEME}{short_url}"
        try:
            response = await self.html.client.head(
                request_url,
                headers=self.html.update_cookie(),
                timeout=self.SHORT_URL_HEAD_TIMEOUT_SECONDS,
            )
            response.raise_for_status()
            resolved = str(response.url)
            if self.SHORT.search(resolved):
                return ""
            if self.flow is not None:
                self.flow.info("short_url.head_resolved", resolved=url_preview(resolved))
            return resolved
        except Exception as exc:
            if self.flow is not None:
                self.flow.info("short_url.head_fallback", error=repr(exc), url=url_preview(short_url))
            return ""

    async def _get_html_data(
            self,
            url: str,
            data: bool,
            cookie: str | None = None,
            proxy: str | None = None,
            count=SimpleNamespace(all=0, success=0, fail=0, skip=0),
    ) -> tuple[str, Namespace | dict]:
        if await self.skip_download(id_ := self.__extract_link_id(url)) and not data:
            count.skip += 1
            return id_, {"message": _("作品 {0} 存在下载记录，跳过处理").format(id_)}
        with self._stage("request_note_html", work_id=id_, url=url_preview(url)):
            html = await self.html.request_url(url, cookie=cookie, proxy=proxy)
        if self.flow is not None:
            self.flow.info("request_note_html.result", work_id=id_, bytes=len(str(html or "")))
        with self._stage("parse_note_data", work_id=id_):
            namespace = self.__generate_data_object(html)
        if not namespace:
            self.logging(_("{0} 获取数据失败").format(id_), ERROR)
            count.fail += 1
            return id_, {}
        return id_, namespace

    def _extract_data(self, namespace: Namespace, id_: str, count):
        with self._stage("extract_note_fields", work_id=id_):
            data = self.explore.run(namespace)
        if not data:
            self.logging(_("{0} 提取数据失败").format(id_), ERROR)
            count.fail += 1
            return {}
        if self.flow is not None:
            self.flow.info(
                "extract_note_fields.result",
                work_id=id_,
                work_type=data.get("作品类型"),
                title=url_preview(data.get("作品标题", "")),
            )
        return data

    async def _deal_download_tasks(
            self,
            data: dict,
            namespace: Namespace,
            id_: str,
            download: bool,
            index: list | tuple | None,
            count: SimpleNamespace,
    ):
        if self.flow is not None:
            self.flow.info(
                "download_tasks.prepare",
                work_id=id_,
                work_type=data.get("作品类型"),
            )
        if data["作品类型"] == _("视频"):
            with self._stage("extract_video_urls", work_id=id_):
                self.__extract_video(data, namespace)
            await self._optimize_video_download_url(data, namespace, id_)
        elif data["作品类型"] in {_("图文"), _("图集")}:
            with self._stage("extract_image_urls", work_id=id_):
                self.__extract_image(data, namespace)
        else:
            self.logging(_("未知的作品类型：{0}").format(id_), WARNING)
            data["下载地址"] = []
            data["动图地址"] = []
        if self.flow is not None:
            self.flow.info(
                "download_tasks.urls",
                work_id=id_,
                media_urls=len(data.get("下载地址", [])),
                live_urls=len([i for i in data.get("动图地址", []) if i]),
            )
        await self.update_author_nickname(data)
        await self.__download_files(data, download, index, count)
        return data

    async def __deal_extract(
            self,
            url: str,
            download: bool,
            index: list | tuple | None,
            data: bool,
            cookie: str | None = None,
            proxy: str | None = None,
            count=SimpleNamespace(all=0, success=0, fail=0, skip=0),
    ):
        id_, namespace = await self._get_html_data(url, data, cookie, proxy, count)
        if not isinstance(namespace, Namespace):
            return namespace
        if not (data := self._extract_data(namespace, id_, count)):
            return data
        data = await self._deal_download_tasks(
            data | {"作品链接": url},
            namespace,
            id_,
            download,
            index,
            count,
        )
        self.logging(_("作品处理完成：{0}").format(id_))
        return data

    async def update_author_nickname(self, container: dict):
        if a := self.CLEANER.filter_name(self.mapping_data.get(i := container["作者ID"], "")):
            container["作者昵称"] = a
        else:
            container["作者昵称"] = self.manager.filter_name(container["作者昵称"]) or i
        await self.mapping.update_cache(i, container["作者昵称"])

    @staticmethod
    def __extract_link_id(url: str) -> str:
        link = urlparse(url)
        return link.path.split("/")[-1]

    def __generate_data_object(self, html: str) -> Namespace:
        return Namespace(self.convert.run(html))

    async def _optimize_video_download_url(
            self,
            container: dict,
            namespace: Namespace,
            work_id: str,
    ) -> None:
        urls = container.get("下载地址") or []
        candidates = self._collect_video_cdn_candidates(namespace, urls)
        if len(candidates) <= 1:
            return
        selected = await self._select_fastest_video_cdn(candidates, work_id)
        if selected:
            container["下载地址"] = [selected]

    def _collect_video_cdn_candidates(
            self,
            namespace: Namespace,
            default_urls: list[str],
    ) -> list[str]:
        candidates = []
        candidates.extend(default_urls)
        candidates.extend(self.video.generate_video_link(namespace))

        if stream := self._select_preferred_video_stream(namespace):
            candidates.append(getattr(stream, "masterUrl", ""))
            backup_urls = getattr(stream, "backupUrls", []) or []
            if isinstance(backup_urls, str):
                candidates.append(backup_urls)
            else:
                candidates.extend(backup_urls)

        return self._deduplicate_video_urls(
            self._format_video_url(candidate) for candidate in candidates
        )[:self.VIDEO_CDN_PROBE_MAX_CANDIDATES]

    def _select_preferred_video_stream(self, namespace: Namespace):
        items = list(self.video.get_video_items(namespace))
        if not items:
            return None
        match self.manager.video_preference:
            case "resolution":
                items.sort(key=lambda item: getattr(item, "height", 0) or 0)
            case "bitrate":
                items.sort(key=lambda item: getattr(item, "videoBitrate", 0) or 0)
            case "size":
                items.sort(key=lambda item: getattr(item, "size", 0) or 0)
        return items[-1]

    @staticmethod
    def _format_video_url(url: str) -> str:
        if not isinstance(url, str) or not url:
            return ""
        return Html.format_url(url)

    @staticmethod
    def _deduplicate_video_urls(urls) -> list[str]:
        seen = set()
        result = []
        for url in urls:
            if not url or url in seen:
                continue
            seen.add(url)
            result.append(url)
        return result

    async def _select_fastest_video_cdn(
            self,
            candidates: list[str],
            work_id: str,
    ) -> str:
        results = await gather(
            *(self._probe_video_cdn_candidate(url, work_id) for url in candidates)
        )
        available = [
            result for result in results
            if result["status"] in self.VIDEO_CDN_PROBE_SUCCESS_STATUS
            and result["bytes"] > 0
        ]
        if not available:
            self._log_video_cdn_probe(
                "xhs_cdn_probe.all_failed",
                work_id=work_id,
                candidates=len(candidates),
                fallback_host=host(candidates[0]),
            )
            return candidates[0]

        selected = max(available, key=lambda item: item["speed_kbps"])
        self._log_video_cdn_probe(
            "xhs_cdn_probe.selected",
            work_id=work_id,
            host=host(selected["url"]),
            bytes=selected["bytes"],
            duration_ms=selected["duration_ms"],
            speed_kbps=selected["speed_kbps"],
            candidates=len(candidates),
        )
        return selected["url"]

    async def _probe_video_cdn_candidate(
            self,
            url: str,
            work_id: str,
    ) -> dict:
        started_at = perf_counter()
        status = 0
        bytes_read = 0
        headers = self.manager.blank_headers.copy()
        headers["Range"] = f"bytes=0-{self.VIDEO_CDN_PROBE_BYTES - 1}"
        try:
            async with self.manager.download_client.stream(
                    "GET",
                    url,
                    headers=headers,
                    timeout=Timeout(
                        self.VIDEO_CDN_PROBE_TOTAL_TIMEOUT_SECONDS,
                        connect=self.VIDEO_CDN_PROBE_CONNECT_TIMEOUT_SECONDS,
                        read=self.VIDEO_CDN_PROBE_READ_TIMEOUT_SECONDS,
                    ),
            ) as response:
                status = response.status_code
                if status not in self.VIDEO_CDN_PROBE_SUCCESS_STATUS:
                    result = self._video_cdn_probe_result(url, status, bytes_read, started_at)
                    self._log_video_cdn_probe_result("xhs_cdn_probe.result", result, work_id)
                    return result
                async for chunk in response.aiter_bytes():
                    bytes_read += len(chunk)
                    if bytes_read >= self.VIDEO_CDN_PROBE_BYTES:
                        break
            result = self._video_cdn_probe_result(url, status, bytes_read, started_at)
            self._log_video_cdn_probe_result("xhs_cdn_probe.result", result, work_id)
            return result
        except Exception as exc:
            result = self._video_cdn_probe_result(
                url,
                status,
                bytes_read,
                started_at,
                type(exc).__name__,
            )
            self._log_video_cdn_probe_result("xhs_cdn_probe.failed", result, work_id)
            return result

    @staticmethod
    def _video_cdn_probe_result(
            url: str,
            status: int,
            bytes_read: int,
            started_at: float,
            error: str = "",
    ) -> dict:
        duration_ms = max(1, elapsed_ms(started_at))
        return {
            "url": url,
            "status": status,
            "bytes": bytes_read,
            "duration_ms": duration_ms,
            "speed_kbps": speed_kbps(bytes_read, duration_ms),
            "error": error,
        }

    def _log_video_cdn_probe_result(
            self,
            event: str,
            result: dict,
            work_id: str,
    ) -> None:
        self._log_video_cdn_probe(
            event,
            work_id=work_id,
            host=host(result["url"]),
            status=result["status"],
            bytes=result["bytes"],
            duration_ms=result["duration_ms"],
            speed_kbps=result["speed_kbps"],
            error=result["error"],
        )

    def _log_video_cdn_probe(self, event: str, **fields) -> None:
        if self.flow is not None:
            self.flow.info(event, **fields)

    def __naming_rules(self, data: dict) -> str:
        keys = self.manager.name_format.split()
        values = []
        for key in keys:
            match key:
                case "发布时间":
                    values.append(self.__get_name_time(data))
                case "作品标题":
                    values.append(self.__get_name_title(data))
                case _:
                    values.append(data[key])
        return beautify_string(
            self.CLEANER.filter_name(
                self.manager.SEPARATE.join(values),
                default=self.manager.SEPARATE.join((data["作者ID"], data["作品ID"])),
            ),
            length=128,
        )

    @staticmethod
    def __get_name_time(data: dict) -> str:
        return data["发布时间"].replace(":", ".")

    def __get_name_title(self, data: dict) -> str:
        return beautify_string(
            self.manager.filter_name(data["作品标题"]),
            64,
        ) or data["作品ID"]

    async def skip_download(self, id_: str) -> bool:
        if self.flow is not None:
            self.flow.info("skip_download.disabled", work_id=id_)
        return False

    def _stage(self, name: str, **fields):
        if self.flow is None:
            return _NullStage()
        return self.flow.stage(name, **fields)

    async def __aenter__(self):
        await self.id_recorder.__aenter__()
        await self.data_recorder.__aenter__()
        await self.map_recorder.__aenter__()
        return self

    async def __aexit__(self, exc_type, exc_value, traceback):
        await self.id_recorder.__aexit__(exc_type, exc_value, traceback)
        await self.data_recorder.__aexit__(exc_type, exc_value, traceback)
        await self.map_recorder.__aexit__(exc_type, exc_value, traceback)
        await self.close()

    async def close(self):
        await self.manager.close()

    def logging(self, text, style=INFO):
        logging(self.print, text, style)


class _NullStage:
    def __enter__(self):
        return None

    def __exit__(self, exc_type, exc_value, traceback):
        return False
