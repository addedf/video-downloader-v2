import json
import sys
import tempfile
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
PYTHON_ROOT = PROJECT_ROOT / "app" / "src" / "main" / "python"
DY_ROOT = PYTHON_ROOT / "dy"
sys.path.insert(0, str(PYTHON_ROOT))
sys.path.insert(0, str(DY_ROOT))

from dy.cli.dy_resource_normalizer import normalize_aweme
from dy.cli.dy_selected_downloader import (
    download_selected_resources,
    parse_download_request,
    select_download_source_url,
)


def media(url, width=1080, height=1920):
    return {"url_list": [url], "width": width, "height": height}


def base_aweme():
    return {
        "aweme_id": "123456",
        "desc": "测试作品",
        "create_time": 1_700_000_000,
        "author": {"nickname": "测试作者", "sec_uid": "sec-user"},
        "video": {"cover": media("https://cdn.test/cover.jpg")},
        "music": {
            "title": "测试原声",
            "play_url": media("https://cdn.test/audio.m4a"),
            "duration": 8,
        },
    }


def image_item(index, *, live=False):
    item = {
        "origin_image": media(f"https://cdn.test/image-{index}.jpg", 1440, 1920),
        "display_image": media(f"https://cdn.test/image-{index}-preview.webp", 720, 960),
    }
    if live:
        item["video"] = {
            "play_addr": media(f"https://cdn.test/image-{index}-live.mp4", 1080, 1440),
            "duration": 2_500,
        }
    return item


class ResourceNormalizerTest(unittest.TestCase):
    def test_video_exposes_video_cover_and_audio(self):
        aweme = base_aweme()
        aweme["video"].update(
            {
                "play_addr": media("https://cdn.test/video.mp4"),
                "duration": 8_430,
            }
        )

        work = normalize_aweme(aweme)

        self.assertEqual("video", work["type"])
        self.assertEqual(1, work["counts"]["videos"])
        self.assertEqual(1, work["counts"]["covers"])
        self.assertEqual(1, work["counts"]["audios"])
        self.assertFalse(work["capabilities"]["has_live_video"])

    def test_pure_gallery_has_no_video_or_live_option(self):
        aweme = base_aweme()
        aweme["image_post_info"] = {"images": [image_item(1), image_item(2)]}

        work = normalize_aweme(aweme)

        self.assertEqual("gallery", work["type"])
        self.assertEqual(2, work["counts"]["images"])
        self.assertEqual(0, work["counts"]["videos"])
        self.assertEqual(0, work["counts"]["live_videos"])

    def test_live_gallery_pairs_each_motion_video_with_its_image(self):
        aweme = base_aweme()
        aweme["image_post_info"] = {
            "images": [image_item(1, live=True), image_item(2, live=True)]
        }

        work = normalize_aweme(aweme)

        self.assertEqual("live_photo", work["type"])
        self.assertEqual(2, work["counts"]["live_videos"])
        first, second = work["resources"]["images"]
        self.assertIn("image-1-live.mp4", first["live_video"]["download_urls"][0])
        self.assertIn("image-2-live.mp4", second["live_video"]["download_urls"][0])

    def test_mixed_gallery_counts_only_images_with_motion_video(self):
        aweme = base_aweme()
        aweme["image_post_info"] = {
            "images": [image_item(1, live=True), image_item(2), image_item(3, live=True)]
        }

        work = normalize_aweme(aweme)

        self.assertEqual("live_photo", work["type"])
        self.assertEqual(3, work["counts"]["images"])
        self.assertEqual(2, work["counts"]["live_videos"])
        self.assertFalse(work["resources"]["images"][1]["live_video"]["available"])

    def test_duplicate_image_urls_are_removed_without_reordering(self):
        aweme = base_aweme()
        duplicate = "https://cdn.test/same.jpg"
        aweme["images"] = [
            {
                "watermark_free_download_url_list": [duplicate, duplicate],
                "origin_image": {"url_list": [duplicate, "https://cdn.test/fallback.jpg"]},
            }
        ]

        urls = normalize_aweme(aweme)["resources"]["images"][0]["download_urls"]

        self.assertEqual([duplicate, "https://cdn.test/fallback.jpg"], urls)

    def test_cover_prefers_complete_origin_cover_over_cropped_display_cover(self):
        aweme = base_aweme()
        aweme["video"] = {
            "cover": media("https://cdn.test/cropped-cover.jpg", 720, 1280),
            "origin_cover": media("https://cdn.test/original-cover.jpg", 1440, 1920),
            "dynamic_cover": media("https://cdn.test/animated-cover.webp", 720, 1280),
        }

        cover = normalize_aweme(aweme)["resources"]["covers"][0]

        self.assertEqual("https://cdn.test/original-cover.jpg", cover["download_urls"][0])
        self.assertEqual(1440, cover["width"])
        self.assertEqual(1920, cover["height"])

    def test_gallery_cover_prefers_complete_first_image(self):
        aweme = base_aweme()
        aweme["video"].update(
            {
                "origin_cover": media("https://cdn.test/gallery-cover.jpg", 1080, 1920),
                "cover": media("https://cdn.test/gallery-cover-cropped.jpg", 720, 1280),
            }
        )
        aweme["image_post_info"] = {"images": [image_item(1, live=True), image_item(2)]}

        cover = normalize_aweme(aweme)["resources"]["covers"][0]

        self.assertEqual("https://cdn.test/image-1.jpg", cover["download_urls"][0])
        self.assertEqual(1440, cover["width"])
        self.assertEqual(1920, cover["height"])

    def test_gallery_cover_reuses_first_normalized_image_candidates(self):
        aweme = base_aweme()
        aweme["image_post_info"] = {
            "images": [
                {
                    "url_list": ["https://cdn.test/content-no-watermark.jpg"],
                    "origin_image": media(
                        "https://cdn.test/owner_watermark_image.jpg", 1440, 1920
                    ),
                    "display_image": media(
                        "https://cdn.test/cropped-preview.webp", 720, 960
                    ),
                }
            ]
        }

        work = normalize_aweme(aweme)
        image_urls = work["resources"]["images"][0]["download_urls"]
        cover_urls = work["resources"]["covers"][0]["download_urls"]

        self.assertEqual("https://cdn.test/content-no-watermark.jpg", image_urls[0])
        self.assertEqual(image_urls, cover_urls[: len(image_urls)])


