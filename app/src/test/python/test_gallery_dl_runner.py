import io
import pathlib
import sys
import unittest
from unittest import mock

PY_SRC = pathlib.Path(__file__).resolve().parents[2] / "main" / "python"
if str(PY_SRC) not in sys.path:
    sys.path.insert(0, str(PY_SRC))

import gallery_dl_runner  # noqa: E402


class TestPagePreviewFallback(unittest.TestCase):
    def test_extracts_open_graph_image_when_attribute_order_varies(self):
        html = b'''<html><head><meta content="/assets/poster.jpg?x=1&amp;y=2" property="og:image"></head></html>'''
        with mock.patch("urllib.request.urlopen", return_value=io.BytesIO(html)):
            preview = gallery_dl_runner._extract_page_preview_url("https://example.com/watch/123")
        self.assertEqual(preview, "https://example.com/assets/poster.jpg?x=1&y=2")

    def test_extracts_video_poster_when_open_graph_is_missing(self):
        html = b'''<html><body><video controls poster="../thumbs/video.webp"></video></body></html>'''
        with mock.patch("urllib.request.urlopen", return_value=io.BytesIO(html)):
            preview = gallery_dl_runner._extract_page_preview_url("https://example.com/videos/123")
        self.assertEqual(preview, "https://example.com/thumbs/video.webp")

    def test_rejects_non_http_page_urls(self):
        self.assertIsNone(gallery_dl_runner._extract_page_preview_url("file:///tmp/video.html"))


if __name__ == "__main__":
    unittest.main()
