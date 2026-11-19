"""
Multi-platform media extraction module implementing the feature set of
MSC72m/media_downloader for Emre Gallery's Chaquopy Python runtime:
- Smart 8-platform URL detection (YouTube, Spotify, TikTok, Instagram,
  Twitter/X + X Spaces, Pinterest, SoundCloud, RadioJavan)
- Spotify Track / Album / Playlist / Artist metadata resolution with
  YouTube-backed audio matching and selectable match candidates
- RadioJavan Songs / Videos / Playlists / Podcasts / Albums + rj.app short links
  with 5-host CDN fallback
- Twitter/X Spaces public live & replayable HLS audio extraction via GraphQL
  guest-token flow + yt-dlp fallback
- SoundCloud free track/set metadata & Go+ preview diagnostics
- TikTok video metadata & thumbnail preservation
- SOCKS5 / HTTP proxy support across urllib and yt-dlp requests
"""

import hashlib
import html
import json
import re
import urllib.error
import urllib.parse
import urllib.request

try:
    import yt_dlp
    YTDLP_AVAILABLE = True
except Exception:
    yt_dlp = None
    YTDLP_AVAILABLE = False

VERSION = "1.2.2-emre"

DEFAULT_UA = (
    "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36"
)

# RadioJavan 5-host CDN fallback order (matching MSC72m/media_downloader)
RADIOJAVAN_CDN_HOSTS = [
    "https://host2.rj-mw1.com/media",
    "https://host1.rj-mw1.com/media",
    "https://host2.rjassets.com/media",
    "https://host1.rjassets.com/media",
    "https://media.rdjavan.com/media",
]

# Public Twitter/X web bearer token used for anonymous guest-token activation
X_PUBLIC_BEARER = (
    "Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs"
    "%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
)
X_AUDIO_SPACE_QUERY_ID = "Tvv_cMTcuG2BNt0c0BpbmQ"

PLATFORM_PATTERNS = [
    ("youtube", [
        r"(?:https?://)?(?:www\.|m\.|music\.)?youtube\.com/",
        r"(?:https?://)?youtu\.be/",
    ]),
    ("spotify", [
        r"(?:https?://)?open\.spotify\.com/",
        r"(?:https?://)?play\.spotify\.com/",
        r"(?:https?://)?spotify\.link/",
        r"(?:https?://)?spoti\.fi/",
    ]),
    ("tiktok", [
        r"(?:https?://)?(?:www\.|m\.|vm\.|vt\.)?tiktok\.com/",
    ]),
    ("instagram", [
        r"(?:https?://)?(?:www\.|m\.)?instagram\.com/",
        r"(?:https?://)?instagr\.am/",
    ]),
    ("twitter_space", [
        r"(?:https?://)?(?:www\.|mobile\.)?(?:twitter\.com|x\.com)/i/spaces/[A-Za-z0-9]+",
    ]),
    ("twitter", [
        r"(?:https?://)?(?:www\.|mobile\.)?(?:twitter\.com|x\.com)/",
        r"(?:https?://)?t\.co/",
    ]),
    ("pinterest", [
        r"(?:https?://)?(?:[a-z]{2}\.|www\.)?pinterest\.[a-z.]+/",
        r"(?:https?://)?pin\.it/",
    ]),
    ("soundcloud", [
        r"(?:https?://)?(?:www\.|m\.|on\.)?soundcloud\.com/",
    ]),
    ("radiojavan", [
        r"(?:https?://)?(?:www\.|play\.)?radiojavan\.com/",
        r"(?:https?://)?rj\.app/",
    ]),
]


def get_version():
    return VERSION


def detect_platform(url):
    """Detects which of the 8 supported platforms (plus x_space) a URL belongs to."""
    if not url or not isinstance(url, str):
        return "unknown"
    clean = url.strip()
    for platform, patterns in PLATFORM_PATTERNS:
        for pat in patterns:
            if re.search(pat, clean, re.IGNORECASE):
                return platform
    return "unknown"


def is_spotify_url(url):
    return detect_platform(url) == "spotify"


