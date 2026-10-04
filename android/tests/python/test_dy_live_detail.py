"""Regression coverage for motion omitted from public gallery share data."""

import asyncio
import copy
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

PYTHON_ROOT = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "python"
sys.path.insert(0, str(PYTHON_ROOT))

from dy.cli import dy_anonymous_share as share
from dy.cli.dy_resource_normalizer import normalize_aweme


def gallery():
    return {
        "aweme_id": "1234567890123456789",
        "images": [
            {"uri": f"photo-{index}", "url_list": [f"https://cdn.test/still-{index}.jpg"], "clip_type": 2}
            for index in range(1, 5)
        ],
        "video": {"play_addr": {"url_list": ["https://cdn.test/music.mp3"]}},
    }


def live_detail():
    data = gallery()
    for index, item in enumerate(data["images"], 1):
        item.update({
            "clip_type": 5,
            "live_photo_type": 1,
            "url_list": [f"https://cdn.test/lower-quality-{index}.webp"],
            "video": {
                "duration": 2300,
                "play_addr": {"url_list": [f"https://cdn.test/motion-{index}.mp4"]},
            },
        })
    return data


class LiveDetailMergeTest(unittest.TestCase):
    def test_pairs_reordered_motion_by_uri_and_preserves_stills(self):
        original = gallery()
        before = copy.deepcopy(original)
        detail = live_detail()
        detail["images"].reverse()
        result = share._merge_live_photo_detail(original, detail)
        self.assertEqual(before, original)
        work = normalize_aweme(result)
        self.assertEqual("live_photo", work["type"])
        self.assertEqual(4, work["counts"]["live_videos"])
        for index, item in enumerate(work["resources"]["images"], 1):
            self.assertEqual(f"image_{index}", item["id"])
            self.assertEqual([f"https://cdn.test/still-{index}.jpg"], item["download_urls"])
            self.assertEqual([f"https://cdn.test/motion-{index}.mp4"], item["live_video"]["download_urls"])

    def test_rejects_other_work_and_unmatched_or_ambiguous_images(self):
        original = gallery()
        detail = live_detail()
        detail["aweme_id"] = "9999999999999999999"
        self.assertEqual(original, share._merge_live_photo_detail(original, detail))
        detail = live_detail()
        detail["images"][0].pop("uri")
        detail["images"][1]["uri"] = "other-photo"
        detail["images"].append(copy.deepcopy(detail["images"][2]))
        work = normalize_aweme(share._merge_live_photo_detail(original, detail))
        self.assertEqual([False, False, False, True], [i["live_video"]["available"] for i in work["resources"]["images"]])

    def test_preserves_existing_motion_and_never_uses_gallery_music(self):
        original = live_detail()
        detail = live_detail()
        detail["images"][0]["video"]["play_addr"]["url_list"] = ["https://cdn.test/replacement.mp4"]
        self.assertEqual(original, share._merge_live_photo_detail(original, detail))
        result = share._merge_live_photo_detail(gallery(), gallery())
        self.assertEqual("gallery", normalize_aweme(result)["type"])
        self.assertEqual(0, normalize_aweme(result)["counts"]["live_videos"])


