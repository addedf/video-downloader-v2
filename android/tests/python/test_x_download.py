import asyncio
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'app/src/main/python'))
import httpx
from x.link_normalizer import normalize
from x.media_extractor import extract_resources, from_fx, media_url, photo_url
from x.x_service import XService
from x import x_android_entry as entry


def post(id='123', handle='NASA', kind='photo'):
    media = {'id': id + '0', 'type': kind, 'url': f'https://pbs.twimg.com/media/{id}.png',
             'width': 800, 'height': 600}
    if kind != 'photo':
        media.update(url=f'https://video.twimg.com/{id}/low.mp4',
                     thumbnail_url=f'https://pbs.twimg.com/{id}.jpg', duration=1.25,
                     formats=[{'container': 'm3u8', 'url': 'https://video.twimg.com/a.m3u8'},
                              {'container': 'mp4', 'bitrate': 9, 'url': f'https://video.twimg.com/{id}/high.mp4'},
                              {'container': 'mp4', 'bitrate': 1, 'url': f'https://video.twimg.com/{id}/low.mp4'}])
    return {'id': id, 'type': 'status', 'author': {'screen_name': handle, 'name': handle},
            'text': 'A test post', 'media': {'all': [media]}}


def page(posts, cursor=None):
    return {'code': 200, 'results': posts, 'cursor': {'bottom': cursor, 'top': None}}


class XLinkTest(unittest.TestCase):
    def test_profiles_and_media_tabs(self):
        for url in ('https://x.com/NASA', 'https://twitter.com/NASA/media?s=20', 'https://mobile.x.com/NASA/'):
            self.assertEqual(normalize(url)['url'], 'https://x.com/NASA')
            self.assertEqual(normalize(url)['kind'], 'profile')

    def test_posts_and_shared_text(self):
        for url in ('看这里 https://x.com/NASA/status/123/video/1?s=1。',
                    'https://fixupx.com/NASA/statuses/123/photo/2', '123'):
            self.assertEqual(normalize(url)['tweet_id'], '123')
        self.assertEqual(normalize('https://x.com/i/web/status/123')['tweet_id'], '123')

    def test_reject_reserved_paths_spoofed_hosts_and_credentials(self):
        for url in ('https://x.com/home', 'https://x.com/NASA/likes', 'https://x.com.evil/NASA',
                    'https://evil.com/x.com/NASA', 'https://u:p@x.com/NASA', 'https://x.com:999/NASA',
                    'https://x.com/NASA/status/123bad', 'https://x.com/NASA/anything/status/123'):
            with self.subTest(url=url), self.assertRaises(ValueError):
                normalize(url)

    def test_short_redirect_and_reject_unrelated_target(self):
        for target, ok in [('https://x.com/NASA/status/123', True), ('https://evil.test', False)]:
            http = httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(302, headers={'location': target})))
            service = XService(http, Mock())
            if ok:
                self.assertEqual(service.normalize('https://t.co/abc')['tweet_id'], '123')
            else:
                with self.assertRaises(ValueError): service.normalize('https://t.co/abc')
            service.close()


class XMediaTest(unittest.TestCase):
    def test_best_mp4_and_gif(self):
        for kind in ('video', 'gif'):
            resource = extract_resources(from_fx(post(kind=kind)))['resources']['videos'][0]
            self.assertTrue(resource['download_urls'][0].endswith('/high.mp4'))
            self.assertEqual(resource['duration_ms'], 1250)
            if kind == 'gif': self.assertIn('GIF', resource['title'])

    def test_original_image_format_and_existing_query(self):
        url, ext = photo_url('https://pbs.twimg.com/media/abc.png?name=small')
        self.assertEqual(url, 'https://pbs.twimg.com/media/abc?format=png&name=orig')
        self.assertEqual(ext, 'png')
        self.assertEqual(photo_url('https://pbs.twimg.com/media/abc?format=webp&name=small')[1], 'webp')

    def test_no_quote_media_and_unwrap_visibility(self):
        tweet = from_fx(post())
        tweet['quoted_status_result'] = {'result': from_fx(post('456', kind='video'))}
        result = extract_resources({'__typename': 'TweetWithVisibilityResults', 'tweet': tweet})
        self.assertEqual(len(result['resources']['images']), 1)
        self.assertFalse(result['resources']['videos'])

    def test_cdn_allowlist(self):
        for url in ('http://pbs.twimg.com/a.jpg', 'https://pbs.twimg.com.evil/a.jpg',
                    'https://u:p@video.twimg.com/a.mp4', 'https://127.0.0.1/a.jpg'):
            self.assertFalse(media_url(url))