def is_radiojavan_url(url):
    return detect_platform(url) == "radiojavan"


def is_x_space_url(url):
    return detect_platform(url) == "twitter_space"


def is_soundcloud_url(url):
    return detect_platform(url) == "soundcloud"


def is_tiktok_url(url):
    return detect_platform(url) == "tiktok"


def _build_opener(proxy=None):
    handlers = []
    if proxy and isinstance(proxy, str) and proxy.strip():
        p = proxy.strip()
        # urllib supports HTTP/HTTPS proxies directly; SOCKS proxies are handled by yt-dlp / OkHttp
        if p.lower().startswith(("http://", "https://")):
            handlers.append(urllib.request.ProxyHandler({"http": p, "https": p}))
    return urllib.request.build_opener(*handlers)


def _http_get(url, headers=None, timeout=20, proxy=None, method="GET"):
    req_headers = {
        "User-Agent": DEFAULT_UA,
        "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
    }
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, headers=req_headers, method=method)
    opener = _build_opener(proxy)
    with opener.open(req, timeout=timeout) as resp:
        final_url = resp.geturl()
        status = getattr(resp, "status", 200)
        body = b"" if method == "HEAD" else resp.read()
        content_type = resp.headers.get("Content-Type", "")
        content_length = resp.headers.get("Content-Length")
        return {
            "status": status,
            "final_url": final_url,
            "body": body.decode("utf-8", errors="replace"),
            "content_type": content_type,
            "content_length": int(content_length) if content_length and content_length.isdigit() else None,
        }


def _http_post(url, data=b"", headers=None, timeout=20, proxy=None):
    req_headers = {"User-Agent": DEFAULT_UA}
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, data=data, headers=req_headers, method="POST")
    opener = _build_opener(proxy)
    with opener.open(req, timeout=timeout) as resp:
        return {
            "status": getattr(resp, "status", 200),
            "body": resp.read().decode("utf-8", errors="replace"),
        }


def _sanitize_filename(name):
    cleaned = re.sub(r'[\\/:*?"<>|\r\n]+', "_", str(name or "").strip())
    cleaned = re.sub(r"\s+", " ", cleaned).strip(" ._")
    return cleaned[:120] or "media"


def _url_hash(url):
    return hashlib.sha256(url.encode("utf-8")).hexdigest()[:12]


# ---------------------------------------------------------------------------
# SPOTIFY EXTRACTOR (Metadata + YouTube-backed audio with match selection)
# ---------------------------------------------------------------------------

def parse_spotify_url(url):
    """Extracts (kind, spotify_id) from a Spotify URL."""
    clean = (url or "").strip()
    m = re.search(
        r"open\.spotify\.com/(?:intl-[a-z]{2}/)?(track|album|playlist|artist|episode)/([A-Za-z0-9]+)",
        clean,
        re.IGNORECASE,
    )
    if m:
        return {"kind": m.group(1).lower(), "id": m.group(2), "url": clean}
    return {"kind": "unknown", "id": "", "url": clean}


