import sys
import unittest
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PYTHON_ROOT = PROJECT_ROOT / "app" / "src" / "main" / "python"
if str(PYTHON_ROOT) not in sys.path:
    sys.path.insert(0, str(PYTHON_ROOT))

from xhs.cli.xhs_url_utils import (
    SHORT_XHS_URL_PATTERN,
    normalize_xhs_media_url,
    normalize_xhs_media_urls,
)


class XhsUrlUtilsTest(unittest.TestCase):
    def test_recognizes_xhslink_cn_share_text(self):
        text = "装酷但可爱 https://xhslink.cn/o/1IDhqimIGg9 去【小红书】逛逛"
        match = SHORT_XHS_URL_PATTERN.search(text)
        self.assertIsNotNone(match)
        self.assertEqual("https://xhslink.cn/o/1IDhqimIGg9", match.group())

    def test_keeps_legacy_xhslink_com_support(self):
        match = SHORT_XHS_URL_PATTERN.search("https://xhslink.com/abc123")
        self.assertIsNotNone(match)

    def test_upgrades_xhs_cdn_http_url_to_https(self):
        value = "http://sns-video-v6.xhscdn.com/path/video.mp4?token=redacted"
        self.assertEqual(
            "https://sns-video-v6.xhscdn.com/path/video.mp4?token=redacted",
            normalize_xhs_media_url(value),
        )

    def test_rejects_non_xhs_plain_http_url(self):
        self.assertEqual("", normalize_xhs_media_url("http://example.com/video.mp4"))

    def test_keep_empty_preserves_live_photo_alignment(self):
        self.assertEqual(
            ["https://sns-video-v6.xhscdn.com/a.mp4", ""],
            normalize_xhs_media_urls(
                ["http://sns-video-v6.xhscdn.com/a.mp4", None],
                keep_empty=True,
            ),
        )


if __name__ == "__main__":
    unittest.main()