class FakeApiClient:
    async def get_session(self):
        return object()

    def sign_url(self, url):
        return url, "test-agent"


class FakeProgressReporter:
    def __init__(self):
        self.total = 0
        self.advanced = []

    def set_item_total(self, total, _detail):
        self.total = total

    def advance_item(self, status, detail):
        self.advanced.append((status, detail))


class FakeFileManager:
    pass


class FakeDownloader:
    def __init__(self, fail_labels=()):
        self.api_client = FakeApiClient()
        self.progress_reporter = FakeProgressReporter()
        self.file_manager = FakeFileManager()
        self.fail_labels = set(fail_labels)
        self.calls = []

    def _build_no_watermark_url(self, _aweme_data):
        return None

    def _download_headers(self, user_agent=None):
        return {"User-Agent": user_agent or "test-agent"}

    async def _download_with_retry(self, url, save_path, _session, **kwargs):
        self.calls.append((url, save_path, kwargs))
        if any(label in save_path.name for label in self.fail_labels):
            return False
        return save_path


def request_json(resource_type="image", include_live_video=False, **overrides):
    payload = {
        "schema_version": 2,
        "source": {
            "platform": "douyin",
            "url": "https://www.douyin.com/note/123456",
            "id": "123456",
        },
        "expected_work_type": "live_photo",
        "selection": {
            "resource_type": resource_type,
            "resource_ids": [],
            "include_live_video": include_live_video,
        },
    }
    payload.update(overrides)
    return json.dumps(payload, ensure_ascii=False)


class DownloadRequestTest(unittest.TestCase):
    def test_parses_valid_request(self):
        request = parse_download_request(request_json(include_live_video=True))
        self.assertEqual("image", request["selection"]["resource_type"])
        self.assertTrue(request["selection"]["include_live_video"])

    def test_rejects_invalid_source_and_selection_types(self):
        invalid_payloads = [
            request_json(source={"platform": "xhs", "url": "u", "id": "1"}),
            request_json(expected_work_type="unknown"),
            request_json(selection={"resource_type": "archive"}),
            request_json(selection={"resource_type": "image", "include_live_video": "yes"}),
        ]
        for payload in invalid_payloads:
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                parse_download_request(payload)

    def test_selected_download_prefers_resolved_work_url_over_short_input(self):
        request = parse_download_request(request_json())

        source_url = select_download_source_url(
            "https://v.douyin.com/4og144EvMso/", request
        )

        self.assertEqual("https://www.douyin.com/note/123456", source_url)

    def test_legacy_download_keeps_original_input_without_request(self):
        source_url = select_download_source_url(
            "4.84 https://v.douyin.com/4og144EvMso/ 复制此链接", None
        )

        self.assertEqual(
            "4.84 https://v.douyin.com/4og144EvMso/ 复制此链接", source_url
        )


class SelectedDownloaderTest(unittest.IsolatedAsyncioTestCase):
    async def test_live_download_attempts_image_and_paired_video_independently(self):
        aweme = base_aweme()
        aweme["image_post_info"] = {"images": [image_item(1, live=True)]}
        work = normalize_aweme(aweme)
        request = parse_download_request(request_json(include_live_video=True))
        downloader = FakeDownloader(fail_labels={"image_01.jpg"})

        with tempfile.TemporaryDirectory() as output_dir:
            result = await download_selected_resources(
                request=request,
                aweme_data=aweme,
                work=work,
                downloader=downloader,
                output_root=Path(output_dir),
            )

        self.assertTrue(result["ok"])
        self.assertEqual(1, result["success"])
        self.assertEqual(1, result["failed"])
        self.assertGreaterEqual(len(downloader.calls), 2)
        self.assertIn("image_01_live", downloader.calls[-1][1].name)

    async def test_audio_keeps_declared_audio_extension(self):
        aweme = base_aweme()
        aweme["video"].update({"play_addr": media("https://cdn.test/video.mp4")})
        work = normalize_aweme(aweme)
        request = parse_download_request(
            request_json(resource_type="audio", expected_work_type="video")
        )
        downloader = FakeDownloader()

        with tempfile.TemporaryDirectory() as output_dir:
            result = await download_selected_resources(
                request=request,
                aweme_data=aweme,
                work=work,
                downloader=downloader,
                output_root=Path(output_dir),
            )

        self.assertTrue(result["ok"])
        self.assertEqual(".m4a", downloader.calls[0][1].suffix)
        self.assertFalse(downloader.calls[0][2]["prefer_response_content_type"])


if __name__ == "__main__":
    unittest.main()
