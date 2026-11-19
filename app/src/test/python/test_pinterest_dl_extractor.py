import json
import pathlib
import sys
import unittest
from types import SimpleNamespace
from unittest import mock

PY_SRC = pathlib.Path(__file__).resolve().parents[2] / "main" / "python"
if str(PY_SRC) not in sys.path:
    sys.path.insert(0, str(PY_SRC))

import gallery_dl_runner  # noqa: E402
import pinterest_dl_extractor as pde  # noqa: E402


class TestPinterestUrlParsing(unittest.TestCase):
    def test_is_pinterest_url_variants(self):
        valid = [
            "https://www.pinterest.com/pin/123456789012345678/",
            "https://in.pinterest.com/pin/aesthetic-art--987654321098765432/",
            "https://pinterest.co.uk/username/my-board/",
            "https://www.pinterest.de/user/board/section-name/",
            "https://pin.it/3AbCdEf",
            "https://i.pinimg.com/736x/ab/cd/ef/abcdef0123456789abcdef0123456789.jpg",
            "https://v1.pinimg.com/videos/mc/720p/ab/cd/ef/abcdef0123456789abcdef0123456789.mp4",
        ]
        for u in valid:
            self.assertTrue(pde.is_pinterest_url(u), f"Expected True for {u}")

        invalid = [
            "https://example.com/pinterest.com",
            "https://instagram.com/p/Cx12345/",
            "",
            None,
        ]
        for u in invalid:
            self.assertFalse(pde.is_pinterest_url(u), f"Expected False for {u}")

    def test_parse_pinterest_url_kinds(self):
        pin = pde.parse_pinterest_url("https://in.pinterest.com/pin/cool-poster--123456789012345678/")
        self.assertEqual(pin["kind"], "pin")
        self.assertEqual(pin["pin_id"], "123456789012345678")

        short = pde.parse_pinterest_url("https://pin.it/7XyZ123")
        self.assertEqual(short["kind"], "short")
        self.assertEqual(short["code"], "7XyZ123")

        board = pde.parse_pinterest_url("https://www.pinterest.com/artist/%E6%97%A5%E6%9C%AC%E3%82%A2%E3%83%BC%E3%83%88/")
        self.assertEqual(board["kind"], "board")
        self.assertEqual(board["username"], "artist")
        self.assertEqual(board["board_slug"], "日本アート")

        section = pde.parse_pinterest_url("https://www.pinterest.com/artist/concept-art/sci-fi/")
        self.assertEqual(section["kind"], "section")
        self.assertEqual(section["section_slug"], "sci-fi")

        search = pde.parse_pinterest_url("https://www.pinterest.com/search/pins/?q=minimalist+architecture")
        self.assertEqual(search["kind"], "search")
        self.assertEqual(search["query"], "minimalist architecture")

        cdn_vid = pde.parse_pinterest_url("https://v1.pinimg.com/videos/mc/720p/aa/bb/cc/aabbccddeeff00112233445566778899.mp4")
        self.assertEqual(cdn_vid["kind"], "cdn")
        self.assertTrue(cdn_vid["is_video"])

    def test_upgrade_pinimg_image_url_preserves_videos_and_originals(self):
        h = "f732ca2684db4685ff263b65cb466ff4"
        self.assertEqual(
            pde.upgrade_pinimg_image_url(f"https://i.pinimg.com/236x/f7/32/ca/{h}.jpg"),
            f"https://i.pinimg.com/originals/f7/32/ca/{h}.jpg",
        )
        # Native original .webp and .png must be preserved
        self.assertEqual(
            pde.upgrade_pinimg_image_url(f"https://i.pinimg.com/originals/f7/32/ca/{h}.webp"),
            f"https://i.pinimg.com/originals/f7/32/ca/{h}.webp",
        )
        # Video URLs MUST NEVER be rewritten to i.pinimg.com/originals
        vid = f"https://v1.pinimg.com/videos/mc/720p/f7/32/ca/{h}.mp4"
        self.assertEqual(pde.upgrade_pinimg_image_url(vid), vid)
        hls = f"https://v1.pinimg.com/videos/mc/hls/f7/32/ca/{h}.m3u8"
        self.assertEqual(pde.upgrade_pinimg_image_url(hls), hls)


