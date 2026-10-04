"""Keep the single-note bridge and its media selection/error contracts stable."""
import asyncio
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

PYTHON_ROOT = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "python"
sys.path.insert(0, str(PYTHON_ROOT))

from xhs.cli import xhs_android_entry as entry
from xhs.cli.android_xhs import AndroidXHS

USER = "5bc340f0208fe80001ce4060"
NOTE = "6a5f00f90000000006011a80"
NOTE_URL = f"https://www.xiaohongshu.com/explore/{NOTE}?xsec_token=test-share"


class XhsSingleNoteBridgeTest(unittest.IsolatedAsyncioTestCase):
    async def test_note_urls_keep_share_parameters_and_nested_author_path(self):
        xhs = AndroidXHS.__new__(AndroidXHS)
        xhs.flow = None
        for value in (NOTE_URL, f"https://www.xiaohongshu.com/user/profile/{USER}/{NOTE}?xsec_token=test-share"):
            self.assertEqual([value], await xhs.extract_links("作品 " + value))
        xhs._resolve_short_url = AsyncMock(return_value=NOTE_URL)
        self.assertEqual([NOTE_URL], await xhs.extract_links("https://xhslink.cn/o/short-note"))

    async def test_homepage_is_rejected_at_existing_link_extraction_boundary(self):
        xhs = AndroidXHS.__new__(AndroidXHS)
        xhs.flow = None
        home = f"https://www.xiaohongshu.com/user/profile/{USER}"
        for value in (home, home + "/", home + "?xsec_token=test-share"):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "仅支持单篇笔记"):
                await xhs.extract_links(value)
        xhs._resolve_short_url = AsyncMock(return_value=home)
        with self.assertRaisesRegex(ValueError, "仅支持单篇笔记"):
            await xhs.extract_links("https://xhslink.cn/o/short-home")

    async def test_bridge_accepts_none_cursor_and_resolves_one_note(self):
        xhs = AsyncMock()
        xhs.__aenter__.return_value = xhs
        xhs.extract.return_value = [{"作品ID": NOTE, "作品链接": NOTE_URL,
                                    "作者ID": USER, "作者昵称": "测试作者", "作品类型": "图文",
                                    "下载地址": ["https://sns-webpic-qc.xhscdn.com/test.jpg"]}]
        with tempfile.TemporaryDirectory() as root, patch.dict(entry._runtime, {
            "app_root": Path(root), "output_root": Path(root),
        }), patch.object(entry, "_create_xhs", return_value=xhs):
            response = json.loads(await asyncio.to_thread(entry.resolve, NOTE_URL, None))
        self.assertTrue(response["ok"])
        self.assertEqual(2, response["schema_version"])
        self.assertEqual(NOTE, response["source"]["id"])
        self.assertEqual(1, response["work"]["counts"]["images"])
        self.assertNotIn("collection", response)
        xhs.extract.assert_awaited_once_with(NOTE_URL, download=False, data=True)

    async def test_failure_reasons_survive_v2_bridge_and_download_keeps_counters(self):
        with tempfile.TemporaryDirectory() as root, patch.dict(entry._runtime, {
            "app_root": Path(root), "output_root": Path(root),
        }):
            empty = json.loads(await asyncio.to_thread(entry.resolve, "", None))
            unsupported = json.loads(await asyncio.to_thread(entry.resolve, NOTE_URL, "old-cursor"))
            download = json.loads(await asyncio.to_thread(entry.download, ""))
        for value in (empty, unsupported, download):
            self.assertFalse(value["ok"])
            self.assertEqual(2, value["schema_version"])
            self.assertEqual(value["message"], value["error"])
        self.assertIn("请先粘贴", empty["error"])
        self.assertIn("仅支持单篇笔记", unsupported["error"])
        self.assertEqual([], download["files"])
        self.assertEqual(0, download["success"])
        self.assertEqual(1, download["failed"])

    def test_collection_selection_is_not_accepted_as_single_note_download(self):
        request = {"schema_version": 2, "source": {"platform": "xiaohongshu",
                   "id": "profile-" + USER, "url": NOTE_URL},
                   "selection": {"resource_type": "all"}}
        with self.assertRaisesRegex(ValueError, "下载选择格式错误"):
            entry._parse_download_request(json.dumps(request))
        request["selection"] = {"resource_type": "image", "resource_ids": [NOTE + "-image_1"]}
        with self.assertRaisesRegex(ValueError, "保存类型不匹配"):
            entry._parse_download_request(json.dumps(request))


if __name__ == "__main__":
    unittest.main()