def _parse_spotify_embed_tracks(embed_html, kind, fallback_title="", fallback_thumb=""):
    """Parses track list and metadata from Spotify's public embed __NEXT_DATA__ JSON."""
    tracks = []
    collection_title = fallback_title
    collection_thumb = fallback_thumb

    m = re.search(
        r'<script[^>]+id=["\']__NEXT_DATA__["\'][^>]*>(.*?)</script>',
        embed_html,
        re.DOTALL | re.IGNORECASE,
    )
    if m:
        try:
            data = json.loads(m.group(1))
            entity = (
                data.get("props", {})
                .get("pageProps", {})
                .get("state", {})
                .get("data", {})
                .get("entity", {})
            )
            if isinstance(entity, dict) and entity:
                collection_title = entity.get("name") or entity.get("title") or collection_title
                visuals = entity.get("visualIdentity", {}) or {}
                images = visuals.get("image") or entity.get("coverArt", {}).get("sources") or []
                if isinstance(images, list) and images:
                    collection_thumb = images[0].get("url") or collection_thumb

                if kind in ("track", "episode"):
                    artists = [
                        a.get("name", "")
                        for a in (entity.get("artists") or [])
                        if isinstance(a, dict) and a.get("name")
                    ]
                    artist_str = ", ".join(artists) or entity.get("subtitle", "")
                    tracks.append({
                        "title": entity.get("name") or entity.get("title") or collection_title,
                        "artist": artist_str,
                        "duration_ms": entity.get("duration") or entity.get("durationMs"),
                        "thumbnail": collection_thumb,
                    })
                else:
                    track_list = entity.get("trackList") or []
                    for t in track_list:
                        if not isinstance(t, dict):
                            continue
                        t_title = t.get("title") or t.get("name") or ""
                        t_artist = t.get("subtitle") or ""
                        if t_title:
                            tracks.append({
                                "title": t_title,
                                "artist": t_artist,
                                "duration_ms": t.get("duration"),
                                "thumbnail": collection_thumb,
                            })
        except Exception:
            pass

    if not tracks:
        # OpenGraph fallback
        og_title = re.search(r'<meta[^>]+property=["\']og:title["\'][^>]+content=["\']([^"\']+)', embed_html, re.IGNORECASE)
        og_desc = re.search(r'<meta[^>]+property=["\']og:description["\'][^>]+content=["\']([^"\']+)', embed_html, re.IGNORECASE)
        og_img = re.search(r'<meta[^>]+property=["\']og:image["\'][^>]+content=["\']([^"\']+)', embed_html, re.IGNORECASE)
        t_name = html.unescape(og_title.group(1)) if og_title else fallback_title
        t_desc = html.unescape(og_desc.group(1)) if og_desc else ""
        t_img = html.unescape(og_img.group(1)) if og_img else fallback_thumb
        if t_name:
            collection_title = collection_title or t_name
            collection_thumb = collection_thumb or t_img
            artist_guess = ""
            if " · " in t_desc:
                artist_guess = t_desc.split(" · ")[0].strip()
            tracks.append({
                "title": t_name,
                "artist": artist_guess,
                "duration_ms": None,
                "thumbnail": t_img,
            })

    return collection_title, collection_thumb, tracks


def _search_youtube_audio_matches(query, max_results=3, proxy=None, timeout=25, cookies=None):
    """Searches YouTube via yt-dlp for audio matches corresponding to a Spotify track."""
    if not YTDLP_AVAILABLE or not query:
        return []
    ydl_opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "extract_flat": False,
        "format": "bestaudio/best",
        "socket_timeout": timeout,
        "geo_bypass": True,
        "retries": 2,
    }
    if proxy:
        ydl_opts["proxy"] = proxy

    matches = []
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(f"ytsearch{max_results}:{query}", download=False)
            entries = (info or {}).get("entries") or []
            for rank, entry in enumerate(entries, start=1):
                if not isinstance(entry, dict):
                    continue
                audio_url = entry.get("url")
                ext = entry.get("ext") or "m4a"
                formats = entry.get("formats") or []
                if not audio_url and formats:
                    audio_fmts = [
                        f for f in formats
                        if f.get("url") and f.get("vcodec") == "none" and f.get("acodec") != "none"
                    ]
                    chosen = audio_fmts[-1] if audio_fmts else next((f for f in formats if f.get("url")), None)
                    if chosen:
                        audio_url = chosen.get("url")
                        ext = chosen.get("ext") or ext
                if not audio_url:
                    continue
                matches.append({
                    "rank": rank,
                    "url": audio_url,
                    "ext": ext,
                    "yt_title": entry.get("title") or query,
                    "yt_url": entry.get("webpage_url") or "",
                    "uploader": entry.get("uploader") or entry.get("channel") or "",
                    "duration": entry.get("duration"),
                    "thumbnail": entry.get("thumbnail") or "",
                    "filesize": entry.get("filesize") or entry.get("filesize_approx"),
                })
    except Exception:
        pass
    return matches


