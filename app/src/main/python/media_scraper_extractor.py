"""
mitchlinDEV/media-scraper feature adapter for Emre Gallery (Chaquopy Python 3.10).

Implements the feature set of https://github.com/mitchlinDEV/media-scraper adapted for
Android without external Selenium/PhantomJS subprocesses:
- General-purpose web media scraping (<a href>, <img src/data-src/srcset>, <video>, <source>, OpenGraph)
- Depth-limited recursive link crawling (`scrape_recursive`, default max_depth=3)
- Full-site same-domain BFS crawling (`scrape_recursive_full_site`) with collections.deque,
  HTTPS URL normalization, sitemap tracking/logging, 3x retry with delay, and CAPTCHA detection
- Windows/Android-safe filename sanitization (`sanitize_filename`) and safe directory creation
- IANA mimetypes media classification (`is_media_url`) + HLS (.m3u8) highest-resolution variant selection
- Lightweight HTTP (`m_scraper/rq`-style) extractors for Tumblr, Pixiv, Twitter/X video/m3u8, and Facebook
"""

import collections
import html
import json
import mimetypes
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser


VERSION = "1.0.0-mitchlinDEV"
DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
)

# Expanded IANA image/video/audio extensions (mirrors mediatypes/{image,video,audio}.csv)
IANA_IMAGE_EXTENSIONS = frozenset({
    ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg", ".ico",
    ".tif", ".tiff", ".avif", ".heic", ".heif", ".jfif", ".pjpeg", ".pjp",
    ".apng", ".jxl", ".psd", ".raw", ".dng",
})
IANA_VIDEO_EXTENSIONS = frozenset({
    ".mp4", ".m4v", ".webm", ".mkv", ".mov", ".avi", ".wmv", ".flv",
    ".f4v", ".ogv", ".ogg", ".3gp", ".3g2", ".ts", ".m2ts", ".mts",
    ".mpeg", ".mpg", ".vob", ".m3u8", ".mpd",
})
IANA_AUDIO_EXTENSIONS = frozenset({
    ".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav", ".flac",
    ".wma", ".aiff", ".alac", ".mid", ".midi",
})

CAPTCHA_MARKERS = (
    "captcha",
    "g-recaptcha",
    "h-captcha",
    "cf-challenge",
    "cf-turnstile",
    "verify you are human",
    "are you a robot",
    "bot detection",
)

NON_PAGE_EXTENSIONS = (
    IANA_IMAGE_EXTENSIONS
    | IANA_VIDEO_EXTENSIONS
    | IANA_AUDIO_EXTENSIONS
    | frozenset({
        ".pdf", ".zip", ".rar", ".7z", ".tar", ".gz", ".bz2", ".xz",
        ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".csv",
        ".css", ".js", ".mjs", ".map", ".woff", ".woff2", ".ttf", ".otf", ".eot",
        ".xml", ".rss", ".atom", ".json", ".apk", ".exe", ".dmg", ".iso",
    })
)


def get_version():
    return VERSION


def sanitize_filename(name, fallback="media"):
    """
    Windows & Android-safe filename sanitizer (mitchlinDEV/media-scraper 2025 update).
    Strips [<>:\"/\\|?*\\n\\r\\t], collapses whitespace, and trims trailing spaces/dots.
    """
    if not name:
        return fallback
    cleaned = re.sub(r'[<>:"/\\|?*\n\r\t]+', "_", str(name))
    cleaned = re.sub(r"\s+", " ", cleaned).strip(" .")
    if not cleaned:
        return fallback
    return cleaned[:180]


def safe_makedirs(path):
    """Safely creates target directories without raising if they already exist."""
    if path:
        os.makedirs(path, exist_ok=True)
    return path


