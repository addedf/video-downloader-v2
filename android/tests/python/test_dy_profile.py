import asyncio
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

from aiohttp import web

PYTHON_ROOT = Path(__file__).resolve().parents[2] / 'app' / 'src' / 'main' / 'python'
sys.path[:0] = [str(PYTHON_ROOT), str(PYTHON_ROOT / 'dy')]

from common.android_flow_logger import new_flow_logger
from dy.cli import dy_android_entry as entry, dy_anonymous_share as share, dy_profile as profile

USER = 'MS4wLjABAAAA_profile_test'
URL = 'https://www.douyin.com/user/' + USER


def video(post_id='1234567890123'):
    return {'aweme_id': post_id, 'desc': '作者发布的视频', 'author': {'sec_uid': USER, 'nickname': '测试博主'},
            'video': {'play_addr': {'url_list': ['https://v1.douyinvod.com/a.mp4']}}}


def gallery(post_id='2234567890123'):
    return {'aweme_id': post_id, 'desc': '图集', 'author': {'sec_uid': USER, 'nickname': '测试博主'},
            'images': [{'url_list': ['https://p1.douyinpic.com/a.jpg']},
                       {'url_list': ['https://p1.douyinpic.com/b.jpg']}]}


def page(items, has_more, cursor=0):
    return {'status_code': 0, 'aweme_list': items, 'has_more': has_more, 'max_cursor': cursor}


class FakeConfig:
    def __init__(self, output):
        self.values = {'path': output, 'cookies': {}, 'proxy': None, 'retry_times': 1}

    def get(self, key, default=None):
        return self.values.get(key, default)

    def get_cookies(self):
        return self.values['cookies']


class ProfileLinkTest(unittest.TestCase):
    def test_direct_and_share_profile_extract_sec_uid(self):
        self.assertEqual(USER, profile.profile_user_id('博主分享 ' + URL + '，去看看'))
        self.assertEqual(USER, profile.profile_user_id(
            'https://www.iesdouyin.com/share/user/123456?sec_uid=' + USER))

    def test_single_work_modal_and_untrusted_hosts_are_not_profiles(self):
        for url in (URL + '?modal_id=1234567890123', 'https://www.douyin.com/video/1234567890123',
                    'https://www.douyin.com.evil.test/user/' + USER, 'https://v.douyin.com/abc/'):
            self.assertIsNone(profile.profile_user_id(url))

    def test_rejects_self_credentials_ports_and_unsafe_schemes(self):
        for url in ('https://www.douyin.com/user/self', 'https://secret@www.douyin.com/user/' + USER,
                    'https://www.douyin.com:8443/user/' + USER, 'http://www.douyin.com/user/' + USER):
            with self.assertRaises(ValueError):
                profile.profile_user_id(url)

    def test_cursor_does_not_allow_query_injection(self):
        for cursor in ('-1', '1&sec_user_id=other', 'x', '9' * 30):
            with self.assertRaises(ValueError):
                profile.parse_cursor(cursor)
        self.assertEqual('0', profile.parse_cursor(None))

    def test_public_resolve_error_retains_v2_schema_and_login_reason(self):
        with tempfile.TemporaryDirectory() as output:
            entry._android_global_config.config_loader = FakeConfig(output)
            with patch.object(entry, 'resolve_profile', new=AsyncMock(side_effect=ValueError('请在抖音登录后重试'))):
                result = json.loads(entry.resolve(URL))
        self.assertEqual(2, result['schema_version'])
        self.assertFalse(result['ok'])
        self.assertIn('登录', result['error'])
        self.assertEqual(URL, result['source']['resolved_url'])
        self.assertEqual('douyin_signed_profile', result['diagnostics']['channel'])

    def test_public_bridge_exception_retains_resolve_v2_schema(self):
        with patch.object(entry, '_resolve_async', new=AsyncMock(side_effect=ValueError('网络请求失败'))):
            result = json.loads(entry.resolve(URL))
        self.assertFalse(result['ok'])
        self.assertEqual(2, result['schema_version'])
        self.assertEqual('网络请求失败', result['error'])


