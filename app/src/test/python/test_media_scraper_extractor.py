import json
import os
import sys
import tempfile
import unittest
from unittest.mock import patch

PYTHON_SRC = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..", "main", "python")
)
if PYTHON_SRC not in sys.path:
    sys.path.insert(0, PYTHON_SRC)

import media_scraper_extractor as mse
import gallery_dl_runner as runner


class TestMediaScraperExtractor(unittest.TestCase):

    def test_sanitize_filename_windows_and_android_safe(self):
        self.assertEqual(mse.sanitize_filename('My:Photo*"Test"?.jpg   '), "My_Photo_Test_.jpg")
        self.assertEqual(mse.sanitize_filename("trailing.dots... "), "trailing.dots")
        self.assertEqual(mse.sanitize_filename("line1\nline2\t<bad>"), "line1_line2_bad_")
        self.assertEqual(mse.sanitize_filename("   ...  ", fallback="untitled"), "untitled")

    def test_guess_media_type_and_is_media_url(self):
        self.assertTrue(mse.is_media_url("https://example.com/photos/sunset.jpg"))
        self.assertTrue(mse.is_media_url("https://example.com/clip.mp4?token=1"))
        self.assertTrue(mse.is_media_url("https://cdn.example.com/live/master.m3u8"))
        self.assertTrue(mse.is_media_url("https://cdn.example.com/audio/track.opus"))
        self.assertFalse(mse.is_media_url("https://example.com/about.html"))
        self.assertFalse(mse.is_media_url("javascript:void(0)"))

        cat, mime, ext = mse.guess_media_type("https://cdn.example.com/stream/index.m3u8")
        self.assertEqual(cat, "stream")
        self.assertEqual(mime, "application/x-mpegURL")
        self.assertEqual(ext, ".m3u8")

    def test_normalize_crawl_url(self):
        self.assertEqual(
            mse.normalize_crawl_url("/gallery/page2#top", "http://Example.com/index.html"),
            "https://example.com/gallery/page2",
        )
        self.assertIsNone(mse.normalize_crawl_url("mailto:test@example.com", "https://example.com"))
        self.assertIsNone(mse.normalize_crawl_url("blob:https://example.com/123", "https://example.com"))

    def test_detect_captcha(self):
        self.assertTrue(mse.detect_captcha("<html><body>Please solve the CAPTCHA to continue</body></html>"))
        self.assertTrue(mse.detect_captcha("<div class='cf-turnstile'></div>"))
        self.assertFalse(mse.detect_captcha("<html><body><h1>Welcome to my photo blog</h1></body></html>"))

    def test_select_best_m3u8_variant(self):
        manifest = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