class XProfileTest(unittest.TestCase):
    def setUp(self):
        self.sleep = patch('x.x_service.time.sleep').start()
        self.service = XService(http=Mock(), guest=Mock())
        self.addCleanup(patch.stopall)

    def test_auto_pagination_dedup_and_author_filter(self):
        self.service._fx = Mock(side_effect=[page([post(), post('222', 'Other')], 'next'),
                                             page([post(), post('456', kind='video')])])
        result = self.service.resolve('https://x.com/NASA')
        self.assertTrue(result['collection']['complete'])
        self.assertEqual(result['work']['counts']['images'], 1)
        self.assertEqual(result['work']['counts']['videos'], 1)
        self.assertEqual(result['work']['type'], 'mixed')
        self.assertEqual(self.service._fx.call_args.args[1]['cursor'], 'next')

    def test_limit_retains_cursor_and_resume(self):
        self.service._fx = Mock(return_value=page([post()], 'next'))
        with patch('x.x_service.MAX_PAGES', 1):
            result = self.service.resolve('https://x.com/NASA')
            self.assertFalse(result['collection']['complete'])
            self.assertEqual(result['collection']['next_cursor'], 'next')
            self.service.resolve('https://x.com/NASA', 'next')
            self.assertEqual(self.service._fx.call_args.args[1]['cursor'], 'next')

    def test_rate_limit_preserves_partial_batch(self):
        response = httpx.Response(429, request=httpx.Request('GET', 'https://api.fxtwitter.com'))
        self.service._fx = Mock(side_effect=[page([post()], 'next'),
            httpx.HTTPStatusError('rate', request=response.request, response=response)])
        result = self.service.resolve('https://x.com/NASA')
        self.assertEqual(result['collection']['next_cursor'], 'next')
        self.assertIn('限流', result['message'])
        self.assertFalse(result['collection']['complete'])

    def test_repeated_cursor_never_claims_complete(self):
        self.service._fx = Mock(return_value=page([post()], 'same'))
        result = self.service.resolve('https://x.com/NASA')
        self.assertFalse(result['collection']['complete'])
        self.assertIsNone(result['collection']['next_cursor'])
        self.assertEqual(self.service._fx.call_count, 2)
        self.assertIn('重复', result['message'])

    def test_empty_page_with_cursor_continues(self):
        self.service._fx = Mock(side_effect=[page([], 'next'), page([post()])])
        self.assertTrue(self.service.resolve('https://x.com/NASA')['collection']['complete'])

    def test_malformed_page_does_not_claim_complete(self):
        self.service._fx = Mock(return_value={'code': 200, 'results': []})
        with self.assertRaises(ValueError): self.service.resolve('https://x.com/NASA')

    def test_empty_terminal_continuation_is_success(self):
        self.service._fx = Mock(return_value=page([]))
        result = self.service.resolve('https://x.com/NASA', 'older')
        self.assertTrue(result['ok'])
        self.assertTrue(result['collection']['complete'])
        self.assertIsNone(result['collection']['next_cursor'])

    def test_changing_cursors_without_new_media_pause(self):
        self.service._fx = Mock(side_effect=[page([post()], 'a'), page([], 'b'), page([], 'c'), page([], 'd')])
        result = self.service.resolve('https://x.com/NASA')
        self.assertFalse(result['collection']['complete'])
        self.assertEqual(result['collection']['next_cursor'], 'd')
        self.assertIn('不能确认', result['message'])

    def test_guest_failure_falls_back_to_fx(self):
        self.service.guest.tweet_result.side_effect = RuntimeError('graphql error')
        self.service._fx = Mock(return_value={'code': 200, 'status': post()})
        result = self.service.resolve('https://x.com/NASA/status/123')
        self.assertEqual(result['diagnostics']['channel'], 'fxtwitter')
        self.assertEqual(result['work']['counts']['images'], 1)


class XDownloadTest(unittest.TestCase):
    def request(self):
        resources = extract_resources(from_fx(post()))['resources']['images']
        resources += extract_resources(from_fx(post('456', kind='video')))['resources']['videos']
        return {'schema_version': 2, 'source': {'platform': 'x', 'url': 'https://x.com/NASA', 'id': 'profile-nasa'},
                'snapshot': {'source_id': 'profile-nasa', 'resources': resources},
                'selection': {'resource_type': 'all'}}

    def test_snapshot_uses_all_media_without_resolving(self):
        with patch.object(entry, '_resolve_sync', side_effect=AssertionError('must not re-resolve')):
            self.assertEqual(len(entry._download_resources('https://x.com/NASA', json.dumps(self.request()))), 2)

    def test_selected_and_empty_ids(self):
        request = self.request()
        for ids, count in [(['456-video-0'], 1), ([], 0)]:
            request['selection']['resource_ids'] = ids
            self.assertEqual(len(entry._download_resources('https://x.com/NASA', json.dumps(request))), count)

    def test_mismatch_invalid_json_and_unsafe_url_rejected(self):
        with self.assertRaises(ValueError): entry._download_resources('https://x.com/other', json.dumps(self.request()))
        with self.assertRaises(ValueError): entry._download_resources('https://x.com/NASA', '{bad')
        request = self.request()
        request['snapshot']['resources'][0]['download_urls'] = ['https://evil.test/file']
        with self.assertRaises(ValueError): entry._download_resources('https://x.com/NASA', json.dumps(request))

    def test_atomic_download_and_actual_image_host(self):
        async def run():
            async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(
                    200, headers={'content-type': 'image/png'}, content=b'png-data'))) as client:
                with tempfile.TemporaryDirectory() as tmp:
                    path = Path(tmp)/'test.png'
                    metric = await entry._download_one(client, 'https://pbs.twimg.com/test.png', path)
                    self.assertEqual(path.read_bytes(), b'png-data')
                    self.assertEqual(metric['host'], 'pbs.twimg.com')
                    self.assertFalse(path.with_suffix('.png.part').exists())
        asyncio.run(run())

    def test_incomplete_and_html_do_not_leave_file(self):
        async def run(headers):
            async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(
                    200, headers=headers, content=b'bad'))) as client:
                with tempfile.TemporaryDirectory() as tmp:
                    target = Path(tmp)/'test.png'
                    with self.assertRaises(ValueError):
                        await entry._download_one(client, 'https://pbs.twimg.com/test.png', target)
                    self.assertFalse(target.exists())
                    self.assertFalse(target.with_suffix('.png.part').exists())
        asyncio.run(run({'content-type': 'text/html'}))
        asyncio.run(run({'content-type': 'image/png', 'content-length': '100'}))


if __name__ == '__main__':
    unittest.main()