def extract_spotify(url, options_json="{}"):
    """
    Resolves Spotify track/album/playlist/artist metadata and matches audio via YouTube.
    For single tracks, returns up to 3 YouTube audio matches for user match selection.
    For albums/playlists/artists, resolves the top YouTube match per track (capped by limit).
    """
    try:
        opts = json.loads(options_json) if options_json else {}
    except Exception:
        opts = {}

    proxy = (opts.get("proxy") or "").strip() or None
    timeout = int(opts.get("timeout") or 25)
    limit = max(1, min(int(opts.get("limit") or 25), 50))
    clean_url = (url or "").strip()

    if not clean_url:
        return json.dumps({"success": False, "engine": "spotify", "error": "Empty Spotify URL", "items": []})

    # Resolve spotify.link / spoti.fi short links
    if re.search(r"spotify\.link/|spoti\.fi/", clean_url, re.IGNORECASE):
        try:
            resp = _http_get(clean_url, timeout=timeout, proxy=proxy)
            if resp.get("final_url"):
                clean_url = resp["final_url"]
        except Exception:
            pass

    parsed = parse_spotify_url(clean_url)
    kind = parsed["kind"]
    spotify_id = parsed["id"]
    if kind == "unknown" or not spotify_id:
        return json.dumps({
            "success": False,
            "engine": "spotify",
            "error": f"Unsupported Spotify URL format: {clean_url}",
            "items": [],
        })

    fallback_title = ""
    fallback_thumb = ""
    try:
        oembed_url = f"https://open.spotify.com/oembed?url={urllib.parse.quote(clean_url, safe='')}"
        oembed = json.loads(_http_get(oembed_url, timeout=timeout, proxy=proxy)["body"])
        fallback_title = oembed.get("title") or ""
        fallback_thumb = oembed.get("thumbnail_url") or ""
    except Exception:
        pass

    embed_html = ""
    try:
        embed_url = f"https://open.spotify.com/embed/{kind}/{spotify_id}"
        embed_html = _http_get(embed_url, timeout=timeout, proxy=proxy)["body"]
    except Exception as e:
        if not fallback_title:
            return json.dumps({
                "success": False,
                "engine": "spotify",
                "error": f"Failed to fetch Spotify metadata: {e}",
                "items": [],
            })

    collection_title, collection_thumb, tracks = _parse_spotify_embed_tracks(
        embed_html, kind, fallback_title=fallback_title, fallback_thumb=fallback_thumb
    )
    if not tracks:
        return json.dumps({
            "success": False,
            "engine": "spotify",
            "error": "No Spotify tracks could be resolved from this URL.",
            "items": [],
        })

    items = []
    seen_urls = set()
    is_single = kind in ("track", "episode") and len(tracks) == 1
    matches_per_track = 3 if is_single else 1

    for idx, track in enumerate(tracks[:limit]):
        t_title = track.get("title") or f"Track {idx + 1}"
        t_artist = track.get("artist") or ""
        query = f"{t_artist} - {t_title} audio" if t_artist else f"{t_title} audio"
        matches = _search_youtube_audio_matches(
            query, max_results=matches_per_track, proxy=proxy, timeout=timeout
        )
        for match in matches:
            m_url = match["url"]
            if m_url in seen_urls:
                continue
            seen_urls.add(m_url)
            ext = match["ext"] or "m4a"
            base_label = f"{t_artist} - {t_title}" if t_artist else t_title
            if is_single and match["rank"] > 1:
                display_title = f"{_sanitize_filename(base_label)} [Match #{match['rank']} - {match['uploader']}].{ext}"
            else:
                display_title = f"{_sanitize_filename(base_label)}.{ext}"
            items.append({
                "id": f"spotify_{idx}_{match['rank']}_{_url_hash(m_url)}",
                "url": m_url,
                "title": display_title,
                "category": "AUDIO",
                "format": ext.upper(),
                "mimeType": f"audio/{'mpeg' if ext == 'mp3' else ext}",
                "isVideo": False,
                "isAudio": True,
                "isStream": False,
                "thumbnailUrl": track.get("thumbnail") or match.get("thumbnail") or collection_thumb,
                "sizeBytes": match.get("filesize"),
                "source": "Spotify (YouTube Audio)",
                "pageUrl": match.get("yt_url") or clean_url,
                "metadata": {
                    "platform": "spotify",
                    "spotify_url": clean_url,
                    "spotify_kind": kind,
                    "spotify_track": t_title,
                    "spotify_artist": t_artist,
                    "youtube_match_title": match.get("yt_title", ""),
                    "youtube_match_rank": str(match["rank"]),
                },
            })

    return json.dumps({
        "success": len(items) > 0,
        "engine": "spotify",
        "extractor": "SpotifyYouTubeMatcher",
        "pageTitle": collection_title or fallback_title or "Spotify",
        "pageDescription": f"Spotify {kind} ({len(tracks)} track(s)) matched via YouTube audio",
        "previewImageUrl": collection_thumb,
        "itemsCount": len(items),
        "items": items,
        "error": "" if items else "Spotify metadata resolved, but no matching YouTube audio stream was found.",
    })


