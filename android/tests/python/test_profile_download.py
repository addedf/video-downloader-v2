import asyncio
import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'app/src/main/python'))
import httpx
from common import profile_download as module


def request():
    return {'schema_version': 2,
            'source': {'platform': 'douyin', 'id': 'profile-author', 'url': 'https://www.douyin.com/user/author'},
            'snapshot': {'source_id': 'profile-author', 'resources': [
                {'id': '123-video_1', 'type': 'video', 'format_hint': 'mp4',
                 'download_urls': ['https://media.douyinvod.com/video.mp4']},
                {'id': '456-image_1', 'type': 'image', 'format_hint': 'jpg',
                 'download_urls': ['https://image.byteimg.com/image.jpg'],
                 'live_video': {'available': True, 'download_urls': ['https://media.douyinvod.com/live.mp4']}}]},
            'selection': {'resource_type': 'all'}}


PARAMS = {'platform': 'douyin', 'expected_source_id': 'profile-author',
          'allowed_host_suffixes': ('douyinvod.com', 'byteimg.com')}


class ProfileDownloadTest(unittest.TestCase):
    def select(self, value):
        return module.select_collection_resources(value, **PARAMS)

    def test_mixed_selection_and_live_option(self):
        value = request()
        self.assertEqual(2, len(self.select(value)))
        value['selection']['include_live_video'] = True
        self.assertEqual(3, len(self.select(value)))
        value['selection']['resource_ids'] = ['456-image_1']
        self.assertEqual(2, len(self.select(value)))

    def test_empty_stale_or_wrong_type_selection_never_downloads_all(self):
        for ids in ([], ['unknown'], 'all', [None]):
            value = request()
            value['selection']['resource_ids'] = ids
            with self.subTest(ids=ids), self.assertRaises(ValueError): self.select(value)

    def test_mismatched_platform_or_source_rejected(self):
        for key, replacement in [('platform', 'xiaohongshu'), ('id', 'profile-other')]:
            value = request()
            value['source'][key] = replacement
            with self.assertRaises(ValueError): self.select(value)
        value = request()
        value['snapshot']['source_id'] = 'profile-other'
        with self.assertRaises(ValueError): self.select(value)

    def test_cdn_validation_rejects_spoofs_private_http_credentials_ports(self):
        for url in ('https://byteimg.com.evil/a.jpg', 'http://image.byteimg.com/a.jpg',
                    'https://127.0.0.1/a.jpg', 'https://u:p@image.byteimg.com/a.jpg',
                    'https://image.byteimg.com:999/a.jpg'):
            value = request()
            value['snapshot']['resources'][1]['download_urls'] = [url]
            with self.subTest(url=url), self.assertRaises(ValueError): self.select(value)

    def test_duplicates_not_saved_twice_and_ids_cannot_escape_directory(self):
        value = request()
        value['snapshot']['resources'].append(copy.deepcopy(value['snapshot']['resources'][0]))
        self.assertEqual(2, len(self.select(value)))
        value['snapshot']['resources'][0]['id'] = '../../escape'
        # IDs do not become paths: download names are bounded SHA-256 digests.
        self.assertEqual('../../escape', self.select(value)[0]['id'])

    def test_atomic_files_alternative_url_partial_failure_and_no_cookie_to_cdn(self):
        value = request()
        value['snapshot']['resources'][0]['download_urls'].insert(0, 'https://media.douyinvod.com/fail.mp4')
        seen_headers = []
        def handler(req):
            seen_headers.append(req.headers)
            if 'fail' in req.url.path:
                return httpx.Response(500)
            if req.url.path.endswith('.jpg'):
                return httpx.Response(200, headers={'content-type': 'text/html'}, content=b'blocked')
            return httpx.Response(200, headers={'content-type': 'video/mp4'}, content=b'media')
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler), follow_redirects=False)
        with tempfile.TemporaryDirectory() as tmp, patch.object(module.httpx, 'AsyncClient', return_value=client):
            result = asyncio.run(module.download_collection_snapshot(value, tmp, None,
                headers={'Cookie': 'must-not-leave', 'Authorization': 'must-not-leave'}, **PARAMS))
            self.assertFalse(result['ok'])
            self.assertEqual(1, result['success'])
            self.assertEqual(1, result['failed'])
            self.assertEqual(b'media', Path(result['files'][0]).read_bytes())
            self.assertFalse(list(Path(tmp).glob('*.part')))
        self.assertTrue(all('cookie' not in h and 'authorization' not in h for h in seen_headers))

    def test_redirect_outside_allowlist_never_requested(self):
        requests = []
        def handler(req):
            requests.append(req.url.host)
            return httpx.Response(302, headers={'location': 'https://127.0.0.1/secret'})
        async def run():
            async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
                with tempfile.TemporaryDirectory() as tmp:
                    with self.assertRaises(ValueError):
                        await module._download_one(client, 'https://media.douyinvod.com/a.mp4',
                            Path(tmp)/'media.mp4', PARAMS['allowed_host_suffixes'], lambda *a: None)
                    self.assertFalse(list(Path(tmp).iterdir()))
        asyncio.run(run())
        self.assertEqual(['media.douyinvod.com'], requests)

    def test_truncated_files_removed(self):
        async def run():
            async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(200,
                    headers={'content-type': 'video/mp4', 'content-length': '100'}, content=b'short'))) as client:
                with tempfile.TemporaryDirectory() as tmp:
                    with self.assertRaises(ValueError):
                        await module._download_one(client, 'https://media.douyinvod.com/a.mp4',
                            Path(tmp)/'media.mp4', PARAMS['allowed_host_suffixes'], lambda *a: None)
                    self.assertFalse(list(Path(tmp).iterdir()))
        asyncio.run(run())


if __name__ == '__main__': unittest.main()