class ProfilePaginationTest(unittest.IsolatedAsyncioTestCase):
    async def resolve(self, pages, cursor=None, max_pages=5):
        with patch.object(profile, '_get_profile_page', new=AsyncMock(side_effect=pages)) as fetch, \
             patch.object(profile, 'MAX_PAGES', max_pages), patch.object(profile.asyncio, 'sleep', new=AsyncMock()):
            result = await profile.resolve_profile(URL, USER, client=object(), cursor=cursor)
        return result, fetch

    async def test_paginates_deduplicates_and_preserves_media_identity(self):
        result, fetch = await self.resolve([page([video()], 1, 100), page([video(), gallery()], 0)])
        self.assertTrue(result['collection']['complete'])
        self.assertIsNone(result['collection']['next_cursor'])
        self.assertEqual(2, result['collection']['posts'])
        self.assertEqual(2, result['collection']['pages'])
        self.assertEqual('100', fetch.await_args_list[1].args[2])
        self.assertEqual('profile-' + USER, result['source']['id'])
        self.assertEqual('mixed', result['work']['type'])
        groups = result['work']['resources']
        self.assertEqual(1, len(groups['videos']))
        self.assertEqual(2, len(groups['images']))
        self.assertEqual('1234567890123-video_1', groups['videos'][0]['id'])
        self.assertEqual('2234567890123-image_1', groups['images'][0]['id'])
        self.assertEqual([], groups['covers'])

    async def test_later_rate_limit_preserves_results_and_retry_cursor(self):
        result, _ = await self.resolve([page([video()], 1, 100), ValueError('login or rate limit')])
        self.assertFalse(result['collection']['complete'])
        self.assertEqual('100', result['collection']['next_cursor'])
        self.assertEqual(1, result['work']['counts']['videos'])
        self.assertIn('已保留', result['message'])

    async def test_first_page_failure_is_not_reported_as_empty_complete(self):
        with self.assertRaisesRegex(ValueError, 'login'):
            await self.resolve([ValueError('login required')])

    async def test_budget_and_resume_use_server_cursor(self):
        result, _ = await self.resolve([page([video()], 1, 100)], max_pages=1)
        self.assertEqual('100', result['collection']['next_cursor'])
        self.assertFalse(result['collection']['complete'])
        resumed, fetch = await self.resolve([page([gallery()], 0)], cursor='100')
        self.assertTrue(resumed['collection']['complete'])
        self.assertEqual('100', fetch.await_args.args[2])

    async def test_repeated_cursor_never_loops_or_claims_complete(self):
        result, fetch = await self.resolve([page([video()], 1, 100), page([video()], 1, 100)])
        self.assertEqual(2, fetch.await_count)
        self.assertFalse(result['collection']['complete'])
        self.assertIsNone(result['collection']['next_cursor'])
        self.assertIn('游标', result['message'])

    async def test_empty_pages_with_cursors_do_not_mean_complete(self):
        result, fetch = await self.resolve([page([video()], 1, 100), page([], 1, 90), page([], 1, 80)])
        self.assertEqual(3, fetch.await_count)
        self.assertEqual('80', result['collection']['next_cursor'])
        self.assertFalse(result['collection']['complete'])

    async def test_partial_missing_media_keeps_page_for_retry(self):
        result, _ = await self.resolve([page([video(), {'aweme_id': '3234567890123'}], 0)], cursor='100')
        self.assertEqual('100', result['collection']['next_cursor'])
        self.assertFalse(result['collection']['complete'])
        self.assertEqual(1, result['collection']['posts'])

    async def test_page_validation_rejects_empty_or_unauthorized_payloads(self):
        client = type('Client', (), {})()
        client._default_query = AsyncMock(return_value={})
        for payload in ({}, {'status_code': 8}, {'aweme_list': []},
                        {'aweme_list': [], 'has_more': 8}):
            client._request_json = AsyncMock(return_value=payload)
            with self.assertRaisesRegex(ValueError, '登录'):
                await profile._get_profile_page(client, USER, '0')

    async def test_request_uses_confirmed_author_post_endpoint(self):
        client = type('Client', (), {})()
        client._default_query = AsyncMock(return_value={'aid': '6383'})
        client._request_json = AsyncMock(return_value=page([video()], 0))
        await profile._get_profile_page(client, USER, '123')
        path, params = client._request_json.await_args.args
        self.assertEqual('/aweme/v1/web/aweme/post/', path)
        self.assertEqual(USER, params['sec_user_id'])
        self.assertEqual('123', params['max_cursor'])
        self.assertEqual('6383', params['aid'])

    async def test_short_share_redirect_routes_to_profile_without_numeric_work_guess(self):
        with patch.object(share, '_expand_share_url', new=AsyncMock(return_value=URL)):
            with self.assertRaises(share.AnonymousDouyinProfile) as raised:
                await share.resolve_public_douyin('https://v.douyin.com/test/', session=object())
        self.assertEqual(USER, raised.exception.user_id)

    async def test_entry_passes_runtime_login_and_cursor_without_single_work_resolve(self):
        with tempfile.TemporaryDirectory() as output:
            config = FakeConfig(output)
            config.values['cookies'] = {'sessionid': 'unit-test-only'}
            entry._android_global_config.config_loader = config
            with patch.object(entry, 'resolve_profile', new=AsyncMock(return_value={'ok': True})) as resolve, \
                 patch.object(entry, 'resolve_public_douyin', new=AsyncMock()) as single:
                result = await entry._resolve_async(URL, new_flow_logger('ProfileTest'), cursor='100')
        self.assertTrue(result['ok'])
        self.assertEqual('100', resolve.await_args.kwargs['cursor'])
        self.assertEqual({'sessionid': 'unit-test-only'}, resolve.await_args.kwargs['cookies'])
        single.assert_not_awaited()

    async def test_entry_routes_short_profile_exception(self):
        with tempfile.TemporaryDirectory() as output:
            entry._android_global_config.config_loader = FakeConfig(output)
            with patch.object(entry, 'resolve_public_douyin', new=AsyncMock(
                    side_effect=share.AnonymousDouyinProfile(URL, USER))), \
                 patch.object(entry, 'resolve_profile', new=AsyncMock(return_value={'ok': True})) as resolve:
                result = await entry._resolve_async('https://v.douyin.com/test/', new_flow_logger('ProfileTest'))
        self.assertTrue(result['ok'])
        self.assertEqual(USER, resolve.await_args.args[1])

    async def test_profile_download_bypasses_single_work_limits_and_does_not_refetch(self):
        request = {'schema_version': 2, 'source': {'id': 'profile-' + USER, 'url': URL, 'platform': 'douyin'},
                   'selection': {'resource_type': 'all'}, 'snapshot': {'source_id': 'profile-' + USER}}
        self.assertEqual(request, entry._parse_entry_download_request(json.dumps(request)))
        with tempfile.TemporaryDirectory() as output:
            entry._android_global_config.config_loader = FakeConfig(output)
            with patch('common.profile_download.download_collection_snapshot', new=AsyncMock(
                    return_value={'ok': True, 'success': 1})) as download, \
                 patch.object(entry, 'resolve_public_douyin', new=AsyncMock()) as single:
                result = await entry._download_async(URL, new_flow_logger('ProfileTest'), request=request)
        self.assertTrue(result['ok'])
        self.assertEqual('profile-' + USER, download.await_args.kwargs['expected_source_id'])
        self.assertNotIn('Cookie', download.await_args.kwargs['headers'])
        single.assert_not_awaited()

    async def test_download_rejects_profile_identity_mismatch(self):
        request = {'source': {'id': 'profile-' + USER, 'url': URL, 'platform': 'douyin'}}
        with tempfile.TemporaryDirectory() as output:
            entry._android_global_config.config_loader = FakeConfig(output)
            for input_url in ('https://www.douyin.com/user/another_user',
                              'https://www.douyin.com/video/1234567890', 'https://evil.test/user/test'):
                with self.assertRaises(ValueError):
                    await entry._download_async(input_url, new_flow_logger('ProfileTest'), request=request)


