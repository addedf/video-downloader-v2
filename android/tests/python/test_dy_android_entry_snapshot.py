import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch


PROJECT_ROOT = Path(__file__).resolve().parents[2]
PYTHON_ROOT = PROJECT_ROOT / "app" / "src" / "main" / "python"
DY_ROOT = PYTHON_ROOT / "dy"
sys.path.insert(0, str(PYTHON_ROOT))
sys.path.insert(0, str(DY_ROOT))

from common.android_flow_logger import new_flow_logger
from dy.cli import dy_android_entry
from dy.cli.dy_anonymous_share import AnonymousDouyinError, AnonymousDouyinResult
from dy.cli.dy_selected_downloader import parse_download_request


class FakeConfig:
    def __init__(self, output_dir):
        self.values = {"path": output_dir, "proxy": None, "retry_times": 2, "cookies": {}}

    def get(self, key, default=None):
        return self.values.get(key, default)

    def get_cookies(self):
        return self.get("cookies") or {}


def snapshot_request():
    return parse_download_request(
        json.dumps(
            {
                "schema_version": 2,
                "source": {
                    "platform": "douyin",
                    "url": "https://www.iesdouyin.com/share/video/123456/",
                    "id": "123456",
                },
                "expected_work_type": "video",
                "selection": {
                    "resource_type": "video",
                    "resource_ids": [],
                    "include_live_video": False,
                },
                "snapshot": {
                    "source_id": "123456",
                    "title": "预览标题",
                    "author": "预览作者",
                    "work_type": "video",
                    "resources": [
                        {
                            "id": "video_1",
                            "index": 1,
                            "type": "video",
                            "title": "无水印视频",
                            "download_urls": ["https://cdn.test/video.mp4"],
                            "format_hint": "mp4",
                        }
                    ],
                },
            },
            ensure_ascii=False,
        )
    )


class SnapshotEntryTest(unittest.IsolatedAsyncioTestCase):
    def test_cookie_header_is_sanitized_for_fallback(self):
        self.assertEqual(
            {"sessionid": "abc", "msToken": "token"},
            dy_android_entry._parse_cookies(
                "sessionid=abc; ignored part; msToken=token; bad key=value"
            ),
        )

    def test_diagnostics_redact_query_and_secret_values(self):
        diagnostics = dy_android_entry._new_diagnostics(
            "https://v.douyin.com/test/?share_sign=secret&foo=bar"
        )
        diagnostics["error"] = dy_android_entry._safe_diagnostic_text(
            "request failed token=secret sessionid=abc https://example.test/a?x=y"
        )
        self.assertEqual("https://v.douyin.com/test/", diagnostics["input_url"])
        self.assertNotIn("secret", json.dumps(diagnostics, ensure_ascii=False))
        self.assertNotIn("sessionid=abc", json.dumps(diagnostics, ensure_ascii=False))

    async def test_anonymous_failure_returns_structured_diagnostics(self):
        with tempfile.TemporaryDirectory() as output_dir:
            config = FakeConfig(output_dir)
            dy_android_entry._android_global_config.config_loader = config
            with patch.object(
                dy_android_entry,
                "resolve_public_douyin",
                new=AsyncMock(side_effect=AnonymousDouyinError("story_25_filter")),
            ):
                result = await dy_android_entry._resolve_async(
                    "https://v.douyin.com/test/?share_sign=secret",
                    new_flow_logger("DiagnosticsTest"),
                )

        diagnostics = result["diagnostics"]
        self.assertFalse(result["ok"])
        self.assertEqual("anonymous", diagnostics["channel"])
        self.assertEqual("https://v.douyin.com/test/", diagnostics["input_url"])
        self.assertEqual("resolve_public_share", diagnostics["stages"][0]["name"])
        self.assertEqual("failed", diagnostics["stages"][0]["status"])
        self.assertNotIn("share_sign", json.dumps(diagnostics, ensure_ascii=False))

    async def test_resolve_uses_cookie_fallback_after_anonymous_failure(self):
        with tempfile.TemporaryDirectory() as output_dir:
            config = FakeConfig(output_dir)
            config.values["cookies"] = {"sessionid": "secret"}
            dy_android_entry._android_global_config.config_loader = config
            fallback = AnonymousDouyinResult(
                input_url="https://v.douyin.com/test/",
                resolved_url="https://www.douyin.com/video/1234567890123456789",
                source_id="1234567890123456789",
                share_type="video",
                aweme_data={
                    "aweme_id": "1234567890123456789",
                    "desc": "受限作品",
                    "video": {"play_addr": {"url_list": ["https://cdn.test/video.mp4"]}},
                },
            )
            with patch.object(
                dy_android_entry,
                "resolve_public_douyin",
                new=AsyncMock(side_effect=AnonymousDouyinError("story")),
            ), patch.object(
                dy_android_entry,
                "_resolve_with_cookies",
                new=AsyncMock(return_value=fallback),
            ) as fallback_mock:
                result = await dy_android_entry._resolve_async(
                    "https://v.douyin.com/test/",
                    new_flow_logger("CookieFallbackTest"),
                )

        self.assertTrue(result["ok"])
        fallback_mock.assert_awaited_once()
        self.assertEqual("cookie_fallback", result["diagnostics"]["channel"])
        self.assertTrue(result["diagnostics"]["fallback_used"])
        self.assertEqual(1, result["diagnostics"]["retry_count"])

    async def test_successful_snapshot_download_skips_public_share_resolve(self):
        with tempfile.TemporaryDirectory() as output_dir:
            dy_android_entry._android_global_config.config_loader = FakeConfig(output_dir)
            download_result = {
                "ok": True,
                "message": "下载完成",
                "error": None,
                "output_dir": output_dir,
                "files": [str(Path(output_dir) / "video.mp4")],
                "success": 1,
                "failed": 0,
                "skipped": 0,
            }
            with patch.object(
                dy_android_entry,
                "_download_with_context",
                new=AsyncMock(return_value=download_result),
            ) as download_mock, patch.object(
                dy_android_entry,
                "resolve_public_douyin",
                new=AsyncMock(),
            ) as resolve_mock:
                result = await dy_android_entry._download_async(
                    "https://v.douyin.com/test/",
                    new_flow_logger("SnapshotEntryTest"),
                    request=snapshot_request(),
                )

        self.assertTrue(result["ok"])
        self.assertTrue(result["used_preview_snapshot"])
        download_mock.assert_awaited_once()
        resolve_mock.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()