class TestPinterestParsers(unittest.TestCase):
    def test_parse_pin_data_video_prefers_mp4_over_hls(self):
        pin = {
            "id": "555111222333",
            "title": "Mountain Timelapse",
            "images": {
                "orig": {"url": "https://i.pinimg.com/originals/aa/bb/cc/aabbccddeeff00112233445566778899.jpg", "width": 1080, "height": 1920}
            },
            "videos": {
                "video_list": {
                    "V_HLSV3_WEB": {
                        "url": "https://v1.pinimg.com/videos/mc/hls/aa/bb/cc/aabbccddeeff00112233445566778899.m3u8",
                        "width": 720,
                        "height": 1280,
                    },
                    "V_720P": {
                        "url": "https://v1.pinimg.com/videos/mc/720p/aa/bb/cc/aabbccddeeff00112233445566778899.mp4",
                        "width": 720,
                        "height": 1280,
                    },
                }
            },
        }
        items, title, _, preview = pde.parse_pin_data(pin, page_url="https://www.pinterest.com/pin/555111222333/")
        self.assertEqual(len(items), 1)
        self.assertTrue(items[0]["isVideo"])
        self.assertFalse(items[0]["isStream"])
        self.assertEqual(items[0]["format"], "MP4")
        self.assertTrue(items[0]["url"].endswith(".mp4"))
        self.assertEqual(items[0]["thumbnailUrl"], preview)
        self.assertEqual(title, "Mountain Timelapse")

    def test_parse_pin_data_story_pin_multi_page(self):
        pin = {
            "id": "777888999000",
            "title": "Idea Pin Recipe",
            "story_pin_data": {
                "pages": [
                    {
                        "blocks": [
                            {
                                "video": {
                                    "video_list": {
                                        "V_EXP7": {
                                            "url": "https://v1.pinimg.com/videos/iht/expMp4/11/22/33/11223344556677889900aabbccddeeff.mp4",
                                            "width": 720,
                                            "height": 1280,
                                        }
                                    }
                                }
                            }
                        ]
                    },
                    {
                        "blocks": [
                            {
                                "image": {
                                    "images": {
                                        "orig": {
                                            "url": "https://i.pinimg.com/originals/44/55/66/44556677889900aabbccddeeff112233.png",
                                            "width": 1080,
                                            "height": 1920,
                                        }
                                    }
                                }
                            }
                        ]
                    },
                ]
            },
        }
        items, _, _, _ = pde.parse_pin_data(pin)
        self.assertEqual(len(items), 2)
        self.assertTrue(items[0]["isVideo"])
        self.assertFalse(items[1]["isVideo"])
        self.assertEqual(items[1]["format"], "PNG")

    def test_parse_pinterest_html_pws_data(self):
        pws_blob = {
            "props": {
                "initialReduxState": {
                    "pins": {
                        "999000111222": {
                            "id": "999000111222",
                            "title": "Architectural Sketch",
                            "images": {
                                "orig": {
                                    "url": "https://i.pinimg.com/originals/12/34/56/1234567890abcdef1234567890abcdef.jpg",
                                    "width": 1200,
                                    "height": 1600,
                                }
                            },
                        }
                    }
                }
            }
        }
        html_doc = f'<html><body><script id="__PWS_DATA__" type="application/json">{json.dumps(pws_blob)}</script></body></html>'
        items, title, _, _ = pde.parse_pinterest_html(html_doc, pin_id="999000111222")
        self.assertEqual(len(items), 1)
        self.assertEqual(title, "Architectural Sketch")
        self.assertIn("/originals/12/34/56/", items[0]["url"])

    def test_parse_pinterest_rss(self):
        rss = """<?xml version="1.0" encoding="utf-8"?>
        <rss version="2.0"><channel>
          <title>Design Board</title>
          <item>
            <title>Poster 1</title>
            <link>https://www.pinterest.com/pin/111222333/</link>
            <description><![CDATA[<a href="https://www.pinterest.com/pin/111222333/"><img src="https://i.pinimg.com/236x/aa/bb/cc/aabbccddeeff00112233445566778899.jpg"></a>]]></description>
          </item>
        </channel></rss>"""
        items, title, _, _ = pde.parse_pinterest_rss(rss)
        self.assertEqual(title, "Design Board")
        self.assertEqual(len(items), 1)
        self.assertEqual(
            items[0]["url"],
            "https://i.pinimg.com/originals/aa/bb/cc/aabbccddeeff00112233445566778899.jpg",
        )


