"""Download a validated multi-work preview snapshot without resolving the profile again."""
from __future__ import annotations

import hashlib
import time
from pathlib import Path
from urllib.parse import urlparse, urljoin
import aiofiles
import httpx

_EXTENSIONS = {'video': {'mp4', 'mov'}, 'image': {'jpg', 'jpeg', 'png', 'webp', 'gif'}}


def _allowed_url(value, suffixes):
    if not isinstance(value, str):
        return False
    try:
        parsed = urlparse(value)
        host = (parsed.hostname or '').lower()
        return (parsed.scheme == 'https' and not parsed.username and not parsed.password
                and parsed.port in (None, 443)
                and any(host == suffix or host.endswith('.' + suffix) for suffix in suffixes))
    except ValueError:
        return False


def select_collection_resources(request, *, platform, expected_source_id, allowed_host_suffixes):
    """Caller derives expected_source_id from a validated profile URL, never from the snapshot."""
    try:
        source, snapshot, selection = request['source'], request['snapshot'], request['selection']
        if (request.get('schema_version') != 2 or source['platform'] != platform
                or not expected_source_id.startswith('profile-')
                or source['id'] != expected_source_id or snapshot['source_id'] != expected_source_id):
            raise ValueError('主页预览与当前链接不一致，请重新提取')
        kind = selection['resource_type']
        if kind not in ('all', 'video', 'image'):
            raise ValueError('主页仅支持保存图片、视频和实况媒体')
        ids = selection.get('resource_ids')
        if ids is not None and (not isinstance(ids, list) or any(not isinstance(i, str) or not i for i in ids)):
            raise ValueError('媒体选择无效，请重新选择')
        wanted = set(ids) if ids is not None else None
        selected, seen = [], set()
        for original in snapshot['resources']:
            if kind != 'all' and original.get('type') != kind:
                continue
            resource_id = original.get('id')
            if wanted is not None and resource_id not in wanted:
                continue
            if not isinstance(resource_id, str) or not resource_id or len(resource_id) > 256:
                raise ValueError('媒体标识无效，请重新提取')
            if resource_id in seen:
                continue
            resource_type = original.get('type')
            if resource_type not in _EXTENSIONS:
                continue
            resource = dict(original)
            urls = resource.get('download_urls')
            if not isinstance(urls, list):
                raise ValueError('媒体地址无效，请重新提取')
            urls = list(dict.fromkeys(u for u in urls if _allowed_url(u, allowed_host_suffixes)))
            ext = str(resource.get('format_hint') or ('mp4' if resource_type == 'video' else 'jpg')).lower()
            if not urls or ext not in _EXTENSIONS[resource_type]:
                raise ValueError('媒体地址或格式无效，请重新提取')
            resource.update(download_urls=urls, format_hint=ext)
            seen.add(resource_id)
            selected.append(resource)
            live = original.get('live_video') or {}
            if selection.get('include_live_video') and resource_type == 'image' and live.get('available'):
                live_urls = list(dict.fromkeys(u for u in live.get('download_urls', [])
                                               if _allowed_url(u, allowed_host_suffixes)))
                if not live_urls:
                    raise ValueError('所选实况视频地址无效，请重新提取')
                selected.append({'id': resource_id + '-live', 'type': 'video',
                                 'download_urls': live_urls, 'format_hint': 'mp4'})
        if wanted is not None and wanted - seen:
            raise ValueError('部分所选媒体已失效，请重新提取')
        if not selected:
            raise ValueError('请至少选择一个要保存的内容')
        return selected
    except (KeyError, TypeError, AttributeError) as exc:
        raise ValueError('主页下载快照无效，请重新提取') from exc


async def download_collection_snapshot(request, output_root, progress_callback, *, platform,
                                       expected_source_id, allowed_host_suffixes, headers=None):
    resources = select_collection_resources(request, platform=platform,
        expected_source_id=expected_source_id, allowed_host_suffixes=allowed_host_suffixes)
    root = Path(output_root)
    root.mkdir(parents=True, exist_ok=True)
    # Metadata APIs may need cookies. Media CDN requests must never receive them.
    safe_headers = {k: v for k, v in (headers or {}).items()
                    if k.lower() in ('user-agent', 'referer', 'origin', 'accept')}
    started = time.monotonic()
    files, metrics, failed = [], [], 0
    async with httpx.AsyncClient(timeout=30, follow_redirects=False, headers=safe_headers) as http:
        for index, resource in enumerate(resources):
            # Stable, bounded filenames independent of arbitrary IDs/paths returned by providers.
            key = hashlib.sha256(resource['id'].encode()).hexdigest()[:24]
            target = root / f'{platform}_{key}.{resource["format_hint"]}'
            def progress(percent, downloaded, total, speed):
                if progress_callback is not None:
                    try:
                        progress_callback.onProgress((index * 100 + percent) // len(resources),
                                                     downloaded, total, speed)
                    except Exception:
                        pass
            saved = False
            for url in resource['download_urls'][:8]:
                try:
                    metric = await _download_one(http, url, target, allowed_host_suffixes, progress)
                    files.append(str(target))
                    metrics.append(metric)
                    saved = True
                    break
                except (httpx.HTTPError, OSError, ValueError):
                    continue
            failed += int(not saved)
    message = f'已下载 {len(files)} 个文件，失败 {failed} 个'
    return {'ok': bool(files) and failed == 0, 'message': message,
            'error': message + '；可重新提取后重试失败媒体' if failed else '',
            'output_dir': str(root), 'files': files, 'success': len(files), 'failed': failed,
            'skipped': 0, 'timings': {'total_ms': int((time.monotonic() - started) * 1000)},
            'download_metrics': metrics, 'api_metrics': []}


async def _download_one(http, url, target, suffixes, progress):
    started = time.monotonic()
    temp = target.with_suffix(target.suffix + '.part')
    downloaded = 0
    try:
        for redirect in range(6):
            if not _allowed_url(url, suffixes):
                raise ValueError('不支持的媒体跳转地址')
            async with http.stream('GET', url, headers={'accept-encoding': 'identity'}) as response:
                if response.status_code in (301, 302, 303, 307, 308):
                    if redirect == 5 or not response.headers.get('location'):
                        raise ValueError('媒体跳转异常')
                    url = urljoin(url, response.headers['location'])
                    continue
                response.raise_for_status()
                mime = response.headers.get('content-type', '').split(';')[0]
                if not mime.startswith(('image/', 'video/', 'application/octet-stream')):
                    raise ValueError('服务器返回的不是媒体文件')
                total = int(response.headers.get('content-length') or 0)
                async with aiofiles.open(temp, 'wb') as fp:
                    async for chunk in response.aiter_bytes(64 * 1024):
                        await fp.write(chunk)
                        downloaded += len(chunk)
                        speed = int(downloaded / max(time.monotonic() - started, .001))
                        progress(min(99, downloaded * 100 // total) if total else 0, downloaded, total, speed)
                if not downloaded or (total and downloaded != total):
                    raise ValueError('下载文件不完整')
                break
        temp.replace(target)
        progress(100, downloaded, downloaded, 0)
    finally:
        temp.unlink(missing_ok=True)
    duration = max(1, int((time.monotonic() - started) * 1000))
    return {'ok': True, 'host': urlparse(url).hostname, 'final_host': urlparse(url).hostname,
            'bytes': downloaded, 'duration_ms': duration,
            'speed_kbps': downloaded * 1000 // duration // 1024}