class ProfileLoginCookieIntegrationTest(unittest.IsolatedAsyncioTestCase):
    """Exercise the real config, API client and HTTP cookie jar without Douyin traffic."""

    async def asyncSetUp(self):
        from core import DouyinAPIClient

        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.requests = []
        self.pages = []
        app = web.Application()
        app.router.add_get('/aweme/v1/web/aweme/post/', self.receive_page)
        self.runner = web.AppRunner(app)
        await self.runner.setup()
        self.addAsyncCleanup(self.runner.cleanup)
        site = web.TCPSite(self.runner, '127.0.0.1', 0)
        await site.start()
        base_url = 'http://127.0.0.1:' + str(self.runner.addresses[0][1])
        self.enterContext(patch.object(DouyinAPIClient, 'BASE_URL', base_url))
        self.enterContext(patch.object(entry.ConfigLoader, '_load_env_config', return_value={}))
        self.enterContext(patch.object(entry._android_global_config, 'config_loader', None))

    async def receive_page(self, request):
        self.requests.append({'cookies': dict(request.cookies),
                              'cursor': request.query.get('max_cursor'),
                              'user_id': request.query.get('sec_user_id')})
        return web.json_response(self.pages.pop(0))

    @staticmethod
    def synthetic_cookies(suffix='initial'):
        return {name: 'unit-test-' + suffix + '-' + name
                for name in ('sessionid', 'sessionid_ss', 'sid_guard', 'sid_tt',
                             'uid_tt', 'UIFID', 'UIFID_TEMP', 'ttwid', 'msToken')}

    def warm_up(self, cookies):
        root = Path(self.temp.name)
        result = json.loads(entry.warm_up(str(root / 'app'), str(root / 'out'),
                                         '; '.join(key + '=' + value for key, value in cookies.items())))
        self.assertTrue(result['ok'])

    async def test_direct_profile_sends_saved_login_cookies_on_every_page(self):
        cookies = self.synthetic_cookies()
        self.warm_up(cookies)
        self.pages = [page([video()], 1, 100), page([gallery()], 0)]
        result = await entry._resolve_async(URL, new_flow_logger('ProfileCookieTest'))
        self.assertTrue(result['ok'])
        self.assertTrue(result['collection']['complete'])
        self.assertEqual(['0', '100'], [request['cursor'] for request in self.requests])
        self.assertTrue(all(request['user_id'] == USER for request in self.requests))
        self.assertTrue(all(request['cookies'] == cookies for request in self.requests))

    async def test_short_profile_uses_saved_login_cookies_after_share_expansion(self):
        cookies = self.synthetic_cookies()
        self.warm_up(cookies)
        self.pages = [page([video()], 0)]
        with patch.object(share, '_expand_share_url', new=AsyncMock(return_value=URL)) as expand:
            result = await entry._resolve_async('https://v.douyin.com/test/',
                                               new_flow_logger('ProfileCookieTest'))
        self.assertTrue(result['ok'])
        expand.assert_awaited_once()
        self.assertEqual(1, len(self.requests))
        self.assertTrue(self.requests[0]['cookies'] == cookies)
        self.assertEqual(USER, self.requests[0]['user_id'])

    async def test_resume_uses_refreshed_login_cookies_in_new_client(self):
        initial_cookies = self.synthetic_cookies()
        self.warm_up(initial_cookies)
        self.pages = [page([video()], 1, 100), page([gallery()], 0)]
        with patch.object(profile, 'MAX_PAGES', 1):
            first = await entry._resolve_async(URL, new_flow_logger('ProfileCookieTest'))
        self.assertEqual('100', first['collection']['next_cursor'])
        updated_cookies = self.synthetic_cookies('refreshed')
        refreshed = json.loads(entry.refresh_cookies(json.dumps(updated_cookies)))
        self.assertTrue(refreshed['cookie_fallback'])
        resumed = await entry._resolve_async(URL, new_flow_logger('ProfileCookieTest'),
                                            cursor=first['collection']['next_cursor'])
        self.assertTrue(resumed['ok'])
        self.assertTrue(resumed['collection']['complete'])
        self.assertEqual(['0', '100'], [request['cursor'] for request in self.requests])
        self.assertTrue(self.requests[0]['cookies'] == initial_cookies)
        self.assertTrue(self.requests[1]['cookies'] == updated_cookies)


if __name__ == '__main__':
    unittest.main()