class TestPinterestLibraryAndRunnerIntegration(unittest.TestCase):
    def test_tier1_pinterest_dl_library_mocked(self):
        fake_media = SimpleNamespace(
            id="444555666",
            src="https://i.pinimg.com/736x/aa/bb/cc/aabbccddeeff00112233445566778899.jpg",
            alt="Ocean Waves",
            origin="https://www.pinterest.com/pin/444555666/",
            resolution=(1080, 1920),
            media_stream=SimpleNamespace(
                url="https://v1.pinimg.com/videos/mc/hls/aa/bb/cc/aabbccddeeff00112233445566778899.m3u8",
                width=720,
                height=1280,
            ),
        )

        class FakeScraper:
            def with_cookies(self, cookies):
                self.cookies = cookies
                return self

            def scrape(self, url, num=1, **kwargs):
                return [fake_media]

        class FakePinterestDL:
            @staticmethod
            def with_api(timeout=25, verbose=False):
                return FakeScraper()

        with mock.patch.object(pde, "PINTEREST_DL_LIB_AVAILABLE", True), mock.patch.object(
            pde, "PinterestDL", FakePinterestDL
        ):
            res_json = pde.extract_pinterest(
                "https://www.pinterest.com/pin/444555666/",
                json.dumps({"cookies": "_pinterest_sess=abc123; csrftoken=tok"}),
            )
            res = json.loads(res_json)
            self.assertTrue(res["success"])
            self.assertEqual(res["engine"], "pinterest-dl")
            self.assertEqual(len(res["items"]), 1)
            self.assertTrue(res["items"][0]["isVideo"])
            self.assertTrue(res["items"][0]["isStream"])
            self.assertEqual(res["items"][0]["format"], "M3U8")
            self.assertIn("/originals/aa/bb/cc/", res["items"][0]["thumbnailUrl"])

    def test_unified_extract_media_dispatches_to_pinterest_extractor(self):
        expected = {
            "success": True,
            "engine": "pinterest-dl",
            "items": [{"url": "https://v1.pinimg.com/video.mp4", "isVideo": True}],
        }

        class FakePinterestRouter:
            @staticmethod
            def is_pinterest_url(url):
                return "pinterest.com" in url or "pin.it" in url

        with mock.patch.object(gallery_dl_runner, "PINTEREST_DL_AVAILABLE", True), \
             mock.patch.object(gallery_dl_runner, "_pinterest_extractor", FakePinterestRouter), \
             mock.patch.object(gallery_dl_runner, "extract_pinterest", return_value=json.dumps(expected)) as extract:
            result = json.loads(gallery_dl_runner.extract_media("https://www.pinterest.com/pin/12345/"))

        self.assertTrue(result["success"])
        self.assertEqual(result["engine"], "pinterest-dl")
        extract.assert_called_once()

    def test_gallery_dl_runner_wiring(self):
        self.assertTrue(gallery_dl_runner.is_pinterest_dl_available())
        status = json.loads(gallery_dl_runner.get_engine_status())
        self.assertTrue(status["pinterest_dl_available"])
        self.assertIn("pinterest-dl", status["engine"])

        sup = json.loads(gallery_dl_runner.check_support("https://pin.it/abc1234"))
        self.assertTrue(sup["supported"])
        self.assertEqual(sup["engine"], "pinterest-dl")
        self.assertEqual(sup["category"], "pinterest")


if __name__ == "__main__":
    unittest.main()
