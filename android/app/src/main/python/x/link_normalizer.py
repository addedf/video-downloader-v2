"""严格识别 X 帖子、主页和短链；网络重定向由服务层处理。"""
import re
from urllib.parse import urlparse

X_HOSTS = {
    'x.com', 'www.x.com', 'mobile.x.com', 'twitter.com', 'www.twitter.com',
    'mobile.twitter.com', 'vxtwitter.com', 'www.vxtwitter.com',
    'fixupx.com', 'www.fixupx.com', 'fxtwitter.com', 'www.fxtwitter.com',
}
SHORT_HOSTS = {'t.co', 'www.t.co'}
_RESERVED = {'home', 'explore', 'search', 'settings', 'notifications', 'messages',
             'i', 'intent', 'share', 'login', 'logout', 'signup', 'compose', 'tos', 'privacy'}
_URL_RE = re.compile(r'https?://[^\s\"\'<>，。；）)】]+')
_STATUS_RE = re.compile(r'^/([A-Za-z0-9_]{1,15}|i/web)/status(?:es)?/(\d{2,20})(?:/(?:photo|video)/\d+)?/?$')
_PROFILE_RE = re.compile(r'^/([A-Za-z0-9_]{1,15})(?:/media)?/?$')


def extract_first_url(text):
    match = _URL_RE.search(text or '')
    return match.group().rstrip('.,;，。；)）】]') if match else None


def normalize(text):
    candidate = extract_first_url(text) or (text or '').strip()
    if re.fullmatch(r'\d{2,20}', candidate):
        candidate = f'https://x.com/i/status/{candidate}'
    parsed = urlparse(candidate if '://' in candidate else f'https://{candidate}')
    host = (parsed.hostname or '').lower()
    if parsed.scheme not in ('http', 'https') or parsed.username or parsed.password or parsed.port:
        raise ValueError('请粘贴有效的 X 帖子或博主主页链接')
    base = {'platform': 'x', 'tweet_id': '', 'handle': '', 'needs_redirect': False}
    if host in SHORT_HOSTS and re.fullmatch(r'/[A-Za-z0-9]+', parsed.path):
        return dict(base, kind='short', url=f'https://t.co{parsed.path}', needs_redirect=True)
    if host not in X_HOSTS:
        raise ValueError('不是支持的 X/Twitter 链接')
    match = _STATUS_RE.fullmatch(parsed.path)
    if match:
        handle, tweet_id = match.groups()
        return dict(base, kind='tweet', tweet_id=tweet_id,
                    url=f'https://x.com/{handle}/status/{tweet_id}')
    match = _PROFILE_RE.fullmatch(parsed.path)
    if match and match[1].lower() not in _RESERVED:
        handle = match[1]
        return dict(base, kind='profile', handle=handle, url=f'https://x.com/{handle}')
    raise ValueError('请粘贴 X 帖子链接或博主主页链接（支持 /用户名/media）')


def from_redirect(location):
    return normalize(location)