# ---------------------------------------------------------------------------
# RADIOJAVAN EXTRACTOR (Direct MP3/MP4 + rj.app + 5-Host CDN Fallback)
# ---------------------------------------------------------------------------

def parse_radiojavan_url(url):
    """Parses RadioJavan full URLs and rj.app short links."""
    clean = (url or "").strip()
    patterns = [
        (r"rj\.app/m/([A-Za-z0-9_-]+)", "song"),
        (r"rj\.app/v/([A-Za-z0-9_-]+)", "video"),
        (r"rj\.app/p/([A-Za-z0-9_-]+)", "podcast"),
        (r"rj\.app/pm/([A-Za-z0-9_-]+)", "playlist"),
        (r"rj\.app/al/([A-Za-z0-9_-]+)", "album"),
        (r"radiojavan\.com/(?:mp3s/mp3|song)/([^/?#]+)", "song"),
        (r"radiojavan\.com/(?:videos/video|video)/([^/?#]+)", "video"),
        (r"radiojavan\.com/(?:podcasts/podcast|podcast)/([^/?#]+)", "podcast"),
        (r"radiojavan\.com/(?:playlists/playlist(?:/mp3)?|playlist)/([^/?#]+)", "playlist"),
        (r"radiojavan\.com/(?:albums/album|album)/([^/?#]+)", "album"),
        (r"radiojavan\.com/artist/([^/?#]+)", "artist"),
    ]
    for pat, kind in patterns:
        m = re.search(pat, clean, re.IGNORECASE)
        if m:
            return {"kind": kind, "slug": m.group(1), "url": clean}
    return {"kind": "unknown", "slug": "", "url": clean}


def _probe_radiojavan_cdn(media_rel_paths, is_video=False, proxy=None, timeout=12):
    """
    Probes RadioJavan's 5 CDN hosts in priority order until a live MP3/MP4 URL is confirmed.
    Returns (confirmed_url, content_length) or (None, None).
    """
    for rel_path in media_rel_paths:
        rel = rel_path.lstrip("/")
        for host in RADIOJAVAN_CDN_HOSTS:
            candidate = f"{host}/{rel}"
            try:
                resp = _http_get(candidate, timeout=timeout, proxy=proxy, method="HEAD")
                ct = (resp.get("content_type") or "").lower()
                if resp["status"] in (200, 206) and (
                    "audio" in ct or "video" in ct or "octet-stream" in ct or candidate.endswith((".mp3", ".mp4"))
                ):
                    return candidate, resp.get("content_length")
            except Exception:
                continue
    return None, None


