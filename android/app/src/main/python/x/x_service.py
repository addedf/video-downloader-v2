"""公开帖直连优先，FxTwitter v2 兜底与主页媒体分页。"""
import time
from urllib.parse import urljoin
import httpx
from .link_normalizer import normalize
from .media_extractor import extract_resources, from_fx, work_for
from .x_client import XGuestClient

FX_ROOT = 'https://api.fxtwitter.com/2'
# 每轮自动翻页；大主页保留游标，避免无限占用 UI 或触发限流。
MAX_PAGES = 10
SCAN_SECONDS = 30
PAGE_SIZE = 20


def friendly_error(exc):
    if isinstance(exc, httpx.HTTPStatusError):
        code = exc.response.status_code
        if code == 429:
            return 'X 服务暂时限流，请稍后继续提取'
        if code in (401, 403):
            return '内容不可公开访问，可能需要登录或账号已受保护'
        if code == 404:
            return '账号或帖子不存在，或没有可公开访问的媒体'
        return f'X 解析服务暂不可用（HTTP {code}），请稍后重试'
    if isinstance(exc, httpx.RequestError):
        return '无法连接 X 解析服务，请检查网络后重试'
    return str(exc) if isinstance(exc, ValueError) else 'X 解析失败，请稍后重试'


class XService:
    def __init__(self, http=None, guest=None):
        self.http = http or httpx.Client(timeout=15, follow_redirects=False,
                                        headers={'user-agent': 'VideoDownloaderV2/2.4.3'})
        self.guest = guest or XGuestClient(timeout=8)

    def close(self):
        self.http.close()
        self.guest.close()

    def normalize(self, text):
        target = normalize(text)
        for _ in range(5):
            if not target['needs_redirect']:
                return target
            response = self.http.get(target['url'])
            if response.status_code not in (301, 302, 303, 307, 308):
                raise ValueError('t.co 短链未返回有效跳转，请粘贴原始 X 链接')
            location = response.headers.get('location')
            if not location:
                raise ValueError('t.co 短链缺少跳转目标')
            target = normalize(urljoin(target['url'], location))
        raise ValueError('t.co 短链跳转次数过多')

    def _fx(self, path, params=None):
        response = self.http.get(FX_ROOT + path, params=params)
        response.raise_for_status()
        try:
            payload = response.json()
        except ValueError as exc:
            raise ValueError('X 解析服务返回异常内容，请稍后重试') from exc
        if payload.get('code') != 200:
            raise ValueError('X 解析服务未返回有效内容，请稍后重试')
        return payload

    def resolve(self, text, cursor=None):
        target = self.normalize(text)
        if target['kind'] == 'profile':
            return self._profile(text, target, cursor)
        if cursor:
            raise ValueError('只有博主主页支持继续提取')
        tweet_id = target['tweet_id']
        channel = 'x_guest'
        try:
            extracted = extract_resources(self.guest.tweet_result(tweet_id))
            if not any(extracted['resources'].values()):
                raise ValueError('直连接口未返回媒体')
        except (httpx.HTTPError, ValueError, KeyError, RuntimeError):
            channel = 'fxtwitter'
            payload = self._fx(f'/status/{tweet_id}')
            post = payload.get('status') or {}
            if post.get('type') != 'status':
                raise ValueError('帖子不存在或不可公开访问')
            extracted = extract_resources(from_fx(post))
        groups = extracted['resources']
        if not any(groups.values()):
            raise ValueError('此帖没有可下载的图片、视频或 GIF')
        result = self._result(text, target, tweet_id, groups,
                              extracted['text'][:100] or f'X 帖子 {tweet_id}', extracted['author'])
        result['diagnostics'] = {'channel': channel}
        return result

    def _profile(self, text, target, cursor):
        handle = target['handle']
        groups = {'videos': [], 'images': [], 'covers': [], 'audios': []}
        seen_media, seen_cursors = set(), set()
        current = cursor or None
        pages, post_count, idle_pages = 0, 0, 0
        started = time.monotonic()
        complete, warning = False, ''
        while pages < MAX_PAGES and time.monotonic() - started < SCAN_SECONDS:
            params = {'count': PAGE_SIZE}
            if current:
                params['cursor'] = current
            try:
                payload = self._fx(f'/profile/{handle}/media', params)
                if not isinstance(payload.get('results'), list) or not isinstance(payload.get('cursor'), dict):
                    raise ValueError('主页接口响应不完整，请稍后重试')
            except (httpx.HTTPError, ValueError) as exc:
                if not pages:
                    raise
                warning = friendly_error(exc)
                break
            pages += 1
            previous_count = len(seen_media)
            for post in payload['results']:
                author = post.get('author') or {}
                if post.get('type') != 'status' or author.get('screen_name', '').lower() != handle.lower():
                    continue
                extracted = extract_resources(from_fx(post))
                added = False
                for kind in ('videos', 'images'):
                    for resource in extracted['resources'][kind]:
                        key = resource['download_urls'][0].split('?', 1)[0]
                        if key not in seen_media:
                            seen_media.add(key)
                            groups[kind].append(resource)
                            added = True
                post_count += int(added)
            next_cursor = payload['cursor'].get('bottom')
            if not next_cursor:
                complete = True
                current = None
                break
            if next_cursor == current or next_cursor in seen_cursors:
                warning = '服务返回重复分页游标，已停止提取；当前结果可能不完整'
                current = None
                break
            seen_cursors.add(next_cursor)
            current = next_cursor
            idle_pages = idle_pages + 1 if len(seen_media) == previous_count else 0
            if idle_pages >= 3:
                warning = '连续多页没有新增媒体，已暂停；不能确认已取全，可稍后继续提取'
                break
            # 空页可能仍有有效游标，不据此声称已取全。
            if pages < MAX_PAGES:
                time.sleep(0.25)
        if not any(groups.values()) and not current and not cursor:
            raise ValueError(warning or '未找到该博主公开发布的可下载媒体')
        message = warning or ('已提取接口当前可访问的全部媒体（不含转推及引用内容）' if complete
                              else '已自动提取一批媒体，可继续提取更早内容；尚未取全')
        result = self._result(text, target, f'profile-{handle.lower()}', groups,
                              f'@{handle} 的媒体', {'id': '', 'name': f'@{handle}'})
        result['message'] = message
        result['collection'] = {'next_cursor': current, 'complete': complete,
                                'pages': pages, 'posts': post_count}
        result['diagnostics'] = {'channel': 'fxtwitter', 'response_summary': message}
        return result

    @staticmethod
    def _result(text, target, source_id, groups, title, author):
        return {'schema_version': 2, 'ok': True, 'message': '', 'error': '',
                'source': {'platform': 'x', 'input_url': text, 'resolved_url': target['url'], 'id': source_id},
                'work': work_for(groups, title, author)}