def guess_media_type(url):
    """
    Classifies a URL using Python's mimetypes.guess_type (strict=False) and IANA tables.
    Returns (category, mime_type, ext) where category is one of
    'image', 'video', 'stream', 'audio', or None.
    """
    if not url or not isinstance(url, str):
        return None, None, ""
    clean = url.strip()
    if not clean or clean.startswith(("data:", "javascript:", "mailto:", "tel:", "blob:")):
        return None, None, ""

    parsed = urllib.parse.urlsplit(clean)
    path = parsed.path or ""
    lower_path = path.lower()
    _, ext = os.path.splitext(lower_path)

    if ext == ".m3u8" or ".m3u8" in lower_path:
        return "stream", "application/x-mpegURL", ".m3u8"
    if ext == ".mpd" or ".mpd" in lower_path:
        return "stream", "application/dash+xml", ".mpd"
    if ext in IANA_IMAGE_EXTENSIONS:
        mime, _ = mimetypes.guess_type(path, strict=False)
        return "image", mime or f"image/{ext.lstrip('.')}", ext
    if ext in IANA_VIDEO_EXTENSIONS:
        mime, _ = mimetypes.guess_type(path, strict=False)
        return "video", mime or f"video/{ext.lstrip('.')}", ext
    if ext in IANA_AUDIO_EXTENSIONS:
        mime, _ = mimetypes.guess_type(path, strict=False)
        return "audio", mime or f"audio/{ext.lstrip('.')}", ext

    mime, _ = mimetypes.guess_type(clean, strict=False)
    if mime:
        if mime.startswith("image/"):
            return "image", mime, ext or ".jpg"
        if mime.startswith("video/"):
            return "video", mime, ext or ".mp4"
        if mime.startswith("audio/"):
            return "audio", mime, ext or ".mp3"
    return None, None, ext


def is_media_url(url):
    """Returns True if url resolves to an image, video, audio, or HLS/DASH stream."""
    cat, _, _ = guess_media_type(url)
    return cat is not None


def normalize_crawl_url(raw_url, base_url=None, force_https=True):
    """
    Resolves relative links against base_url, strips fragments, and normalizes
    http:// to https:// (matching mitchlinDEV/media-scraper's full-site crawler).
    """
    if not raw_url:
        return None
    raw = html.unescape(str(raw_url)).strip()
    if not raw or raw.startswith(("#", "javascript:", "mailto:", "tel:", "data:", "blob:")):
        return None
    if base_url:
        resolved = urllib.parse.urljoin(base_url, raw)
    else:
        resolved = raw
    parsed = urllib.parse.urlsplit(resolved)
    scheme = parsed.scheme.lower()
    if scheme not in ("http", "https") or not parsed.netloc:
        return None
    if force_https and scheme == "http":
        scheme = "https"
    # Normalize trailing slash on bare root path and drop fragment
    path = parsed.path or "/"
    normalized = urllib.parse.urlunsplit((scheme, parsed.netloc.lower(), path, parsed.query, ""))
    return normalized


def detect_captcha(html_text):
    """Detects common CAPTCHA / human-verification challenges in page HTML or visible text."""
    if not html_text:
        return False
    lower = html_text.lower()
    if any(marker in lower for marker in CAPTCHA_MARKERS if marker != "captcha"):
        return True
    visible_text = re.sub(r"<[^>]+>", " ", lower)
    return "captcha" in visible_text


def _is_crawlable_page_url(url):
    """Returns True if URL looks like an HTML page worth crawling rather than a binary asset."""
    parsed = urllib.parse.urlsplit(url)
    _, ext = os.path.splitext((parsed.path or "").lower())
    if ext and ext in NON_PAGE_EXTENSIONS:
        return False
    return True


def _pick_best_srcset(srcset_value):
    """Selects the highest width/density URL from an HTML srcset attribute."""
    if not srcset_value:
        return None
    best_url = None
    best_score = -1.0
    for part in srcset_value.split(","):
        tokens = part.strip().split()
        if not tokens:
            continue
        url_candidate = tokens[0]
        score = 1.0
        if len(tokens) > 1:
            desc = tokens[1].lower()
            m_w = re.match(r"^(\d+)w$", desc)
            m_x = re.match(r"^(\d+(?:\.\d+)?)x$", desc)
            if m_w:
                score = float(m_w.group(1))
            elif m_x:
                score = float(m_x.group(1)) * 1000.0
        if score > best_score:
            best_score = score
            best_url = url_candidate
    return best_url


