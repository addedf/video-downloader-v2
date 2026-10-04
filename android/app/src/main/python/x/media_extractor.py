"""将 X GraphQL / FxTwitter 媒体统一为 Android v2 资源协议。"""
from urllib.parse import urlparse, parse_qs, urlencode, urlunparse


def media_url(url):
    """下载和预览只允许 X 的 HTTPS 媒体 CDN，不向第三方发送 Cookie。"""
    p = urlparse(url or '')
    return url if (p.scheme == 'https' and p.hostname in ('pbs.twimg.com', 'video.twimg.com')
                   and not p.username and not p.password and p.port in (None, 443)) else ''


def photo_url(url, size='orig'):
    url = media_url(url)
    if not url:
        return '', 'jpg'
    p = urlparse(url)
    query = parse_qs(p.query)
    ext = query.get('format', [p.path.rsplit('.', 1)[-1]])[0].lower()
    if ext not in ('jpg', 'jpeg', 'png', 'webp', 'gif'):
        ext = 'jpg'
    path = p.path
    if path.lower().endswith(('.jpg', '.jpeg', '.png', '.webp', '.gif')):
        path = path.rsplit('.', 1)[0]
    return urlunparse(p._replace(path=path, query=urlencode({'format': ext, 'name': size}))), ext


def unwrap(tweet):
    while tweet.get('__typename') == 'TweetWithVisibilityResults':
        tweet = tweet.get('tweet') or {}
    return tweet


def tweet_media(tweet):
    return (((unwrap(tweet).get('legacy') or {}).get('extended_entities') or {}).get('media') or [])


def video_variants(media):
    return sorted([v for v in (media.get('video_info') or {}).get('variants', [])
                   if v.get('content_type') == 'video/mp4' and media_url(v.get('url'))],
                  key=lambda v: v.get('bitrate') or 0, reverse=True)


def best_video_url(media):
    variants = video_variants(media)
    return variants[0]['url'] if variants else None


def from_fx(post):
    """只转换当前帖的媒体，不递归引入引用帖或转推内容。"""
    author = post.get('author') or {}
    media = post.get('media') or {}
    entities = []
    for item in media.get('all') or (media.get('photos', []) + media.get('videos', [])):
        kind = item.get('type')
        if kind not in ('photo', 'video', 'gif'):
            continue
        variants = [{'url': v.get('url'), 'bitrate': v.get('bitrate', 0), 'content_type': 'video/mp4'}
                    for v in item.get('formats') or [] if v.get('container') == 'mp4']
        if not variants and urlparse(item.get('url') or '').path.endswith('.mp4'):
            variants = [{'url': item['url'], 'content_type': 'video/mp4'}]
        entities.append({
            'id_str': item.get('id'), 'type': 'animated_gif' if kind == 'gif' else kind,
            'media_url_https': item.get('url') if kind == 'photo' else item.get('thumbnail_url'),
            'ext_alt_text': item.get('altText'),
            'sizes': {'large': {'w': item.get('width'), 'h': item.get('height')}},
            'video_info': {'variants': variants, 'duration_millis': round((item.get('duration') or 0) * 1000)},
        })
    return {'rest_id': str(post.get('id') or ''),
            'legacy': {'full_text': post.get('text') or '', 'extended_entities': {'media': entities}},
            'core': {'user_results': {'result': {'rest_id': author.get('id'), 'legacy': author}}}}


def extract_resources(tweet):
    tweet = unwrap(tweet)
    tweet_id = str(tweet.get('rest_id') or '')
    user = ((tweet.get('core') or {}).get('user_results') or {}).get('result') or {}
    author = dict(user.get('legacy') or {}, **(user.get('core') or {}))
    text = ((tweet.get('legacy') or {}).get('full_text') or '').strip()
    groups = {'videos': [], 'images': [], 'covers': [], 'audios': []}
    for index, item in enumerate(tweet_media(tweet)):
        kind = item.get('type')
        if kind == 'photo':
            url, ext = photo_url(item.get('media_url_https'))
            preview, _ = photo_url(item.get('media_url_https'), 'small')
            resource_type, label = 'image', 'X 图片'
        elif kind in ('video', 'animated_gif'):
            url, ext = best_video_url(item), 'mp4'
            preview = media_url(item.get('media_url_https'))
            resource_type, label = 'video', 'GIF（MP4）' if kind == 'animated_gif' else 'X 视频'
        else:
            continue
        if not url:
            continue
        size = (item.get('sizes') or {}).get('large') or {}
        groups[resource_type + 's'].append({
            'id': f'{tweet_id}-{resource_type}-{index}', 'index': index, 'type': resource_type,
            'title': f'{label} · {text[:80]}' if text else label,
            'preview_urls': [preview] if preview else [], 'download_urls': [url],
            'width': size.get('w'), 'height': size.get('h'),
            'duration_ms': (item.get('video_info') or {}).get('duration_millis'), 'format_hint': ext,
        })
    return {'tweet_id': tweet_id, 'text': text,
            'author': {'id': user.get('rest_id') or '', 'name': author.get('name') or '',
                       'handle': author.get('screen_name') or ''}, 'resources': groups}


def work_for(groups, title, author):
    videos, images = groups['videos'], groups['images']
    return {'type': 'mixed' if videos and images else 'video' if videos else 'gallery',
            'title': title, 'author': author, 'resources': groups,
            'counts': dict({k: len(v) for k, v in groups.items()}, live_videos=0),
            'capabilities': {'has_video': bool(videos), 'has_images': bool(images),
                             'has_cover': False, 'has_audio': False, 'has_live_video': False}}
