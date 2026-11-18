import json
import os
import sys
import unittest
from unittest.mock import patch

PYTHON_SRC = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..", "main", "python")
)
if PYTHON_SRC not in sys.path:
    sys.path.insert(0, PYTHON_SRC)

import media_downloader_extractor as mde
import gallery_dl_runner as runner


class TestMediaDownloaderExtractor(unittest.TestCase):

    def test_detect_all_eight_platforms_and_x_spaces(self):
        cases = {
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ": "youtube",
            "https://youtu.be/dQw4w9WgXcQ": "youtube",
            "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT": "spotify",
            "https://spotify.link/abc123": "spotify",
            "https://www.tiktok.com/@user/video/1234567890": "tiktok",
            "https://vm.tiktok.com/ZM8xyz/": "tiktok",
            "https://www.instagram.com/reel/Cxyz123/": "instagram",
            "https://x.com/i/spaces/1vAxRvQAbCdKl": "twitter_space",
            "https://twitter.com/user/status/12345": "twitter",
            "https://www.pinterest.com/pin/123456789/": "pinterest",
            "https://pin.it/abc123": "pinterest",
            "https://soundcloud.com/artist/track-name": "soundcloud",
            "https://play.radiojavan.com/song/test-song": "radiojavan",
            "https://rj.app/m/abcd1234": "radiojavan",
            "https://example.org/page": "unknown",
        }
        for url, expected in cases.items():
            self.assertEqual(mde.detect_platform(url), expected, f"Failed for {url}")

    def test_spotify_url_and_embed_parsing(self):
        parsed = mde.parse_spotify_url("https://open.spotify.com/album/1A2B3C4D")
        self.assertEqual(parsed["kind"], "album")
        self.assertEqual(parsed["id"], "1A2B3C4D")

        embed_html = """
        <html><head></head><body>
        <script id="__NEXT_DATA__" type="application/json">
        {"props":{"pageProps":{"state":{"data":{"entity":{
            "name":"Test Album",
            "visualIdentity":{"image":[{"url":"https://i.scdn.co/cover.jpg"}]},
            "trackList":[
                {"title":"Song One","subtitle":"Artist A","duration":210000},
                {"title":"Song Two","subtitle":"Artist B","duration":185000}
            ]
        }}}}}}
        </script></body></html>
        """
        title, thumb, tracks = mde._parse_spotify_embed_tracks(embed_html, "album")
        self.assertEqual(title, "Test Album")
        self.assertEqual(thumb, "https://i.scdn.co/cover.jpg")
        self.assertEqual(len(tracks), 2)
        self.assertEqual(tracks[0]["title"], "Song One")
        self.assertEqual(tracks[0]["artist"], "Artist A")

    def test_spotify_extraction_with_youtube_matches(self):
        fake_embed = """
        <script id="__NEXT_DATA__">
        {"props":{"pageProps":{"state":{"data":{"entity":{
            "name":"Blinding Lights",
            "artists":[{"name":"The Weeknd"}],
            "visualIdentity":{"image":[{"url":"https://i.scdn.co/blinding.jpg"}]}
        }}}}}}
        </script>
        """
        fake_matches = [
            {
                "rank": 1,
                "url": "https://rr1.googlevideo.com/audio1",
                "ext": "m4a",
                "yt_title": "The Weeknd - Blinding Lights (Official Audio)",
                "yt_url": "https://www.youtube.com/watch?v=fHI8X4OXluQ",
                "uploader": "The Weeknd",
                "duration": 200,
                "thumbnail": "https://i.ytimg.com/1.jpg",
                "filesize": 3200000,
            },
            {
                "rank": 2,
                "url": "https://rr1.googlevideo.com/audio2",
                "ext": "m4a",
                "yt_title": "The Weeknd - Blinding Lights (Lyrics)",
                "yt_url": "https://www.youtube.com/watch?v=abc12345678",
                "uploader": "Lyrics Channel",
                "duration": 202,
                "thumbnail": "https://i.ytimg.com/2.jpg",
                "filesize": 3100000,
            },
        ]

        def fake_get(url, **kwargs):
            if "oembed" in url:
                return {"status": 200, "final_url": url, "body": json.dumps({"title": "Blinding Lights", "thumbnail_url": "https://i.scdn.co/blinding.jpg"})}
            return {"status": 200, "final_url": url, "body": fake_embed}

        with patch.object(mde, "_http_get", side_effect=fake_get), \
             patch.object(mde, "_search_youtube_audio_matches", return_value=fake_matches):
            raw = mde.extract_spotify("https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b")
            res = json.loads(raw)
            self.assertTrue(res["success"])
            self.assertEqual(len(res["items"]), 2)
            self.assertEqual(res["items"][0]["category"], "AUDIO")
            self.assertEqual(res["items"][0]["metadata"]["youtube_match_rank"], "1")
            self.assertIn("Match #2", res["items"][1]["title"])

    def test_radiojavan_url_parsing_and_5_host_cdn_fallback(self):
        self.assertEqual(mde.parse_radiojavan_url("https://rj.app/m/xyz123")["kind"], "song")
        self.assertEqual(mde.parse_radiojavan_url("https://rj.app/v/vid999")["kind"], "video")
        self.assertEqual(mde.parse_radiojavan_url("https://play.radiojavan.com/song/Shadmehr-Aghili-Test")["slug"], "Shadmehr-Aghili-Test")
        self.assertEqual(len(mde.RADIOJAVAN_CDN_HOSTS), 5)

        called_hosts = []

        def fake_get(url, method="GET", **kwargs):
            if method == "GET":
                return {
                    "status": 200,
                    "final_url": "https://play.radiojavan.com/song/Test-Track",
                    "body": '<html><head><meta property="og:title" content="Test Track"></head><body></body></html>',
                }
            called_hosts.append(url)
            # Fail first 2 CDN hosts, succeed on 3rd host (host2.rjassets.com)
            if "host2.rjassets.com" in url:
                return {"status": 200, "final_url": url, "body": "", "content_type": "audio/mpeg", "content_length": 5432100}
            raise OSError("CDN host timeout")

        with patch.object(mde, "_http_get", side_effect=fake_get):
            raw = mde.extract_radiojavan("https://rj.app/m/test123")
            res = json.loads(raw)
            self.assertTrue(res["success"])
            self.assertEqual(len(res["items"]), 1)
            self.assertIn("host2.rjassets.com", res["items"][0]["url"])
            self.assertEqual(res["items"][0]["category"], "AUDIO")
            self.assertGreaterEqual(len(called_hosts), 3)

    def test_x_space_graphql_and_replay_disabled_diagnostic(self):
        self.assertEqual(mde.parse_x_space_id("https://x.com/i/spaces/1vAxRvQAbCdKl"), "1vAxRvQAbCdKl")

        def fake_post(url, **kwargs):
            return {"status": 200, "body": json.dumps({"guest_token": "999999"})}

        def fake_get_live(url, **kwargs):
            if "AudioSpaceById" in url:
                return {
                    "status": 200,
                    "body": json.dumps({
                        "data": {"audioSpace": {"metadata": {
                            "title": "Dev Talk Space",
                            "state": "Running",
                            "media_key": "28_12345",
                            "is_space_available_for_replay": True,
                        }}}
                    }),
                }
            return {
                "status": 200,
                "body": json.dumps({"source": {"location": "https://prod-fastly.video.pscp.tv/playlist.m3u8"}}),
            }

        with patch.object(mde, "_http_post", side_effect=fake_post), \
             patch.object(mde, "_http_get", side_effect=fake_get_live):
            res = json.loads(mde.extract_x_space("https://x.com/i/spaces/1vAxRvQAbCdKl"))
            self.assertTrue(res["success"])
            self.assertEqual(res["items"][0]["category"], "STREAMS")
            self.assertTrue(res["items"][0]["isAudio"])
            self.assertTrue(res["items"][0]["isStream"])
            self.assertTrue(res["items"][0]["url"].endswith(".m3u8"))

    def test_soundcloud_go_plus_preview_detection(self):
        self.assertTrue(mde.check_soundcloud_preview_only({"format_id": "hls_mp3_1_0_preview", "url": "https://cf-hls-media.sndcdn.com/preview/x"}))
        self.assertFalse(mde.check_soundcloud_preview_only({"format_id": "hls_opus_64", "url": "https://cf-hls-media.sndcdn.com/playlist.m3u8"}))

    def test_runner_check_support_routes_new_platforms(self):
        self.assertTrue(runner.is_media_downloader_available())
        sp = json.loads(runner.check_support("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"))
        self.assertTrue(sp["supported"])
        self.assertEqual(sp["engine"], "spotify")

        rj = json.loads(runner.check_support("https://rj.app/m/abc123"))
        self.assertTrue(rj["supported"])
        self.assertEqual(rj["engine"], "radiojavan")

        xs = json.loads(runner.check_support("https://x.com/i/spaces/1vAxRvQAbCdKl"))
        self.assertTrue(xs["supported"])
        self.assertEqual(xs["engine"], "x_spaces")


if __name__ == "__main__":
    unittest.main()
