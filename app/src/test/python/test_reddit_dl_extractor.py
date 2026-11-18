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

import reddit_dl_extractor as rdl
import gallery_dl_runner as runner


SAMPLE_DASH_MPD = """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
  <Period>
    <AdaptationSet contentType="video" mimeType="video/mp4">
      <Representation id="1" bandwidth="800000" width="640" height="360">
        <BaseURL>DASH_360.mp4</BaseURL>
      </Representation>
      <Representation id="2" bandwidth="4500000" width="1920" height="1080">
        <BaseURL>DASH_1080.mp4</BaseURL>
      </Representation>
      <Representation id="3" bandwidth="2200000" width="1280" height="720">
        <BaseURL>DASH_720.mp4</BaseURL>
      </Representation>
    </AdaptationSet>
    <AdaptationSet contentType="audio" mimeType="audio/mp4">
      <Representation id="4" bandwidth="64000">
        <BaseURL>DASH_AUDIO_64.mp4</BaseURL>
      </Representation>
      <Representation id="5" bandwidth="128000">
        <BaseURL>DASH_AUDIO_128.mp4</BaseURL>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>
"""


class TestRedditDlExtractor(unittest.TestCase):
    def test_shorthand_and_url_parsing(self):
        self.assertTrue(rdl.is_reddit_url("r/funny"))
        self.assertTrue(rdl.is_reddit_url("u/johndoe"))
        self.assertTrue(rdl.is_reddit_url("p/1n0dpkp"))
        self.assertTrue(rdl.is_reddit_url("my/saved"))
        self.assertTrue(rdl.is_reddit_url("my/joined"))
        self.assertTrue(rdl.is_reddit_url("my/following"))
        self.assertTrue(rdl.is_reddit_url("https://www.redgifs.com/watch/happycat"))
        self.assertFalse(rdl.is_reddit_url("https://example.com/r/funny"))

        self.assertEqual(rdl.parse_reddit_target("r/DataHoarder")["kind"], "subreddit")
        self.assertEqual(rdl.parse_reddit_target("u/patrickkfkan")["kind"], "user")
        self.assertEqual(rdl.parse_reddit_target("p/1n0dpkp")["post_id"], "1n0dpkp")
        self.assertEqual(rdl.parse_reddit_target("my/saved")["kind"], "my_saved")
        self.assertEqual(rdl.parse_reddit_target("my/joined")["kind"], "my_joined")
        self.assertEqual(rdl.parse_reddit_target("my/following")["kind"], "my_following")
        self.assertEqual(
            rdl.parse_reddit_target("https://www.reddit.com/r/pics/comments/abc123/my_slug/")["post_id"],
            "abc123",
        )
        self.assertEqual(
            rdl.parse_reddit_target("https://redd.it/xyz789")["post_id"],
            "xyz789",
        )
        self.assertEqual(
            rdl.parse_reddit_target("https://www.reddit.com/gallery/gal999")["post_id"],
            "gal999",
        )
        self.assertEqual(
            rdl.parse_reddit_target("https://www.reddit.com/r/pics/s/ShareToken123")["kind"],
            "share",
        )

    def test_dash_mpd_selects_highest_resolution_and_audio(self):
        parsed = rdl._parse_dash_mpd(
            SAMPLE_DASH_MPD, "https://v.redd.it/abc12345/DASHPlaylist.mpd?a=1"
        )
        self.assertEqual(parsed["video_url"], "https://v.redd.it/abc12345/DASH_1080.mp4")
        self.assertEqual(parsed["audio_url"], "https://v.redd.it/abc12345/DASH_AUDIO_128.mp4")
        self.assertEqual(parsed["width"], 1920)
        self.assertEqual(parsed["height"], 1080)

    def test_extract_gallery_and_comments_and_author(self):
        mock_post_response = [
            {
                "data": {
                    "children": [
                        {
                            "kind": "t3",
                            "data": {
                                "id": "gal001",
                                "subreddit": "EarthPorn",
                                "author": "photographer1",
                                "title": "Mountain Sunrise",
                                "selftext": "Shot on mirrorless",
                                "permalink": "/r/EarthPorn/comments/gal001/mountain_sunrise/",
                                "created_utc": 1750424400,
                                "score": 420,
                                "is_gallery": True,
                                "gallery_data": {
                                    "items": [
                                        {"media_id": "m2"},
                                        {"media_id": "m1"},
                                    ]
                                },
                                "media_metadata": {
                                    "m1": {
                                        "status": "valid",
                                        "m": "image/png",
                                        "s": {"x": 2000, "y": 1500, "u": "https://i.redd.it/second.png?a=1&amp;b=2"},
                                    },
                                    "m2": {
                                        "status": "valid",
                                        "m": "image/jpeg",
                                        "s": {"x": 3000, "y": 2000, "u": "https://i.redd.it/first.jpg"},
                                    },
                                },
                            },
                        }
                    ]
                }
            },
            {
                "data": {
                    "children": [
                        {"kind": "t1", "data": {"id": "c1", "author": "viewer1", "score": 15, "body": "Amazing photo!"}}
                    ]
                }
            },
        ]

        def fake_get_json(self_client, endpoint, params=None):
            if endpoint == "/comments/gal001":
                return mock_post_response, None
            if endpoint == "/user/photographer1/about":
                return {"data": {"name": "photographer1", "link_karma": 5000, "comment_karma": 1200}}, None
            return None, "unexpected endpoint"

        with patch.object(rdl.RedditHttpClient, "get_reddit_json", fake_get_json):
            res = json.loads(
                rdl.extract_reddit(
                    "p/gal001",
                    json.dumps({"comments": True, "post_authors": True}),
                )
            )

        self.assertTrue(res["success"])
        self.assertEqual(res["itemsCount"], 2)
        # Order follows gallery_data (m2 then m1)
        self.assertEqual(res["items"][0]["url"], "https://i.redd.it/first.jpg")
        self.assertEqual(res["items"][1]["url"], "https://i.redd.it/second.png?a=1&b=2")
        self.assertEqual(res["items"][0]["metadata"]["comments_count"], "1")
        self.assertIn("photographer1", res["items"][0]["metadata"]["author_details"])

    def test_extract_hosted_video_with_dash_mpd_and_crosspost(self):
        mock_post = [
            {
                "data": {
                    "children": [
                        {
                            "kind": "t3",
                            "data": {
                                "id": "xpost1",
                                "subreddit": "videos",
                                "author": "reposter",
                                "title": "Cool Clip",
                                "permalink": "/r/videos/comments/xpost1/cool_clip/",
                                "created_utc": 1750424400,
                                "crosspost_parent_list": [
                                    {
                                        "id": "orig1",
                                        "subreddit": "original",
                                        "author": "creator",
                                        "title": "Cool Clip",
                                        "is_video": True,
                                        "secure_media": {
                                            "reddit_video": {
                                                "fallback_url": "https://v.redd.it/vid99/DASH_720.mp4",
                                                "dash_url": "https://v.redd.it/vid99/DASHPlaylist.mpd",
                                                "width": 1280,
                                                "height": 720,
                                            }
                                        },
                                    }
                                ],
                            },
                        }
                    ]
                }
            }
        ]

        with patch.object(rdl.RedditHttpClient, "get_reddit_json", return_value=(mock_post, None)), \
             patch.object(rdl.RedditHttpClient, "fetch_text", return_value=SAMPLE_DASH_MPD):
            res = json.loads(rdl.extract_reddit("https://www.reddit.com/r/videos/comments/xpost1/cool_clip/"))

        self.assertTrue(res["success"])
        self.assertEqual(res["itemsCount"], 1)
        item = res["items"][0]
        self.assertTrue(item["isVideo"])
        self.assertEqual(item["url"], "https://v.redd.it/vid99/DASH_1080.mp4")
        self.assertEqual(item["resolutionHint"], "1920x1080")
        self.assertEqual(item["metadata"]["audio_url"], "https://v.redd.it/vid99/DASH_AUDIO_128.mp4")

    def test_extract_redgifs_video(self):
        with patch.object(
            rdl.RedditHttpClient,
            "fetch_redgifs_video",
            return_value={
                "url": "https://media.redgifs.com/HappyCat.mp4",
                "thumbnail": "https://media.redgifs.com/HappyCat-poster.jpg",
                "width": 1080,
                "height": 1920,
            },
        ):
            res = json.loads(rdl.extract_reddit("https://www.redgifs.com/watch/HappyCat"))

        self.assertTrue(res["success"])
        self.assertEqual(res["items"][0]["url"], "https://media.redgifs.com/HappyCat.mp4")
        self.assertEqual(res["items"][0]["downloadHeaders"]["Referer"], "https://www.redgifs.com/")

    def test_date_range_and_continue_ignores_stickied_posts(self):
        # Stickied post is in seen_post_ids AND older than after_ts, but must NOT stop pagination!
        mock_listing = {
            "data": {
                "after": None,
                "children": [
                    {
                        "kind": "t3",
                        "data": {
                            "id": "sticky1",
                            "subreddit": "funny",
                            "title": "Pinned Rules",
                            "stickied": True,
                            "created_utc": 1700000000,
                            "url": "https://i.redd.it/sticky.jpg",
                        },
                    },
                    {
                        "kind": "t3",
                        "data": {
                            "id": "new_too_late",
                            "subreddit": "funny",
                            "title": "After Before Cutoff",
                            "stickied": False,
                            "created_utc": rdl.parse_date_filter("2025-07-20"),  # exclusive --before
                            "url": "https://i.redd.it/late.jpg",
                        },
                    },
                    {
                        "kind": "t3",
                        "data": {
                            "id": "in_range",
                            "subreddit": "funny",
                            "title": "Valid Post",
                            "stickied": False,
                            "created_utc": rdl.parse_date_filter("2025-06-20 13:00"),  # inclusive --after
                            "url": "https://i.redd.it/valid.jpg",
                        },
                    },
                    {
                        "kind": "t3",
                        "data": {
                            "id": "seen_old",
                            "subreddit": "funny",
                            "title": "Already Downloaded",
                            "stickied": False,
                            "created_utc": rdl.parse_date_filter("2025-06-25"),
                            "url": "https://i.redd.it/seen.jpg",
                        },
                    },
                    {
                        "kind": "t3",
                        "data": {
                            "id": "after_stop",
                            "subreddit": "funny",
                            "title": "Should Not Be Reached",
                            "stickied": False,
                            "created_utc": rdl.parse_date_filter("2025-06-24"),
                            "url": "https://i.redd.it/never.jpg",
                        },
                    },
                ],
            }
        }

        with patch.object(rdl.RedditHttpClient, "get_reddit_json", return_value=(mock_listing, None)):
            res = json.loads(
                rdl.extract_reddit(
                    "r/funny",
                    json.dumps({
                        "after": "2025-06-20 13:00",
                        "before": "2025-07-20",
                        "seen_post_ids": ["sticky1", "seen_old"],
                    }),
                )
            )

        self.assertTrue(res["success"])
        self.assertEqual(res["itemsCount"], 1)
        self.assertEqual(res["items"][0]["url"], "https://i.redd.it/valid.jpg")

    def test_account_targets_require_auth_and_work_with_auth(self):
        # Without auth -> clear error message
        unauth_res = json.loads(rdl.extract_reddit("my/saved"))
        self.assertFalse(unauth_res["success"])
        self.assertIn("Sign in", unauth_res["error"])

        # With cookies -> fetches /api/v1/me and /user/<me>/saved
        def fake_get_json(self_client, endpoint, params=None):
            if endpoint == "/api/v1/me":
                return {"name": "amre_test"}, None
            if endpoint == "/user/amre_test/saved":
                return {
                    "data": {
                        "after": None,
                        "children": [
                            {
                                "kind": "t3",
                                "data": {
                                    "id": "sav1",
                                    "subreddit": "art",
                                    "title": "Saved Art",
                                    "url": "https://i.redd.it/saved1.jpg",
                                },
                            }
                        ],
                    }
                }, None
            return None, "unexpected"

        with patch.object(rdl.RedditHttpClient, "get_reddit_json", fake_get_json):
            auth_res = json.loads(
                rdl.extract_reddit("my/saved", json.dumps({"cookies": "reddit_session=abc123"}))
            )
        self.assertTrue(auth_res["success"])
        self.assertEqual(auth_res["items"][0]["url"], "https://i.redd.it/saved1.jpg")

    def test_runner_integration(self):
        self.assertTrue(runner.is_reddit_dl_available())
        status = json.loads(runner.get_engine_status())
        self.assertTrue(status["reddit_dl_available"])
        self.assertIn("reddit-dl", status["engine"])

        sup = json.loads(runner.check_support("r/DataHoarder"))
        self.assertTrue(sup["supported"])
        self.assertEqual(sup["engine"], "reddit-dl")
        self.assertEqual(sup["subcategory"], "subreddit")


if __name__ == "__main__":
    unittest.main()
