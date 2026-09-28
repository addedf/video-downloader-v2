import sys
import unittest
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PYTHON_ROOT = PROJECT_ROOT / "app" / "src" / "main" / "python"
DY_ROOT = PYTHON_ROOT / "dy"
sys.path.insert(0, str(PYTHON_ROOT))
sys.path.insert(0, str(DY_ROOT))

from dy.cli.dy_anonymous_share import (
    AnonymousDouyinError,
    _extract_route,
    _validate_douyin_url,
    canonicalize_aweme,
    extract_aweme_item,
    extract_router_data,
)
from dy.cli.dy_resource_normalizer import normalize_aweme


class AnonymousDouyinShareTest(unittest.TestCase):
    def test_extracts_video_item_from_router_data(self):
        html = '''<script>window._ROUTER_DATA = {"loaderData":{"videoInfoRes":{"item_list":[{"aweme_id":"1234567890123456789"}]}}};</script>'''
        item = extract_aweme_item(extract_router_data(html))
        self.assertEqual("1234567890123456789", item["aweme_id"])

    def test_extracts_note_and_slides_shapes(self):
        note = {"loaderData": {"page": {"noteDetailRes": {"aweme_detail": {"aweme_id": "1"}}}}}
        slides = {"loaderData": {"slidesInfoRes": {"aweme_list": [{"aweme_id": "2"}]}}}
        self.assertEqual("1", extract_aweme_item(note)["aweme_id"])
        self.assertEqual("2", extract_aweme_item(slides)["aweme_id"])

    def test_extracts_direct_slidesinfo_response(self):
        slidesinfo = {"status_code": 0, "aweme_details": [{"aweme_id": "3"}]}
        self.assertEqual("3", extract_aweme_item(slidesinfo)["aweme_id"])

    def test_router_decoder_handles_braces_inside_strings(self):
        html = '<script>window._ROUTER_DATA={"loaderData":{"videoInfoRes":{"item_list":[{"desc":"a } brace"}]}}};</script>'
        self.assertEqual("a } brace", extract_aweme_item(extract_router_data(html))["desc"])

    def test_extracts_render_data_script_with_attribute_order_and_trailing_script(self):
        html = """
        <script type="application/json" id="RENDER_DATA">
        {&quot;data&quot;:{&quot;videoDetail&quot;:{&quot;awemeId&quot;:&quot;1234567890123456789&quot;,&quot;videoInfo&quot;:{}}}}
        </script><script>window.afterRender = true;</script>
        """
        item = extract_aweme_item(extract_router_data(html))
        self.assertEqual("1234567890123456789", item["awemeId"])

    def test_extracts_url_encoded_render_data(self):
        html = """
        <script id="RENDER_DATA" type="application/json">%7B%22data%22%3A%7B%22videoDetail%22%3A%7B%22awemeId%22%3A%221234567890123456789%22%2C%22videoInfo%22%3A%7B%7D%7D%7D%7D</script>
        """
        item = extract_aweme_item(extract_router_data(html))
        self.assertEqual("1234567890123456789", item["awemeId"])

    def test_extracts_hydration_assignment_after_invalid_candidate(self):
        html = """
        <script id="RENDER_DATA" type="application/json">not-json</script>
        <script>
        window.__UNIVERSAL_DATA_FOR_REHYDRATION__ = {
          "app": {"videoDetail": {"aweme_id": "1234567890123456789", "video": {}}}
        };
        </script>
        """
        item = extract_aweme_item(extract_router_data(html))
        self.assertEqual("1234567890123456789", item["aweme_id"])

    def test_prefers_later_detail_payload_over_empty_router_state(self):
        html = """
        <script>window._ROUTER_DATA = {"loaderData": {"route": {}}};</script>
        <script id="SIGI_STATE" type="application/json">
        {"data":{"item":{"aweme_id":"1234567890123456789","video":{}}}}
        </script>
        """
        item = extract_aweme_item(extract_router_data(html))
        self.assertEqual("1234567890123456789", item["aweme_id"])

    def test_extracts_direct_aweme_object_from_dynamic_daily_video_key(self):
        payload = {
            "dynamic": {
                "videoDetail": {
                    "awemeId": "1234567890123456789",
                    "videoInfo": {"playAddr": {"uri": "v0200-daily"}},
                }
            }
        }
        item = extract_aweme_item(payload)
        work = normalize_aweme(canonicalize_aweme(item))
        self.assertEqual("1234567890123456789", item["awemeId"])
        self.assertEqual("video", work["type"])
        self.assertIn(
            "video_id=v0200-daily",
            work["resources"]["videos"][0]["download_urls"][0],
        )

    def test_extracts_video_info_alias_from_dynamic_daily_video_key(self):
        payload = {
            "dynamic": {
                "videoDetail": {
                    "aweme_id": "1234567890123456789",
                    "video_info": {"play_addr": {"uri": "v0200-daily-alias"}},
                }
            }
        }
        item = extract_aweme_item(payload)
        work = normalize_aweme(canonicalize_aweme(item))
        self.assertEqual("video", work["type"])
        self.assertIn(
            "video_id=v0200-daily-alias",
            work["resources"]["videos"][0]["download_urls"][0],
        )

    def test_reports_unavailable_daily_story(self):
        payload = {
            "loaderData": {
                "videoInfoRes": {
                    "filter_list": [
                        {"aweme_id": "1234567890123456789", "filter_reason": "story_25_filter"}
                    ],
                    "item_list": [],
                }
            }
        }
        with self.assertRaisesRegex(AnonymousDouyinError, "日常作品已过期"):
            extract_aweme_item(payload)

    def test_extracts_json_string_wrapped_hydration_payload(self):
        html = r'''<script>window.__INITIAL_STATE__ = '{"data":{"item":{"aweme_id":"1234567890123456789","video":{}}}}';</script>'''
        item = extract_aweme_item(extract_router_data(html))
        self.assertEqual("1234567890123456789", item["aweme_id"])

    def test_identifies_javascript_waf_challenge(self):
        html = '<script src="https://lf-waf-js.byted-static.com/obj/waf-jschallenge/out-sha256.js"></script>'
        with self.assertRaisesRegex(AnonymousDouyinError, "临时风控"):
            extract_router_data(html)

    def test_canonicalizes_camel_case_and_uri_play_address(self):
        item = {
            "awemeId": "1234567890123456789",
            "awemeType": 0,
            "author": {"nickname": "作者", "secUid": "sec"},
            "video": {
                "playAddr": {"uri": "v0200-test", "width": 1080, "height": 1920},
                "originCover": {"urlList": ["https://cdn.test/cover.jpg"]},
            },
        }
        aweme = canonicalize_aweme(item)
        work = normalize_aweme(aweme)
        self.assertEqual("1234567890123456789", aweme["aweme_id"])
        self.assertEqual("sec", aweme["author"]["sec_uid"])
        self.assertIn("video_id=v0200-test", work["resources"]["videos"][0]["download_urls"][0])

    def test_canonicalizes_gallery_and_live_video(self):
        item = {
            "awemeId": "1234567890123456789",
            "imagePostInfo": {
                "images": [{
                    "originImage": {"urlList": ["https://cdn.test/image.jpg"]},
                    "video": {"playAddr": {"urlList": ["https://cdn.test/live.mp4"]}},
                }]
            },
        }
        work = normalize_aweme(canonicalize_aweme(item))
        self.assertEqual("live_photo", work["type"])
        self.assertEqual(1, work["counts"]["images"])
        self.assertEqual(1, work["counts"]["live_videos"])

    def test_rejects_non_douyin_and_non_https_urls(self):
        for url in ("https://example.com/video/1234567890123456789", "http://v.douyin.com/abc"):
            with self.subTest(url=url), self.assertRaises(AnonymousDouyinError):
                _validate_douyin_url(url)

    def test_extracts_supported_route(self):
        self.assertEqual(
            ("slides", "1234567890123456789"),
            _extract_route("https://www.douyin.com/slides/1234567890123456789"),
        )


if __name__ == "__main__":
    unittest.main()
