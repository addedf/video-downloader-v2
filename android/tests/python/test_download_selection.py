"""Selection contract regressions without network or media downloads."""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

PYTHON_ROOT = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "python"
sys.path.insert(0, str(PYTHON_ROOT))

from x import x_android_entry
from xhs.cli import xhs_android_entry
from source.application.download import Download as XhsDownload


def x_request(ids_marker="omitted"):
    selection = {"resource_type": "image"}
    if ids_marker != "omitted":
        selection["resource_ids"] = ids_marker
    return json.dumps({
        "schema_version": 2,
        "source": {"platform": "x", "id": "123456", "url": "https://x.com/test/status/123456"},
        "selection": selection,
        "snapshot": {
            "source_id": "123456",
            "resources": [{
                "id": f"123456-image-{index}", "type": "image", "index": index,
                "download_urls": [f"https://pbs.twimg.com/media/photo{index}.jpg?name=orig"],
                "format_hint": "jpg",
            } for index in (1, 2)],
        },
    })


class XSelectionTest(unittest.TestCase):
    def selected(self, ids):
        return x_android_entry._download_resources("https://x.com/test/status/123456", x_request(ids))

    def test_selection_filters_snapshot_without_re_resolving(self):
        with patch.object(x_android_entry, "_resolve_sync") as resolve:
            selected = self.selected(["123456-image-2"])
        self.assertEqual(["123456-image-2"], [resource["id"] for resource in selected])
        resolve.assert_not_called()

    def test_empty_selection_is_not_all_and_legacy_selection_is_all(self):
        self.assertEqual([], self.selected([]))
        self.assertEqual(2, len(self.selected(None)))
        self.assertEqual(2, len(self.selected("omitted")))

    def test_invalid_or_stale_selection_never_falls_back_to_all(self):
        for ids in ("123456-image-1", [""], [123], ["123456-image-9"],
                    ["123456-image-1", "123456-image-9"]):
            with self.subTest(ids=ids), self.assertRaises(ValueError):
                self.selected(ids)


class XhsSelectionTest(unittest.IsolatedAsyncioTestCase):
    def test_indices_keep_null_and_empty_distinct_for_each_resource_type(self):
        for kind in ("image", "video", "cover"):
            with self.subTest(kind=kind):
                selection = {"resource_type": kind, "resource_ids": []}
                self.assertEqual([], xhs_android_entry._selected_indices(selection))
                selection["resource_ids"] = None
                self.assertEqual([1] if kind == "cover" else None,
                                 xhs_android_entry._selected_indices(selection))
        self.assertEqual([3, 1], xhs_android_entry._selected_indices({
            "resource_type": "image", "resource_ids": ["image_3", "image_1", "image_3"],
        }))

    def test_invalid_ids_cannot_turn_into_save_all(self):
        for ids in (["video_1"], ["image_0"], [""], [1], "image_1"):
            with self.subTest(ids=ids), self.assertRaises(ValueError):
                xhs_android_entry._selected_indices({"resource_type": "image", "resource_ids": ids})

    async def test_empty_selection_stops_before_extraction_or_file_creation(self):
        with tempfile.TemporaryDirectory() as root, patch.dict(xhs_android_entry._runtime, {
            "app_root": Path(root), "output_root": Path(root),
        }), patch.object(xhs_android_entry, "_create_xhs") as create:
            result = await xhs_android_entry._download_async(
                "https://www.xiaohongshu.com/explore/note", None,
                request={"selection": {"resource_type": "image", "resource_ids": []}},
            )
            self.assertFalse(result["ok"])
            create.assert_not_called()
            self.assertEqual([], list(Path(root).iterdir()))

    def test_only_selected_image_gets_its_paired_live_video(self):
        downloader = XhsDownload.__new__(XhsDownload)
        downloader.image_download = True
        downloader.live_download = True
        downloader.image_format_list = ("jpeg", "png")
        downloader.image_format = "jpeg"
        downloader.live_format = "mp4"
        with patch.object(downloader, "_Download__check_exists_path", return_value=False):
            tasks = downloader._Download__ready_download_image(
                ["image-1", "image-2", "image-3"],
                ["live-1", "live-2", "live-3"], [2], Path("/unused"), "note",
            )
        self.assertEqual(["image-2", "live-2"], [task[0] for task in tasks])


if __name__ == "__main__":
    unittest.main()