class LiveDetailFetchTest(unittest.IsolatedAsyncioTestCase):
    async def test_resolver_enriches_slides_with_public_detail(self):
        request = AsyncMock(return_value=(json.dumps({"status_code": 0, "aweme_detail": live_detail()}), ""))
        with patch.object(share, "_fetch_slides_item", AsyncMock(return_value=gallery())), patch.object(share, "_request_text", request):
            result = await share.resolve_public_douyin(
                "https://www.douyin.com/slides/1234567890123456789", session=object()
            )
        self.assertEqual(4, normalize_aweme(result.aweme_data)["counts"]["live_videos"])
        request.assert_awaited_once()
        self.assertIn("aweme_id=1234567890123456789", request.call_args.args[1])
        headers = request.call_args.kwargs["request_headers"]
        self.assertEqual("https://open.douyin.com", headers["Origin"])
        self.assertNotIn("Cookie", headers)

    async def test_does_not_fetch_for_video_or_complete_live_gallery(self):
        no_uri = gallery()
        for image in no_uri["images"]:
            image.pop("uri")
        for data in ({"aweme_id": "123", "video": {}}, live_detail(), no_uri):
            with self.subTest(data_type=normalize_aweme(data)["type"]), patch.object(share, "_request_text", AsyncMock()) as request:
                result = await share._enrich_live_photos(object(), data, source_id=data["aweme_id"], proxy=None)
                self.assertEqual(data, result)
                request.assert_not_awaited()

    async def test_optional_failure_keeps_original_gallery(self):
        for response in ("", "<html>challenge</html>", json.dumps({"status_code": 1}), json.dumps({"aweme_detail": {"aweme_id": "other"}})):
            data = gallery()
            with self.subTest(response=response), patch.object(share, "_request_text", AsyncMock(return_value=(response, ""))):
                self.assertEqual(data, await share._enrich_live_photos(object(), data, source_id=data["aweme_id"], proxy=None))
        for error in (share.AnonymousDouyinError("unavailable"), asyncio.TimeoutError()):
            data = gallery()
            with self.subTest(error=type(error).__name__), patch.object(share, "_request_text", AsyncMock(side_effect=error)):
                self.assertEqual(data, await share._enrich_live_photos(object(), data, source_id=data["aweme_id"], proxy=None))


class OpenDetailFallbackTest(unittest.IsolatedAsyncioTestCase):
    async def resolve_with_detail(self, payload=None, error=None):
        async def request(_session, url, **_kwargs):
            if "/aweme/v1/web/aweme/detail/" in url:
                if error is not None:
                    raise error
                return json.dumps(payload), url
            return "<html>No work data</html>", url

        with patch.object(share, "_fetch_slides_item", AsyncMock(side_effect=share.AnonymousDouyinError("no slides data"))), patch.object(share, "_request_text", AsyncMock(side_effect=request)) as fetch:
            result = await share.resolve_public_douyin(
                "https://www.douyin.com/slides/1234567890123456789", session=object()
            )
            self.assertEqual(1, sum("/aweme/v1/web/aweme/detail/" in call.args[1] for call in fetch.call_args_list))
            return result

    async def test_share_failure_can_use_current_matching_public_detail(self):
        result = await self.resolve_with_detail({"status_code": 0, "aweme_detail": live_detail()})
        self.assertEqual("1234567890123456789", result.source_id)
        self.assertEqual("live_photo", normalize_aweme(result.aweme_data)["type"])

    async def test_successful_complete_share_does_not_fetch_detail(self):
        with patch.object(share, "_fetch_slides_item", AsyncMock(return_value=live_detail())), patch.object(share, "_fetch_open_detail", AsyncMock()) as fetch:
            result = await share.resolve_public_douyin(
                "https://www.douyin.com/slides/1234567890123456789", session=object()
            )
        fetch.assert_not_awaited()
        self.assertEqual(4, normalize_aweme(result.aweme_data)["counts"]["live_videos"])

    async def test_invalid_fallback_keeps_original_share_failure(self):
        wrong_work = live_detail()
        wrong_work["aweme_id"] = "9999999999999999999"
        audio_only = gallery()
        audio_only.pop("images")
        for detail in (None, {}, {"aweme_id": "1234567890123456789"}, wrong_work, audio_only):
            with self.subTest(detail=detail), self.assertRaisesRegex(share.AnonymousDouyinError, "公开分享页未返回可解析作品数据"):
                await self.resolve_with_detail({"status_code": 0, "aweme_detail": detail})
        for error in (share.AnonymousDouyinError("network unavailable"), asyncio.TimeoutError()):
            with self.subTest(error=type(error).__name__), self.assertRaisesRegex(share.AnonymousDouyinError, "公开分享页未返回可解析作品数据"):
                await self.resolve_with_detail(error=error)

    async def test_hidden_work_returns_explicit_visibility_message(self):
        with self.assertRaisesRegex(share.AnonymousDouyinVisibilityError, "审核中或仅自己可见"):
            await self.resolve_with_detail({
                "status_code": 0,
                "aweme_detail": None,
                "filter_detail": {"aweme_id": "1234567890123456789", "filter_reason": "status_audit_self_see"},
            })


if __name__ == "__main__":
    unittest.main()