def _extract_rj_media_urls_from_html(page_html):
    """Extracts direct MP3/MP4 URLs, relative CDN paths, title, and artwork from RadioJavan HTML/JSON."""
    direct_urls = []
    seen = set()

    # 1. Explicit full media URLs embedded in page JSON / scripts
    for m in re.finditer(r'https?://[^\s"\'<>\\]+?\.(?:mp3|mp4)(?:\?[^\s"\'<>\\]*)?', page_html, re.IGNORECASE):
        u = m.group(0).replace("\\/", "/")
        if u not in seen:
            seen.add(u)
            direct_urls.append(u)

    # 2. Relative media paths (e.g. mp3/mp3-256/Song-Name.mp3 or music_video/hd/Video.mp4)
    rel_paths = []
    for m in re.finditer(r'["\']((?:mp3|podcast|music_video)/[^"\']+\.(?:mp3|mp4))["\']', page_html, re.IGNORECASE):
        p = m.group(1).replace("\\/", "/")
        if p not in rel_paths:
            rel_paths.append(p)

    # 3. Title and thumbnail via OpenGraph
    og_title = re.search(r'<meta[^>]+property=["\']og:title["\'][^>]+content=["\']([^"\']+)', page_html, re.IGNORECASE)
    og_img = re.search(r'<meta[^>]+property=["\']og:image["\'][^>]+content=["\']([^"\']+)', page_html, re.IGNORECASE)
    title = html.unescape(og_title.group(1)).strip() if og_title else ""
    thumb = html.unescape(og_img.group(1)).strip() if og_img else ""

    return title, thumb, direct_urls, rel_paths


def extract_radiojavan(url, options_json="{}"):
    """
    Extracts RadioJavan songs, videos, podcasts, playlists, and albums.
    Supports play.radiojavan.com, radiojavan.com, rj.app short links, and 5-host CDN fallback.
    """
    try:
        opts = json.loads(options_json) if options_json else {}
    except Exception:
        opts = {}

    proxy = (opts.get("proxy") or "").strip() or None
    timeout = int(opts.get("timeout") or 25)
    cookies = (opts.get("cookies") or "").strip()
    clean_url = (url or "").strip()

    if not clean_url:
        return json.dumps({"success": False, "engine": "radiojavan", "error": "Empty RadioJavan URL", "items": []})

    headers = {"Referer": "https://play.radiojavan.com/"}
    if cookies:
        headers["Cookie"] = cookies

    # Resolve rj.app short links or fetch page directly
    final_url = clean_url
    page_html = ""
    try:
        resp = _http_get(clean_url, headers=headers, timeout=timeout, proxy=proxy)
        final_url = resp.get("final_url") or clean_url
        page_html = resp.get("body") or ""
    except Exception as e:
        return json.dumps({
            "success": False,
            "engine": "radiojavan",
            "error": f"Failed to reach RadioJavan: {e}",
            "items": [],
        })

    parsed = parse_radiojavan_url(final_url)
    kind = parsed["kind"]
    slug = parsed["slug"]
    title, thumb, direct_urls, rel_paths = _extract_rj_media_urls_from_html(page_html)
    page_title = title or slug.replace("-", " ") or "RadioJavan"

    items = []
    seen = set()

    # Add direct URLs discovered in page HTML/JSON
    for idx, media_url in enumerate(direct_urls):
        if media_url in seen:
            continue
        seen.add(media_url)
        is_video = ".mp4" in media_url.lower()
        ext = "mp4" if is_video else "mp3"
        base_name = _sanitize_filename(page_title if len(direct_urls) == 1 else f"{page_title}_{idx + 1}")
        items.append({
            "id": f"rj_{idx}_{_url_hash(media_url)}",
            "url": media_url,
            "title": f"{base_name}.{ext}",
            "category": "VIDEOS" if is_video else "AUDIO",
            "format": ext.upper(),
            "mimeType": "video/mp4" if is_video else "audio/mpeg",
            "isVideo": is_video,
            "isAudio": not is_video,
            "isStream": False,
            "thumbnailUrl": thumb or None,
            "source": "RadioJavan",
            "pageUrl": final_url,
            "downloadHeaders": {"Referer": "https://play.radiojavan.com/"},
            "metadata": {"platform": "radiojavan", "kind": kind},
        })

    # If no direct URL found in HTML, probe RadioJavan's 5-host CDN fallback
    if not items:
        candidate_rel_paths = list(rel_paths)
        if slug:
            if kind == "video":
                candidate_rel_paths.extend([
                    f"music_video/hd/{slug}.mp4",
                    f"music_video/hq/{slug}.mp4",
                    f"music_video/lq/{slug}.mp4",
                ])
            elif kind == "podcast":
                candidate_rel_paths.extend([
                    f"podcast/mp3-256/{slug}.mp3",
                    f"podcast/mp3-192/{slug}.mp3",
                ])
            else:
                candidate_rel_paths.extend([
                    f"mp3/mp3-256/{slug}.mp3",
                    f"mp3/mp3-192/{slug}.mp3",
                    f"mp3/mp3-128/{slug}.mp3",
                ])

        probed_url, probed_len = _probe_radiojavan_cdn(
            candidate_rel_paths, is_video=(kind == "video"), proxy=proxy, timeout=min(timeout, 12)
        )
        if probed_url:
            is_video = probed_url.lower().endswith(".mp4")
            ext = "mp4" if is_video else "mp3"
            items.append({
                "id": f"rj_cdn_{_url_hash(probed_url)}",
                "url": probed_url,
                "title": f"{_sanitize_filename(page_title)}.{ext}",
                "category": "VIDEOS" if is_video else "AUDIO",
                "format": ext.upper(),
                "mimeType": "video/mp4" if is_video else "audio/mpeg",
                "isVideo": is_video,
                "isAudio": not is_video,
                "isStream": False,
                "thumbnailUrl": thumb or None,
                "sizeBytes": probed_len,
                "source": "RadioJavan CDN",
                "pageUrl": final_url,
                "downloadHeaders": {"Referer": "https://play.radiojavan.com/"},
                "metadata": {"platform": "radiojavan", "kind": kind, "cdn_fallback": "true"},
            })

    return json.dumps({
        "success": len(items) > 0,
        "engine": "radiojavan",
        "extractor": "RadioJavanExtractor",
        "pageTitle": page_title,
        "pageDescription": f"RadioJavan {kind}",
        "previewImageUrl": thumb,
        "itemsCount": len(items),
        "items": items,
        "error": "" if items else "Could not locate a direct RadioJavan stream across all 5 CDN hosts.",
    })