low/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
hd/1080p.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720
mid/720p.m3u8
"""
        best = mse.select_best_m3u8_variant(manifest, "https://video.twimg.com/ext_tw_video/master.m3u8")
        self.assertEqual(best, "https://video.twimg.com/ext_tw_video/hd/1080p.m3u8")

    def test_scrape_recursive_bfs_same_domain_and_sitemap(self):
        pages = {
            "https://photos.example.com/": {
                "ok": True,
                "url": "https://photos.example.com/",
                "status": 200,
                "content_type": "text/html",
                "text": """
                    <html><head><title>Root Gallery</title></head><body>
                    <img src="/img/cover.jpg" alt="Cover Shot" />
                    <a href="/album/1">Album 1</a>
                    <a href="https://external.other.com/page">External</a>
                    </body></html>
                """,
            },
            "https://photos.example.com/album/1": {
                "ok": True,
                "url": "https://photos.example.com/album/1",
                "status": 200,
                "content_type": "text/html",
                "text": """
                    <html><head><title>Album 1</title></head><body>
                    <video src="/vid/clip.mp4" poster="/img/poster.png"></video>
                    <a href="/img/fullsize.png">Full</a>
                    <a href="/album/captcha">Blocked</a>
                    </body></html>
                """,
            },
            "https://photos.example.com/album/captcha": {
                "ok": True,
                "url": "https://photos.example.com/album/captcha",
                "status": 200,
                "content_type": "text/html",
                "text": "<html><body>Please complete the g-recaptcha check</body></html>",
            },
        }

        def fake_get(url, **kwargs):
            return pages.get(url, {"ok": False, "url": url, "status": 404, "content_type": "", "text": "", "error": "404"})

        with tempfile.TemporaryDirectory() as tmpdir:
            sitemap_file = os.path.join(tmpdir, "sitemap.txt")
            with patch.object(mse, "_http_get_text", side_effect=fake_get):
                raw = mse.scrape_recursive(
                    "http://photos.example.com/",
                    json.dumps({
                        "maxDepth": 2,
                        "maxPages": 10,
                        "sameDomainOnly": True,
                        "scrollPause": 0,
                        "sitemapPath": sitemap_file,
                    }),
                )
            result = json.loads(raw)
            self.assertTrue(result["success"])
            self.assertEqual(result["pageTitle"], "Root Gallery")
            self.assertEqual(result["pagesCrawled"], 3)
            self.assertTrue(result["captchaDetected"])
            self.assertEqual(result["captchaUrl"], "https://photos.example.com/album/captcha")
            urls = {item["url"] for item in result["items"]}
            self.assertIn("https://photos.example.com/img/cover.jpg", urls)
            self.assertIn("https://photos.example.com/vid/clip.mp4", urls)
            self.assertIn("https://photos.example.com/img/fullsize.png", urls)
            # External domain must not be visited
            self.assertNotIn("https://external.other.com/page", result["sitemap"])
            # Sitemap file written
            self.assertTrue(os.path.exists(sitemap_file))
            with open(sitemap_file, "r", encoding="utf-8") as fh:
                lines = [ln.strip() for ln in fh.readlines() if ln.strip()]
            self.assertEqual(len(lines), 3)

    def test_extract_tumblr_pixiv_twitter_facebook(self):
        # Tumblr
        tumblr_payload = 'var tumblr_api_read = {"tumblelog":{"title":"Art Blog"},"posts":[{"id":"101","url":"https://demo.tumblr.com/post/101","photo-url-1280":"https://64.media.tumblr.com/pic1.jpg"}]};'
        with patch.object(mse, "_http_get_text", return_value={"ok": True, "url": "https://demo.tumblr.com/api/read/json", "status": 200, "content_type": "application/json", "text": tumblr_payload}):
            res = json.loads(mse.extract_tumblr("https://demo.tumblr.com/"))
            self.assertTrue(res["success"])
            self.assertEqual(res["items"][0]["url"], "https://64.media.tumblr.com/pic1.jpg")

        # Pixiv
        pixiv_payload = json.dumps({"error": False, "body": [{"urls": {"original": "https://i.pximg.net/img-original/img/123_p0.png", "small": "https://i.pximg.net/small/123_p0.jpg"}}]})
        with patch.object(mse, "_http_get_text", return_value={"ok": True, "url": "https://www.pixiv.net/ajax/illust/123/pages", "status": 200, "content_type": "application/json", "text": pixiv_payload}):
            res = json.loads(mse.extract_pixiv("https://www.pixiv.net/en/artworks/123"))
            self.assertTrue(res["success"])
            self.assertEqual(res["items"][0]["headers"]["Referer"], "https://www.pixiv.net/")

        # Twitter syndication
        tw_payload = json.dumps({
            "user": {"screen_name": "nasa"},
            "text": "Launch clip",
            "video": {
                "poster": "https://pbs.twimg.com/poster.jpg",
                "variants": [
                    {"type": "video/mp4", "bitrate": 832000, "src": "https://video.twimg.com/low.mp4"},
                    {"type": "video/mp4", "bitrate": 2176000, "src": "https://video.twimg.com/hd.mp4"},
                ],
            },
        })
        with patch.object(mse, "_http_get_text", return_value={"ok": True, "url": "https://cdn.syndication.twimg.com/tweet-result?id=999", "status": 200, "content_type": "application/json", "text": tw_payload}):
            res = json.loads(mse.extract_twitter_video("https://x.com/nasa/status/999"))
            self.assertTrue(res["success"])
            self.assertEqual(res["items"][0]["url"], "https://video.twimg.com/hd.mp4")

        # Facebook public HD video
        fb_html = '<html><head><title>FB Clip</title></head><body>"browser_native_hd_url":"https:\\/\\/video.fbcdn.net\\/v\\/clip_hd.mp4?oe=1"</body></html>'
        with patch.object(mse, "_http_get_text", return_value={"ok": True, "url": "https://www.facebook.com/watch/?v=123", "status": 200, "content_type": "text/html", "text": fb_html}):
            res = json.loads(mse.extract_facebook("https://www.facebook.com/watch/?v=123"))
            self.assertTrue(res["success"])
            self.assertEqual(res["items"][0]["url"], "https://video.fbcdn.net/v/clip_hd.mp4?oe=1")

    def test_runner_wiring(self):
        self.assertTrue(runner.is_media_scraper_available())
        status = json.loads(runner.get_engine_status())
        self.assertTrue(status["media_scraper_available"])
        self.assertEqual(status["media_scraper_version"], mse.VERSION)


if __name__ == "__main__":
    unittest.main()