class _MediaAndLinkParser(HTMLParser):
    """Lightweight stdlib HTML parser extracting title, media tags, and crawlable <a> links."""

    def __init__(self, base_url):
        super().__init__(convert_charrefs=True)
        self.base_url = base_url
        self.in_title = False
        self.title_chunks = []
        self.media_candidates = []  # list of dicts: url, kind_hint, title_hint, poster
        self.page_links = []

    def handle_starttag(self, tag, attrs):
        tag_l = tag.lower()
        attr_map = {k.lower(): (v or "") for k, v in attrs if k}

        if tag_l == "title":
            self.in_title = True
            return

        if tag_l == "meta":
            prop = (attr_map.get("property") or attr_map.get("name") or "").lower()
            content = attr_map.get("content", "").strip()
            if content and prop in (
                "og:image", "og:image:secure_url", "twitter:image",
                "og:video", "og:video:url", "og:video:secure_url", "twitter:player:stream",
            ):
                hint = "video" if "video" in prop or "stream" in prop else "image"
                self.media_candidates.append({
                    "url": content,
                    "kind_hint": hint,
                    "title_hint": "",
                    "poster": None,
                })
            return

        if tag_l == "img":
            src = (
                _pick_best_srcset(attr_map.get("srcset") or attr_map.get("data-srcset"))
                or attr_map.get("data-original")
                or attr_map.get("data-src")
                or attr_map.get("src")
            )
            if src:
                self.media_candidates.append({
                    "url": src.strip(),
                    "kind_hint": "image",
                    "title_hint": attr_map.get("alt") or attr_map.get("title") or "",
                    "poster": None,
                })
            return

        if tag_l == "video":
            poster = attr_map.get("poster", "").strip() or None
            src = attr_map.get("src") or attr_map.get("data-src")
            if src:
                self.media_candidates.append({
                    "url": src.strip(),
                    "kind_hint": "video",
                    "title_hint": attr_map.get("title") or "",
                    "poster": poster,
                })
            return

        if tag_l == "source":
            src = _pick_best_srcset(attr_map.get("srcset")) or attr_map.get("src") or attr_map.get("data-src")
            if src:
                type_attr = attr_map.get("type", "").lower()
                hint = "video" if "video" in type_attr or "mpegurl" in type_attr else (
                    "audio" if "audio" in type_attr else "image"
                )
                self.media_candidates.append({
                    "url": src.strip(),
                    "kind_hint": hint,
                    "title_hint": "",
                    "poster": None,
                })
            return

        if tag_l == "audio":
            src = attr_map.get("src") or attr_map.get("data-src")
            if src:
                self.media_candidates.append({
                    "url": src.strip(),
                    "kind_hint": "audio",
                    "title_hint": attr_map.get("title") or "",
                    "poster": None,
                })
            return

        if tag_l == "a":
            href = attr_map.get("href", "").strip()
            if href:
                resolved = normalize_crawl_url(href, self.base_url, force_https=False)
                if resolved:
                    if is_media_url(resolved):
                        self.media_candidates.append({
                            "url": resolved,
                            "kind_hint": "",
                            "title_hint": attr_map.get("title") or "",
                            "poster": None,
                        })
                    elif _is_crawlable_page_url(resolved):
                        self.page_links.append(resolved)

    def handle_endtag(self, tag):
        if tag.lower() == "title":
            self.in_title = False

    def handle_data(self, data):
        if self.in_title and data:
            self.title_chunks.append(data)

    @property
    def page_title(self):
        return sanitize_filename("".join(self.title_chunks).strip(), fallback="")


def extract_page_links_and_media(html_text, page_url):
    """Parses HTML text and returns (page_title, media_items, page_links)."""
    parser = _MediaAndLinkParser(page_url)
    try:
        parser.feed(html_text or "")
        parser.close()
    except Exception:
        pass
    return parser.page_title, parser.media_candidates, parser.page_links


