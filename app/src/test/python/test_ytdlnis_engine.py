import json
import os
import sys
import unittest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "../../main/python")))
import ytdlnis_engine


class TestYtdlnisEngine(unittest.TestCase):
    def test_parse_timestamp_and_format(self):
        self.assertAlmostEqual(ytdlnis_engine.parse_timestamp_to_seconds("01:30"), 90.0)
        self.assertAlmostEqual(ytdlnis_engine.parse_timestamp_to_seconds("01:02:03.5"), 3723.5)
        self.assertAlmostEqual(ytdlnis_engine.parse_timestamp_to_seconds("45"), 45.0)
        self.assertIsNone(ytdlnis_engine.parse_timestamp_to_seconds("invalid"))
        self.assertEqual(ytdlnis_engine.format_seconds_to_timestamp(90), "01:30")
        self.assertEqual(ytdlnis_engine.format_seconds_to_timestamp(3661), "01:01:01")

    def test_parse_bulk_inputs(self):
        raw = """
        # Comment line
        https://www.youtube.com/watch?v=dQw4w9WgXcQ
        https://www.youtube.com/watch?v=dQw4w9WgXcQ
        lofi hip hop radio
        // another comment
        https://vimeo.com/123456789.
        """
        parsed = json.loads(ytdlnis_engine.parse_bulk_inputs(raw))
        self.assertEqual(parsed["count"], 3)
        self.assertEqual(parsed["items"][0]["kind"], "url")
        self.assertEqual(parsed["items"][0]["query"], "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        self.assertEqual(parsed["items"][1]["kind"], "search")
        self.assertEqual(parsed["items"][1]["query"], "ytsearch1:lofi hip hop radio")
        self.assertEqual(parsed["items"][2]["query"], "https://vimeo.com/123456789")

    def test_render_filename_template(self):
        meta = json.dumps({
            "title": "My: Great/Video?",
            "uploader": "Amre Channel",
            "ext": "mp4",
            "upload_date": "20261119",
        })
        rendered = ytdlnis_engine.render_filename_template("%(upload_date)s - %(uploader)s - %(title)s.%(ext)s", meta)
        self.assertEqual(rendered, "20261119 - Amre Channel - My_ Great_Video.mp4")

    def test_build_ytdlp_options_multi_audio_and_po_token(self):
        cfg = {
            "type": "video",
            "format_id": "137",
            "audio_format_ids": ["140", "251"],
            "container": "mkv",
            "filename_template": "%(title)s",
            "download_subtitles": True,
            "subtitle_languages": ["en", "bn"],
            "embed_thumbnail": True,
            "po_token": "abc123token",
            "sponsorblock_action": "remove",
            "sponsorblock_categories": ["sponsor", "intro"],
        }
        opts = ytdlnis_engine.build_ytdlp_options(cfg, "/tmp/out")
        self.assertEqual(opts["format"], "137+140+251/best")
        self.assertTrue(opts["allow_multiple_audio_streams"])
        self.assertEqual(opts["merge_output_format"], "mkv")
        self.assertTrue(opts["writesubtitles"])
        self.assertEqual(opts["subtitleslangs"], ["en", "bn"])
        self.assertTrue(opts["writethumbnail"])
        self.assertEqual(opts["extractor_args"]["youtube"]["po_token"], ["web+abc123token"])
        self.assertEqual(opts["sponsorblock_action"], "remove")

    def test_build_ytdlp_options_video_only_respects_8k_cap(self):
        opts = ytdlnis_engine.build_ytdlp_options(
            {"type": "video", "format_id": "best", "video_only": True, "max_height": 4320},
            "/tmp/video-only",
        )
        self.assertEqual(opts["format"], "bestvideo[height<=4320]/bestvideo/best")

    def test_build_ytdlp_options_prefers_separate_high_resolution_video_and_audio(self):
        opts = ytdlnis_engine.build_ytdlp_options(
            {"type": "video", "format_id": "best", "max_height": 4320},
            "/tmp/video-audio",
        )
        self.assertEqual(opts["format"], "bestvideo[height<=4320]+bestaudio/best[height<=4320]/best")

    def test_build_ytdlp_options_audio_mode(self):
        cfg = {
            "type": "audio",
            "format_id": "best",
            "container": "mp3",
            "filename_template": "%(title)s.%(ext)s",
        }
        opts = ytdlnis_engine.build_ytdlp_options(cfg, "/tmp/audio")
        self.assertEqual(opts["format"], "bestaudio/best")
        self.assertNotIn("merge_output_format", opts)

    def test_adjust_lrc_timing(self):
        lrc = "[00:01.00]Line one\n[00:59.50]Line two"
        shifted_fwd = ytdlnis_engine.adjust_lrc_timing(lrc, 1000)
        self.assertIn("[00:02.00]Line one", shifted_fwd)
        self.assertIn("[01:00.50]Line two", shifted_fwd)
        shifted_back = ytdlnis_engine.adjust_lrc_timing(lrc, -5000)
        self.assertIn("[00:00.00]Line one", shifted_back)

    def test_terminal_help_and_status(self):
        res_help = json.loads(ytdlnis_engine.run_terminal_command("help"))
        self.assertTrue(res_help["success"])
        self.assertIn("Emre Gallery YTDLnis Terminal", res_help["output"])

        res_status = json.loads(ytdlnis_engine.run_terminal_command("status"))
        self.assertTrue(res_status["success"])
        self.assertIn("engine_version", res_status["output"])



    def test_tls_enabled_and_continuedl_resume(self):
        opts = ytdlnis_engine.build_ytdlp_options(
            {"type": "video", "retries": 5},
            output_dir="/tmp/out"
        )
        self.assertNotIn("nocheckcertificate", opts)
        self.assertTrue(opts.get("continuedl"))
        self.assertLessEqual(opts.get("retries", 99), 2)

    def test_cookiefile_normalizes_youtube_domains_and_preserves_netscape(self):
        import os
        import gallery_dl_runner
        path = gallery_dl_runner._prepare_cookiefile("SID=abc; SAPISID=xyz", "https://youtu.be/dQw4w9WgXcQ")
        try:
            with open(path, encoding="utf-8") as fh:
                content = fh.read()
            self.assertIn(".youtube.com\tTRUE\t/\tTRUE", content)
            self.assertNotIn(".youtu.be", content)
        finally:
            if path and os.path.exists(path):
                os.unlink(path)

        netscape_in = "# Netscape HTTP Cookie File\n.google.com\tTRUE\t/\tTRUE\t2147483647\tSID\t123"
        path2 = gallery_dl_runner._prepare_cookiefile(netscape_in, "https://www.youtube.com/watch?v=123")
        try:
            with open(path2, encoding="utf-8") as fh2:
                content2 = fh2.read()
            self.assertIn(".google.com\tTRUE\t/\tTRUE\t2147483647\tSID\t123", content2)
        finally:
            if path2 and os.path.exists(path2):
                os.unlink(path2)

    def test_android_subproc_guard_blocks_posixsubprocess_fork_exec(self):
        import subprocess
        ytdlnis_engine.install_android_subproc_guard()
        self.assertIsNotNone(sys.modules.get("_posixsubprocess"))
        with self.assertRaises(FileNotFoundError):
            subprocess.Popen(["ffmpeg", "-version"])
        with self.assertRaises(FileNotFoundError):
            sys.modules["_posixsubprocess"].fork_exec()


if __name__ == "__main__":
    unittest.main()