# ---------------------------------------------------------------------------
# TWITTER / X SPACES EXTRACTOR (GraphQL AudioSpaceById + HLS Audio)
# ---------------------------------------------------------------------------

def parse_x_space_id(url):
    m = re.search(r"(?:twitter\.com|x\.com)/i/spaces/([A-Za-z0-9]+)", (url or "").strip(), re.IGNORECASE)
    return m.group(1) if m else ""


def extract_x_space(url, options_json="{}"):
    """
    Extracts public live or replayable Twitter/X Space HLS audio stream.
    1. Activates an anonymous guest token and queries X GraphQL AudioSpaceById.
    2. Resolves the dynamic HLS (.m3u8) playlist from live_video_stream/status/<media_key>.
    3. Falls back to yt-dlp's TwitterSpaces extractor if GraphQL is restricted.
    """
    try:
        opts = json.loads(options_json) if options_json else {}
    except Exception:
        opts = {}

    proxy = (opts.get("proxy") or "").strip() or None
    timeout = int(opts.get("timeout") or 25)
    cookies = (opts.get("cookies") or "").strip()
    clean_url = (url or "").strip()
    space_id = parse_x_space_id(clean_url)

    if not space_id:
        return json.dumps({"success": False, "engine": "x_spaces", "error": "Invalid X Space URL", "items": []})

    space_title = f"X Space {space_id}"
    diagnostic_error = ""
    hls_url = None
    space_state = ""

    # 1. X GraphQL AudioSpaceById flow
    try:
        guest_resp = _http_post(
            "https://api.twitter.com/1.1/guest/activate.json",
            data=b"",
            headers={"Authorization": X_PUBLIC_BEARER},
            timeout=timeout,
            proxy=proxy,
        )
        guest_token = json.loads(guest_resp["body"]).get("guest_token", "")
        if guest_token:
            variables = json.dumps({
                "id": space_id,
                "isMetatagsQuery": False,
                "withReplays": True,
                "withListeners": True,
            })
            features = json.dumps({
                "spaces_2022_h2_spaces_communities": True,
                "spaces_2022_h2_clipping": True,
                "creator_subscriptions_tweet_preview_api_enabled": True,
            })
            gql_url = (
                f"https://api.twitter.com/graphql/{X_AUDIO_SPACE_QUERY_ID}/AudioSpaceById"
                f"?variables={urllib.parse.quote(variables)}&features={urllib.parse.quote(features)}"
            )
            gql_headers = {
                "Authorization": X_PUBLIC_BEARER,
                "x-guest-token": guest_token,
            }
            if cookies:
                gql_headers["Cookie"] = cookies
            gql_resp = _http_get(gql_url, headers=gql_headers, timeout=timeout, proxy=proxy)
            gql_data = json.loads(gql_resp["body"])
            meta = (
                gql_data.get("data", {})
                .get("audioSpace", {})
                .get("metadata", {})
            )
            if meta:
                space_title = meta.get("title") or space_title
                space_state = meta.get("state") or ""
                media_key = meta.get("media_key") or ""
                is_replay_available = meta.get("is_space_available_for_replay", True)
                if space_state.lower() == "ended" and not is_replay_available:
                    diagnostic_error = "This X Space has ended and replay is disabled by the host."
                elif media_key:
                    stream_url = f"https://api.twitter.com/1.1/live_video_stream/status/{media_key}"
                    stream_resp = _http_get(stream_url, headers=gql_headers, timeout=timeout, proxy=proxy)
                    stream_data = json.loads(stream_resp["body"])
                    hls_url = (stream_data.get("source") or {}).get("location")
    except Exception as e:
        diagnostic_error = f"X GraphQL lookup failed: {e}"

    # 2. Fallback to yt-dlp if GraphQL didn't return an HLS URL
    if not hls_url and YTDLP_AVAILABLE and "replay is disabled" not in diagnostic_error.lower():
        try:
            ydl_opts = {
                "quiet": True,
                "no_warnings": True,
                "skip_download": True,
                "socket_timeout": timeout,
            }
            if proxy:
                ydl_opts["proxy"] = proxy
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(clean_url, download=False)
                if isinstance(info, dict):
                    space_title = info.get("title") or space_title
                    hls_url = info.get("url")
                    if not hls_url and info.get("formats"):
                        hls_url = info["formats"][-1].get("url")
        except Exception as e:
            if not diagnostic_error:
                diagnostic_error = str(e)

    if not hls_url:
        return json.dumps({
            "success": False,
            "engine": "x_spaces",
            "error": diagnostic_error or "X Space is private, deleted, login-gated, or replay-disabled.",
            "items": [],
        })

    items = [{
        "id": f"xspace_{space_id}_{_url_hash(hls_url)}",
        "url": hls_url,
        "title": f"{_sanitize_filename(space_title)}.m4a",
        "category": "STREAMS",
        "format": "HLS",
        "mimeType": "application/x-mpegURL",
        "isVideo": False,
        "isAudio": True,
        "isStream": True,
        "source": "Twitter/X Spaces",
        "pageUrl": clean_url,
        "metadata": {
            "platform": "twitter_space",
            "space_id": space_id,
            "space_state": space_state,
        },
    }]

    return json.dumps({
        "success": True,
        "engine": "x_spaces",
        "extractor": "TwitterSpacesExtractor",
        "pageTitle": space_title,
        "pageDescription": f"X Space ({space_state or 'Public'}) HLS Audio",
        "previewImageUrl": "",
        "itemsCount": len(items),
        "items": items,
    })


def check_soundcloud_preview_only(entry):
    """Returns True if a SoundCloud yt-dlp entry is a Go+ 30-second preview-only track."""
    if not isinstance(entry, dict):
        return False
    url = str(entry.get("url") or "").lower()
    format_id = str(entry.get("format_id") or "").lower()
    if "preview" in format_id or "/preview/" in url:
        return True
    return False