def _build_opener(proxy=None):
    handlers = []
    if proxy:
        handlers.append(urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
    return urllib.request.build_opener(*handlers)


def _http_get_text(url, headers=None, timeout=20, proxy=None, retries=3, retry_delay=1.5):
    """Fetches a URL with retry logic (mirrors scrape_recursive_full_site's 3-attempt retry)."""
    req_headers = {"User-Agent": DEFAULT_USER_AGENT}
    if headers:
        req_headers.update(headers)
    opener = _build_opener(proxy=proxy)
    last_err = None
    attempts = max(1, int(retries))
    for attempt in range(attempts):
        try:
            req = urllib.request.Request(url, headers=req_headers)
            with opener.open(req, timeout=timeout) as resp:
                final_url = resp.geturl() or url
                content_type = (resp.headers.get("Content-Type") or "").lower()
                raw_bytes = resp.read(4 * 1024 * 1024)
                charset = "utf-8"
                m = re.search(r"charset=([^\s;]+)", content_type)
                if m:
                    charset = m.group(1).strip('"\'')
                text = raw_bytes.decode(charset, errors="replace")
                return {
                    "ok": True,
                    "url": final_url,
                    "status": getattr(resp, "status", 200),
                    "content_type": content_type,
                    "text": text,
                }
        except Exception as exc:
            last_err = str(exc)
            if attempt + 1 < attempts and retry_delay > 0:
                time.sleep(min(retry_delay, 3.0))
    return {"ok": False, "url": url, "status": 0, "content_type": "", "text": "", "error": last_err or "Request failed"}


def _build_scraped_item(media_url, page_url, engine_label, title_hint="", kind_hint="", poster=None, extra_headers=None):
    cat, mime, ext = guess_media_type(media_url)
    if cat is None:
        if kind_hint == "video":
            cat, mime, ext = "video", "video/mp4", ".mp4"
        elif kind_hint == "audio":
            cat, mime, ext = "audio", "audio/mpeg", ".mp3"
        elif kind_hint == "image":
            cat, mime, ext = "image", "image/jpeg", ".jpg"
        else:
            return None

    parsed = urllib.parse.urlsplit(media_url)
    url_basename = os.path.basename(parsed.path.rstrip("/"))
    raw_title = title_hint.strip() if title_hint else (urllib.parse.unquote(url_basename) or "media")
    clean_title = sanitize_filename(raw_title, fallback="media")

    is_stream = cat == "stream"
    is_video = cat == "video"
    is_audio = cat == "audio"
    fmt = ext.lstrip(".").upper() if ext else ("HLS" if is_stream else "MP4" if is_video else "MP3" if is_audio else "JPG")

    headers = {
        "User-Agent": DEFAULT_USER_AGENT,
        "Referer": page_url,
    }
    if extra_headers:
        headers.update(extra_headers)

    return {
        "url": media_url,
        "title": clean_title,
        "isVideo": is_video,
        "isAudio": is_audio,
        "isStream": is_stream,
        "format": fmt,
        "mimeType": mime or "application/octet-stream",
        "thumbnailUrl": poster or (media_url if cat == "image" else None),
        "source": engine_label,
        "pageUrl": page_url,
        "headers": headers,
    }


def scrape_recursive(url, options_json="{}"):
    """
    Depth-limited & full-site BFS media crawler (mitchlinDEV/media-scraper).
    Options:
      - maxDepth (int, default 3)
      - maxPages (int, default 20)
      - sameDomainOnly (bool, default True)
      - scrollPause (float seconds, default 0.5)
      - retries (int, default 3)
      - timeout (int seconds, default 20)
      - cookies (str, optional)
      - proxy (str, optional)
      - sitemapPath (str, optional path to write sitemap.txt)
      - mode ('silent' | 'normal' | 'verbose', default 'normal')
    """
    try:
        opts = json.loads(options_json) if options_json else {}
    except Exception:
        opts = {}

    start_url = normalize_crawl_url(url, force_https=True)
    if not start_url:
        return json.dumps({
            "success": False,
            "engine": "media-scraper",
            "error": "Invalid URL for recursive scrape",
            "items": [],
            "sitemap": [],
        })

    max_depth = max(0, min(int(opts.get("maxDepth", 3)), 5))
    max_pages = max(1, min(int(opts.get("maxPages", 20)), 100))
    same_domain_only = bool(opts.get("sameDomainOnly", True))
    scroll_pause = max(0.0, min(float(opts.get("scrollPause", 0.5)), 5.0))
    retries = max(1, min(int(opts.get("retries", 3)), 5))
    timeout = max(5, min(int(opts.get("timeout", 20)), 60))
    cookies = opts.get("cookies") or ""
    proxy = opts.get("proxy") or None
    sitemap_path = opts.get("sitemapPath") or ""

    base_host = urllib.parse.urlsplit(start_url).netloc.lower()
    queue = collections.deque([(start_url, 0)])
    visited = set()
    sitemap = []
    seen_media = set()
    items = []
    first_page_title = ""
    captcha_detected = False
    captcha_url = None
    last_error = None

    req_headers = {"User-Agent": DEFAULT_USER_AGENT}
    if cookies:
        req_headers["Cookie"] = cookies

    while queue and len(visited) < max_pages:
        current_url, depth = queue.popleft()
        if current_url in visited:
            continue
        visited.add(current_url)
        sitemap.append(current_url)

        res = _http_get_text(
            current_url,
            headers=req_headers,
            timeout=timeout,
            proxy=proxy,
            retries=retries,
            retry_delay=min(scroll_pause, 2.0),
        )
        if not res["ok"]:
            last_error = res.get("error")
            continue

        content_type = res["content_type"]
        if content_type.startswith(("image/", "video/", "audio/")) or "mpegurl" in content_type:
            item = _build_scraped_item(res["url"], current_url, "media-scraper", extra_headers=req_headers)
            if item and item["url"] not in seen_media:
                seen_media.add(item["url"])
                items.append(item)
            continue

        page_html = res["text"]
        if detect_captcha(page_html):
            captcha_detected = True
            captcha_url = res["url"]
            # Pause crawling this page so user can complete verification in browser
            continue

        page_title, media_candidates, page_links = extract_page_links_and_media(page_html, res["url"])
        if not first_page_title and page_title:
            first_page_title = page_title

        for cand in media_candidates:
            resolved_media = normalize_crawl_url(cand["url"], base_url=res["url"], force_https=False)
            if not resolved_media or resolved_media in seen_media:
                continue
            item = _build_scraped_item(
                resolved_media,
                res["url"],
                "media-scraper (Recursive)",
                title_hint=cand.get("title_hint", ""),
                kind_hint=cand.get("kind_hint", ""),
                poster=normalize_crawl_url(cand["poster"], base_url=res["url"], force_https=False) if cand.get("poster") else None,
                extra_headers=req_headers,
            )
            if item:
                seen_media.add(resolved_media)
                items.append(item)

        if depth < max_depth:
            for link in page_links:
                norm_link = normalize_crawl_url(link, base_url=res["url"], force_https=True)
                if not norm_link or norm_link in visited:
                    continue
                if same_domain_only:
                    link_host = urllib.parse.urlsplit(norm_link).netloc.lower()
                    if link_host != base_host:
                        continue
                queue.append((norm_link, depth + 1))

        if scroll_pause > 0 and queue and len(visited) < max_pages:
            time.sleep(min(scroll_pause, 1.5))

    if sitemap_path and sitemap:
        try:
            safe_makedirs(os.path.dirname(sitemap_path))
            with open(sitemap_path, "w", encoding="utf-8") as fh:
                fh.write("\n".join(sitemap) + "\n")
        except Exception:
            pass

    return json.dumps({
        "success": len(items) > 0,
        "engine": "media-scraper",
        "extractor": "media-scraper (Recursive BFS)",
        "pageTitle": first_page_title or base_host,
        "pagesCrawled": len(visited),
        "sitemap": sitemap,
        "captchaDetected": captcha_detected,
        "captchaUrl": captcha_url,
        "error": (
            f"CAPTCHA verification detected at {captcha_url}"
            if captcha_detected and not items
            else (None if items else (last_error or "No media found across crawled pages"))
        ),
        "items": items,
    })


def select_best_m3u8_variant(playlist_text, base_url):
    """
    Port of util/twitter.py's twitter_m3u8() variant selector.
    Parses #EXT-X-STREAM-INF lines and selects the variant with the highest
    pixel area (width * height), falling back to highest BANDWIDTH.
    """
    if not playlist_text:
        return None
    lines = [ln.strip() for ln in playlist_text.splitlines() if ln.strip()]
    best_url = None
    best_pixels = -1
    best_bandwidth = -1

    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("#EXT-X-STREAM-INF"):
            pixels = 0
            bandwidth = 0
            m_res = re.search(r"RESOLUTION=(\d+)x(\d+)", line, re.IGNORECASE)
            if m_res:
                pixels = int(m_res.group(1)) * int(m_res.group(2))
            m_bw = re.search(r"BANDWIDTH=(\d+)", line, re.IGNORECASE)
            if m_bw:
                bandwidth = int(m_bw.group(1))
            if i + 1 < len(lines) and not lines[i + 1].startswith("#"):
                candidate = urllib.parse.urljoin(base_url, lines[i + 1])
                if (pixels > best_pixels) or (pixels == best_pixels and bandwidth > best_bandwidth):
                    best_pixels = pixels
                    best_bandwidth = bandwidth
                    best_url = candidate
                i += 2
                continue
        i += 1
    return best_url


def extract_twitter_video(url, options_json="{}"):
    """
    Lightweight Twitter/X media & highest-resolution HLS (.m3u8) extractor
    inspired by mitchlinDEV/media-scraper's util/twitter.py.
    """
    try:
        opts = json.loads(options_json) if options_json else {}
    except Exception:
        opts = {}

    m_id = re.search(r"(?:twitter\.com|x\.com)/[^/]+/status/(\d+)", str(url), re.IGNORECASE)
    if not m_id:
        return json.dumps({
            "success": False,
            "engine": "media-scraper-twitter",
            "error": "No Twitter/X status ID found in URL",
            "items": [],
        })

    tweet_id = m_id.group(1)
    timeout = max(5, min(int(opts.get("timeout", 20)), 60))
    proxy = opts.get("proxy") or None
    items = []
    page_title = f"Tweet {tweet_id}"

    # 1. Public Twitter syndication endpoint (no auth required)
    syndication_url = f"https://cdn.syndication.twimg.com/tweet-result?id={tweet_id}&token=a"
    res = _http_get_text(syndication_url, timeout=timeout, proxy=proxy, retries=2, retry_delay=0.5)
    if res["ok"]:
        try:
            data = json.loads(res["text"])
            user = (data.get("user") or {}).get("screen_name") or ""
            text = (data.get("text") or "").strip()
            if user:
                page_title = sanitize_filename(f"@{user} - {text[:60]}", fallback=page_title)

            # Photos
            for idx, photo in enumerate(data.get("photos") or []):
                p_url = photo.get("url")
                if p_url:
                    items.append({
                        "url": p_url,
                        "title": sanitize_filename(f"{tweet_id}_photo_{idx + 1}.jpg"),
                        "isVideo": False,
                        "isAudio": False,
                        "isStream": False,
                        "format": "JPG",
                        "mimeType": "image/jpeg",
                        "thumbnailUrl": p_url,
                        "source": "media-scraper (Twitter)",
                        "pageUrl": url,
                        "headers": {"User-Agent": DEFAULT_USER_AGENT, "Referer": "https://x.com/"},
                    })

            # Video variants (MP4 + HLS .m3u8)
            video = data.get("video") or {}
            poster = video.get("poster")
            variants = video.get("variants") or []
            mp4_variants = [v for v in variants if "mp4" in (v.get("type") or "").lower() and v.get("src")]
            m3u8_variants = [v for v in variants if "mpegurl" in (v.get("type") or "").lower() and v.get("src")]

            if mp4_variants:
                # Pick highest bitrate/resolution MP4
                mp4_variants.sort(key=lambda v: int(v.get("bitrate") or 0), reverse=True)
                best_mp4 = mp4_variants[0]["src"]
                items.append({
                    "url": best_mp4,
                    "title": sanitize_filename(f"{tweet_id}_video.mp4"),
                    "isVideo": True,
                    "isAudio": False,
                    "isStream": False,
                    "format": "MP4",
                    "mimeType": "video/mp4",
                    "thumbnailUrl": poster,
                    "source": "media-scraper (Twitter)",
                    "pageUrl": url,
                    "headers": {"User-Agent": DEFAULT_USER_AGENT, "Referer": "https://x.com/"},
                })
            elif m3u8_variants:
                master_m3u8 = m3u8_variants[0]["src"]
                m3u8_res = _http_get_text(master_m3u8, timeout=timeout, proxy=proxy, retries=2, retry_delay=0.5)
                best_hls = select_best_m3u8_variant(m3u8_res["text"], master_m3u8) if m3u8_res["ok"] else master_m3u8
                items.append({
                    "url": best_hls or master_m3u8,
                    "title": sanitize_filename(f"{tweet_id}_video.m3u8"),
                    "isVideo": False,
                    "isAudio": False,
                    "isStream": True,
                    "format": "HLS",
                    "mimeType": "application/x-mpegURL",
                    "thumbnailUrl": poster,
                    "source": "media-scraper (Twitter HLS)",
                    "pageUrl": url,
                    "headers": {"User-Agent": DEFAULT_USER_AGENT, "Referer": "https://x.com/"},
                })
        except Exception:
            pass

    return json.dumps({
        "success": len(items) > 0,
        "engine": "media-scraper-twitter",
        "extractor": "media-scraper (Twitter)",
        "pageTitle": page_title,
        "error": None if items else "Could not extract Twitter/X media via syndication",
        "items": items,
    })


def extract_tumblr(url, options_json="{}"):
    """
    Lightweight HTTP Tumblr extractor (mirrors m_scraper/rq/tumblrer.py).
    Supports <blog>.tumblr.com and tumblr.com/<blog> via API v1 JSON + HTML fallback.
    """
    try:
        opts = json.loads(options_json) if options_json else {}
    except Exception:
        opts = {}

    timeout = max(5, min(int(opts.get("timeout", 20)), 60))
    proxy = opts.get("proxy") or None
    limit = max(1, min(int(opts.get("limit") or 25), 50))

    parsed = urllib.parse.urlsplit(str(url))
    host = parsed.netloc.lower()
    blog_name = None
    if host.endswith(".tumblr.com") and host not in ("www.tumblr.com", "assets.tumblr.com"):
        blog_name = host.split(".")[0]
    else:
        parts = [p for p in parsed.path.split("/") if p]
        if parts and parts[0] not in ("post", "tagged", "search", "explore"):
            blog_name = parts[0]

    items = []
    seen = set()
    page_title = f"Tumblr - {blog_name}" if blog_name else "Tumblr"

    if blog_name:
        api_url = f"https://{blog_name}.tumblr.com/api/read/json?num={limit}"
        res = _http_get_text(api_url, timeout=timeout, proxy=proxy, retries=2, retry_delay=0.5)
        if res["ok"] and res["text"]:
            raw = res["text"].strip()
            # Strip JSONP wrapper: var tumblr_api_read = {...};
            raw = re.sub(r"^var\s+tumblr_api_read\s*=\s*", "", raw)
            raw = raw.rstrip(";")
            try:
                data = json.loads(raw)
                tumblelog = data.get("tumblelog") or {}
                if tumblelog.get("title"):
                    page_title = sanitize_filename(tumblelog["title"], fallback=page_title)
                for post in data.get("posts") or []:
                    post_id = str(post.get("id") or "")
                    post_url = post.get("url-with-slug") or post.get("url") or url
                    # Single photo or photoset
                    photo_urls = []
                    for key in ("photo-url-1280", "photo-url-500", "photo-url-400"):
                        if post.get(key):
                            photo_urls.append(post[key])
                            break
                    for p in post.get("photos") or []:
                        for key in ("photo-url-1280", "photo-url-500"):
                            if p.get(key):
                                photo_urls.append(p[key])
                                break
                    for idx, p_url in enumerate(photo_urls):
                        if p_url not in seen:
                            seen.add(p_url)
                            item = _build_scraped_item(
                                p_url, post_url, "media-scraper (Tumblr)",
                                title_hint=f"{blog_name}_{post_id}_{idx + 1}",
                                kind_hint="image",
                            )
                            if item:
                                items.append(item)
                    # Embedded video URLs in video-player
                    vp = post.get("video-player") or ""
                    for m_vid in re.finditer(r'https?://[^\s"\'<>]+\.mp4(?:\?[^\s"\'<>]*)?', vp):
                        v_url = html.unescape(m_vid.group(0))
                        if v_url not in seen:
                            seen.add(v_url)
                            item = _build_scraped_item(
                                v_url, post_url, "media-scraper (Tumblr)",
                                title_hint=f"{blog_name}_{post_id}",
                                kind_hint="video",
                            )
                            if item:
                                items.append(item)
            except Exception:
                pass

    return json.dumps({
        "success": len(items) > 0,
        "engine": "media-scraper-tumblr",
        "extractor": "media-scraper (Tumblr)",
        "pageTitle": page_title,
        "error": None if items else "No Tumblr media extracted",
        "items": items,
    })


def extract_pixiv(url, options_json="{}"):
    """
    Lightweight HTTP Pixiv extractor (mirrors m_scraper/rq/pixiver.py).
    Supports pixiv.net/artworks/<id> and pixiv.net/en/artworks/<id> via Ajax API
    with mandatory Referer: https://www.pixiv.net/ download headers.
    """
    try:
        opts = json.loads(options_json) if options_json else {}
    except Exception:
        opts = {}

    m_id = re.search(r"pixiv\.net/(?:[a-z]{2}/)?artworks/(\d+)", str(url), re.IGNORECASE)
    if not m_id:
        m_id = re.search(r"illust_id=(\d+)", str(url), re.IGNORECASE)
    if not m_id:
        return json.dumps({
            "success": False,
            "engine": "media-scraper-pixiv",
            "error": "No Pixiv artwork ID found in URL",
            "items": [],
        })

    illust_id = m_id.group(1)
    timeout = max(5, min(int(opts.get("timeout", 20)), 60))
    proxy = opts.get("proxy") or None
    cookies = opts.get("cookies") or ""

    pixiv_headers = {
        "User-Agent": DEFAULT_USER_AGENT,
        "Referer": "https://www.pixiv.net/",
    }
    if cookies:
        pixiv_headers["Cookie"] = cookies

    pages_url = f"https://www.pixiv.net/ajax/illust/{illust_id}/pages"
    res = _http_get_text(pages_url, headers=pixiv_headers, timeout=timeout, proxy=proxy, retries=2, retry_delay=0.5)
    items = []
    if res["ok"]:
        try:
            payload = json.loads(res["text"])
            if not payload.get("error"):
                for idx, page in enumerate(payload.get("body") or []):
                    urls_obj = page.get("urls") or {}
                    orig = urls_obj.get("original") or urls_obj.get("regular") or urls_obj.get("small")
                    thumb = urls_obj.get("small") or urls_obj.get("thumb_mini") or orig
                    if orig:
                        item = _build_scraped_item(
                            orig,
                            f"https://www.pixiv.net/artworks/{illust_id}",
                            "media-scraper (Pixiv)",
                            title_hint=f"pixiv_{illust_id}_p{idx}",
                            kind_hint="image",
                            poster=thumb,
                            extra_headers=pixiv_headers,
                        )
                        if item:
                            items.append(item)
        except Exception:
            pass

    return json.dumps({
        "success": len(items) > 0,
        "engine": "media-scraper-pixiv",
        "extractor": "media-scraper (Pixiv)",
        "pageTitle": f"Pixiv Artwork {illust_id}",
        "error": None if items else "Pixiv artwork requires login cookie (PHPSESSID) or was unavailable",
        "items": items,
    })


def extract_facebook(url, options_json="{}"):
    """
    Lightweight public Facebook media extractor (mirrors mediascrapers.py FacebookScraper).
    Extracts browser_native_hd_url / browser_native_sd_url / OpenGraph images & videos.
    """
    try:
        opts = json.loads(options_json) if options_json else {}
    except Exception:
        opts = {}

    timeout = max(5, min(int(opts.get("timeout", 20)), 60))
    proxy = opts.get("proxy") or None
    cookies = opts.get("cookies") or ""
    headers = {"User-Agent": DEFAULT_USER_AGENT}
    if cookies:
        headers["Cookie"] = cookies

    res = _http_get_text(url, headers=headers, timeout=timeout, proxy=proxy, retries=2, retry_delay=0.5)
    if not res["ok"]:
        return json.dumps({
            "success": False,
            "engine": "media-scraper-facebook",
            "error": res.get("error") or "Failed to fetch Facebook page",
            "items": [],
        })

    body = res["text"]
    items = []
    seen = set()

    for key, label in (
        ("browser_native_hd_url", "HD"),
        ("playable_url_quality_hd", "HD"),
        ("browser_native_sd_url", "SD"),
        ("playable_url", "SD"),
    ):
        for m in re.finditer(rf'"{key}"\s*:\s*"(https?:[^"]+)"', body):
            raw_u = m.group(1).replace("\\/", "/").replace("\\u0025", "%").replace("\\u0026", "&")
            if raw_u not in seen:
                seen.add(raw_u)
                item = _build_scraped_item(
                    raw_u, res["url"], f"media-scraper (Facebook {label})",
                    title_hint=f"facebook_video_{label.lower()}.mp4",
                    kind_hint="video",
                    extra_headers=headers,
                )
                if item:
                    items.append(item)

    page_title, media_cands, _ = extract_page_links_and_media(body, res["url"])
    for cand in media_cands:
        resolved = normalize_crawl_url(cand["url"], base_url=res["url"], force_https=False)
        if resolved and resolved not in seen:
            item = _build_scraped_item(
                resolved, res["url"], "media-scraper (Facebook)",
                title_hint=cand.get("title_hint", ""),
                kind_hint=cand.get("kind_hint", ""),
                extra_headers=headers,
            )
            if item:
                seen.add(resolved)
                items.append(item)

    return json.dumps({
        "success": len(items) > 0,
        "engine": "media-scraper-facebook",
        "extractor": "media-scraper (Facebook)",
        "pageTitle": page_title or "Facebook Media",
        "error": None if items else "No public Facebook media found",
        "items": items,
    })
