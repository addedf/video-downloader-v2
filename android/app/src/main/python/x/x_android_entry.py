"""X Android 桥接：帖子/主页解析、预览快照下载和原子落盘。"""
import asyncio
import json
import re
import time
from pathlib import Path
from urllib.parse import urlparse
import httpx
from common.android_utils import build_error_response
from .link_normalizer import normalize
from .media_extractor import media_url
from .x_service import XService, friendly_error

_runtime = {'output_root': None}
_service = None


def warm_up(app_data_dir, output_dir, cookie_header):
    global _service
    try:
        root = Path(output_dir) / 'X'
        root.mkdir(parents=True, exist_ok=True)
        _runtime['output_root'] = root
        if _service:
            _service.close()
        _service = XService()
        return json.dumps({'ok': True})
    except Exception:
        return json.dumps({'ok': False, 'error': 'X 初始化失败'}, ensure_ascii=False)


def refresh_cookies(cookie_header):
    # 公开内容通道不读取或转发用户 Cookie。
    return json.dumps({'ok': True})


def _resolve_sync(input_text, cursor=None):
    if _service:
        return _service.resolve(input_text, cursor)
    service = XService()
    try:
        return service.resolve(input_text, cursor)
    finally:
        service.close()


def resolve(input_text, cursor=None):
    try:
        result = _resolve_sync(input_text, cursor)
    except Exception as exc:
        message = friendly_error(exc)
        result = {'schema_version': 2, 'ok': False, 'message': message, 'error': message,
                  'source': {'platform': 'x', 'input_url': input_text}, 'work': None}
    return json.dumps(result, ensure_ascii=False)


def _download_resources(input_text, request_json):
    if not request_json:
        resolved = _resolve_sync(input_text)
        groups = resolved['work']['resources']
        return groups['videos'] + groups['images']
    try:
        request = json.loads(request_json)
        source = request['source']
        snapshot = request['snapshot']
        selection = request['selection']
        normalized = normalize(input_text)
        source_link = normalize(source['url'])
        source_id = (f"profile-{source_link['handle'].lower()}" if source_link['kind'] == 'profile'
                     else source_link['tweet_id'])
        if (request.get('schema_version') != 2 or source['platform'] != 'x'
                or source['id'] != source_id or snapshot['source_id'] != source_id
                or (not normalized['needs_redirect'] and normalized['url'].lower() != source_link['url'].lower())):
            raise ValueError('预览与当前链接不一致，请重新解析')
        kind = selection['resource_type']
        if kind not in ('all', 'video', 'image'):
            raise ValueError('不支持的 X 媒体选择')
        resource_ids = selection.get('resource_ids')
        if resource_ids is not None and (
                not isinstance(resource_ids, list)
                or not all(isinstance(value, str) and value.strip() for value in resource_ids)):
            raise ValueError('resource_ids 必须是非空字符串数组')
        wanted = set(resource_ids) if resource_ids is not None else None
        resources = []
        seen = set()
        for resource in snapshot['resources']:
            if kind != 'all' and resource.get('type') != kind:
                continue
            if wanted is not None and resource.get('id') not in wanted:
                continue
            resource = dict(resource)
            if not re.fullmatch(r'\d{2,20}-(?:video|image)-\d+', resource.get('id', '')):
                raise ValueError('媒体标识无效，请重新解析')
            resource['download_urls'] = [u for u in resource.get('download_urls', []) if media_url(u)]
            if not resource['download_urls'] or resource.get('format_hint') not in ('mp4', 'jpg', 'jpeg', 'png', 'webp', 'gif'):
                raise ValueError('媒体下载地址无效，请重新解析')
            if resource['id'] not in seen:
                seen.add(resource['id'])
                resources.append(resource)
        if wanted is not None and wanted - seen:
            raise ValueError('部分所选资源已失效，请重新解析')
        return resources
    except (KeyError, TypeError, json.JSONDecodeError) as exc:
        raise ValueError('下载快照无效，请重新解析') from exc


def download(input_text, *args):
    request_json = next((arg for arg in args if isinstance(arg, str)), None)
    callback = next((arg for arg in args if arg is not None and not isinstance(arg, str)), None)
    try:
        result = asyncio.run(_download_async(input_text, request_json, callback))
    except Exception as exc:
        result = build_error_response(friendly_error(exc), output_root=_runtime['output_root'])
    return json.dumps(result, ensure_ascii=False)


async def _download_async(input_text, request_json, callback):
    root = _runtime['output_root']
    if root is None:
        raise ValueError('X 下载尚未初始化，请重启应用后重试')
    resources = _download_resources(input_text, request_json)
    if not resources:
        raise ValueError('当前选择没有可下载媒体')
    root.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    files, metrics, failed = [], [], 0
    last_error = ''
    async with httpx.AsyncClient(follow_redirects=False, timeout=30) as http:
        for index, resource in enumerate(resources):
            target = root / f"x_{resource['id']}.{resource['format_hint']}"
            def progress(percent, downloaded, total, speed):
                if callback is not None:
                    try:
                        callback.onProgress((index * 100 + percent) // len(resources), downloaded, total, speed)
                    except Exception:
                        pass
            try:
                metric = await _download_one(http, resource['download_urls'][0], target, progress)
                files.append(str(target))
                metrics.append(metric)
            except (httpx.HTTPError, OSError, ValueError) as exc:
                failed += 1
                last_error = friendly_error(exc)
    message = f'已下载 {len(files)} 个文件，失败 {failed} 个'
    return {'ok': failed == 0 and bool(files), 'message': message,
            'error': f'{message}；{last_error}' if failed else '', 'files': files,
            'output_dir': str(root), 'success': len(files), 'failed': failed, 'skipped': 0,
            'timings': {'total_ms': int((time.monotonic() - started) * 1000)},
            'download_metrics': metrics, 'api_metrics': []}


async def _download_one(http, url, target, progress=None):
    import aiofiles
    if not media_url(url):
        raise ValueError('不支持的媒体下载地址')
    started = time.monotonic()
    temp = target.with_suffix(target.suffix + '.part')
    downloaded = 0
    try:
        async with http.stream('GET', url, headers={'accept-encoding': 'identity'}) as response:
            response.raise_for_status()
            content_type = response.headers.get('content-type', '').split(';')[0]
            if not content_type.startswith(('video/', 'image/', 'application/octet-stream')):
                raise ValueError('媒体服务器返回了非媒体内容，请重新解析')
            total = int(response.headers.get('content-length') or 0)
            async with aiofiles.open(temp, 'wb') as fp:
                async for chunk in response.aiter_bytes(64 * 1024):
                    await fp.write(chunk)
                    downloaded += len(chunk)
                    if progress:
                        speed = int(downloaded / max(time.monotonic() - started, 0.001))
                        progress(min(99, downloaded * 100 // total) if total else 0, downloaded, total, speed)
            if not downloaded or (total and total != downloaded):
                raise ValueError('媒体下载不完整，请重试')
        temp.replace(target)
        if progress:
            progress(100, downloaded, downloaded, 0)
    finally:
        temp.unlink(missing_ok=True)
    duration = max(1, int((time.monotonic() - started) * 1000))
    return {'ok': True, 'host': urlparse(url).hostname, 'final_host': urlparse(url).hostname,
            'bytes': downloaded, 'duration_ms': duration, 'speed_kbps': downloaded * 1000 // duration // 1024}
