"""
Pinterest media extractor powered by sean1832/pinterest-dl for Emre Gallery (Chaquopy Python).

Uses `pinterest-dl` (`PinterestDL.with_api()`) as the primary extraction engine for
Pinterest pins, boards, board sections, profiles, search queries, and `pin.it` short links,
with resilient multi-tier fallbacks that work inside Android's Chaquopy sandbox (no Playwright,
no subprocess/ffmpeg calls in Python):
  1. Official `pinterest-dl` Python API (`PinterestDL.with_api()`), supporting optional
     session cookies for private boards/pins and extracting both full-resolution images
     and video streams (MP4 / HLS .m3u8).
  2. Direct Pinterest internal Resource API (`PinResource`, `BoardResource`/`BoardFeedResource`,
     `BoardSectionsResource`/`BoardSectionPinsResource`, `BaseSearchResource`,
     `UserActivityPinsResource`) with full Idea/Story Pin and Carousel Pin parsing.
  3. Logged-out HTML (`__PWS_DATA__`, Relay JSON, JSON-LD, OpenGraph), Board/Profile RSS
     feeds, and official Pinterest oEmbed fallback when internal API endpoints return 403.
"""

import hashlib
import html as html_lib
import inspect
import json
import re
import urllib.parse
import xml.etree.ElementTree as ET

import requests

try:
    import pinterest_dl
    from pinterest_dl import PinterestDL
    PINTEREST_DL_LIB_AVAILABLE = True
except Exception:
    pinterest_dl = None
    PinterestDL = None
    PINTEREST_DL_LIB_AVAILABLE = False


DEFAULT_DESKTOP_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)

PINTEREST_HOST_RE = re.compile(
    r"^(?:[a-z0-9-]+\.)*pinterest\.(?:com|co\.uk|ca|de|fr|es|it|jp|ch|at|pt|se|dk|ie|nz|ph|cl|co\.kr|ru|com\.au|com\.mx|com\.br|co\.in|nl|pl|be|no|fi|cz|hu|ro|gr|tr|id|my|sg|vn|th|tw|hk)$",
    re.IGNORECASE,
)

RESERVED_ROOT_PATHS = {
    "pin", "search", "ideas", "today", "explore", "videos", "watch",
    "business", "settings", "login", "logout", "signup", "join",
    "about", "legal", "privacy", "terms", "help", "resource",
    "categories", "topics", "shopping", "shop", "news_hub", "homefeed",
    "password", "convert", "oembed.json",
}

PINIMG_HEX_RE = re.compile(r"([0-9a-fA-F]{32})(?:\.([a-zA-Z0-9]+))?")
PINIMG_SIZE_RE = re.compile(r"/(?:\d+x\d*|\d*x\d+|originals)/", re.IGNORECASE)


def is_pinterest_dl_available():
    """Returns True if the Pinterest extractor (library or fallback) is available."""
    return True


def is_pinterest_dl_library_installed():
    """Returns True only if the `pinterest-dl` PyPI package is importable."""
    return bool(PINTEREST_DL_LIB_AVAILABLE)


def get_pinterest_dl_version():
    """Returns the installed `pinterest-dl` version or 'fallback'."""
    if PINTEREST_DL_LIB_AVAILABLE:
        ver = getattr(pinterest_dl, "__version__", None)
        if ver:
            return str(ver)
        try:
            from importlib.metadata import version as _pkg_version
            return _pkg_version("pinterest-dl")
        except Exception:
            return "1.3.0"
    return "fallback"


def is_pinterest_url(url):
    """Returns True if the URL points to Pinterest (any country TLD, pin.it, or pinimg.com)."""
    if not url or not isinstance(url, str):
        return False
    try:
        parsed = urllib.parse.urlparse(url.strip())
        host = (parsed.hostname or "").lower()
    except Exception:
        return False
    if not host:
        return False
    if host == "pin.it" or host.endswith(".pin.it"):
        return True
    if host == "pinimg.com" or host.endswith(".pinimg.com"):
        return True
    if PINTEREST_HOST_RE.match(host):
        return True
    # Catch any other regional pinterest.<tld>
    if re.match(r"^(?:[a-z0-9-]+\.)*pinterest\.[a-z]{2,}(?:\.[a-z]{2,})?$", host):
        return True
    return False


def parse_pinterest_url(url):
    """
    Parses a Pinterest URL into structured target info:
      - {"kind": "short", "code": "...", "url": ...}
      - {"kind": "pin", "pin_id": "...", "url": ...}
      - {"kind": "search", "query": "...", "url": ...}
      - {"kind": "section", "username": "...", "board_slug": "...", "section_slug": "...", "url": ...}
      - {"kind": "board", "username": "...", "board_slug": "...", "url": ...}
      - {"kind": "profile", "username": "...", "url": ...}
      - {"kind": "cdn", "is_video": bool, "url": ...}
    """
    if not is_pinterest_url(url):
        return {"kind": "unknown", "url": url or ""}

    clean = url.strip()
    parsed = urllib.parse.urlparse(clean)
    host = (parsed.hostname or "").lower()
    path = parsed.path or ""
    segments = [urllib.parse.unquote(s) for s in path.split("/") if s]

    if host == "pin.it" or host.endswith(".pin.it"):
        return {
            "kind": "short",
            "code": segments[0] if segments else "",
            "url": clean,
        }

    if host == "pinimg.com" or host.endswith(".pinimg.com"):
        is_vid = _is_pinimg_video_url(clean)
        return {"kind": "cdn", "is_video": is_vid, "url": clean}

    # Single Pin: /pin/<id>/ or /pin/<seo-slug>--<id>/
    if len(segments) >= 2 and segments[0].lower() == "pin":
        raw_pin = segments[1]
        # Handle SEO slugged pins like /pin/my-pin-title--123456789012345678/
        if "--" in raw_pin:
            candidate = raw_pin.rsplit("--", 1)[-1]
        else:
            candidate = raw_pin
        m = re.search(r"(\d{6,})", candidate)
        pin_id = m.group(1) if m else candidate
        return {
            "kind": "pin",
            "pin_id": pin_id,
            "url": f"https://www.pinterest.com/pin/{pin_id}/",
        }

    # Search: /search/pins/?q=... or /search/?q=...
    if segments and segments[0].lower() == "search":
        qs = urllib.parse.parse_qs(parsed.query)
        query = (qs.get("q") or [""])[0].strip()
        if query:
            return {
                "kind": "search",
                "query": query,
                "url": clean,
            }

    # Ideas / Topics pages (treat like search/HTML)
    if segments and segments[0].lower() == "ideas":
        topic = segments[1].replace("-", " ") if len(segments) >= 2 else ""
        return {
            "kind": "ideas",
            "query": topic,
            "url": clean,
        }

    # Board Section / Board / Profile
    if segments and segments[0].lower() not in RESERVED_ROOT_PATHS:
        username = segments[0]
        if len(segments) >= 3 and segments[1].lower() not in ("_saved", "_created", "pins"):
            board_slug = segments[1]
            section_slug = segments[2]
            return {
                "kind": "section",
                "username": username,
                "board_slug": board_slug,
                "section_slug": section_slug,
                "url": clean,
            }
        if len(segments) >= 2 and segments[1].lower() not in ("_saved", "_created", "pins", "boards"):
            board_slug = segments[1]
            return {
                "kind": "board",
                "username": username,
                "board_slug": board_slug,
                "url": clean,
            }
        return {
            "kind": "profile",
            "username": username,
            "url": clean,
        }

    return {"kind": "unknown", "url": clean}


def _is_pinimg_video_url(url):
    """Returns True if a pinimg.com URL is a video or HLS stream (never rewrite to i.pinimg.com/originals)."""
    if not url:
        return False
    lower = url.lower()
    if "/videos/" in lower:
        return True
    if re.search(r"^https?://v\d*\.pinimg\.com/", lower):
        return True
    base = lower.split("?", 1)[0].split("#", 1)[0]
    return base.endswith((".mp4", ".m3u8", ".ts", ".webm", ".mov", ".m4v"))


def upgrade_pinimg_image_url(url):
    """
    Upgrades a Pinterest thumbnail image URL (e.g. /236x/, /474x/, /736x/) to /originals/
    while NEVER touching video URLs (`v1.pinimg.com`, `/videos/`, `.mp4`, `.m3u8`) and
    preserving already-original URLs and their native extensions (`.png`, `.webp`, `.gif`, `.jpg`).
    """
    if not url or not isinstance(url, str):
        return url
    clean = url.strip()
    if "pinimg.com" not in clean.lower():
        return clean
    if _is_pinimg_video_url(clean):
        return clean
    if "/originals/" in clean:
        return clean
    base = clean.split("?", 1)[0].split("#", 1)[0]
    m = PINIMG_HEX_RE.search(base)
    if not m:
        return clean
    hex_hash = m.group(1)
    raw_ext = (m.group(2) or "jpg").lower()
    # Preserve gif/png/jpg; thumbnails served as webp/jpeg usually have jpg/png originals
    ext = "jpg" if raw_ext in ("jpeg", "webp") else raw_ext
    p1, p2, p3 = hex_hash[0:2], hex_hash[2:4], hex_hash[4:6]
    return f"https://i.pinimg.com/originals/{p1}/{p2}/{p3}/{hex_hash}.{ext}"


def _parse_cookie_string(cookie_str):
    cookies = {}
    if not cookie_str or not isinstance(cookie_str, str):
        return cookies
    for part in cookie_str.split(";"):
        part = part.strip()
        if not part or "=" not in part:
            continue
        k, v = part.split("=", 1)
        k = k.strip()
        v = v.strip()
        if k:
            cookies[k] = v
    return cookies


def _cookies_dict_to_list(cookies_dict):
    """Converts a flat cookie dict into Playwright/Selenium/pinterest-dl cookie list format."""
    return [
        {
            "name": k,
            "value": v,
            "domain": ".pinterest.com",
            "path": "/",
        }
        for k, v in (cookies_dict or {}).items()
        if k and v is not None
    ]


def _guess_extension(url, is_video=False, is_stream=False):
    if is_stream:
        return "m3u8"
    try:
        path = urllib.parse.urlparse(url).path.lower()
        for ext in ("mp4", "webm", "mov", "m3u8", "ts", "gif", "png", "webp", "jpg", "jpeg"):
            if path.endswith("." + ext):
                return "jpg" if ext == "jpeg" else ext
    except Exception:
        pass
    return "mp4" if is_video else "jpg"


def _mime_for_ext(ext, is_video=False, is_stream=False):
    if is_stream or ext == "m3u8":
        return "application/vnd.apple.mpegurl"
    if is_video:
        if ext == "ts":
            return "video/mp2t"
        return f"video/{ext}"
    return f"image/{'jpeg' if ext == 'jpg' else ext}"


def _sanitize_filename_part(text, max_len=48):
    if not text:
        return ""
    cleaned = re.sub(r"[^a-zA-Z0-9._ -]+", "", str(text)).strip()
    cleaned = re.sub(r"\s+", "_", cleaned)
    return cleaned[:max_len].strip("_")


def _make_item(
    url,
    is_video=False,
    is_stream=False,
    pin_id="",
    index=0,
    caption="",
    thumbnail_url=None,
    width=None,
    height=None,
    page_url="",
    source="pinterest-dl",
):
    ext = _guess_extension(url, is_video=is_video, is_stream=is_stream)
    slug = _sanitize_filename_part(caption, max_len=36)
    if pin_id and slug:
        base = f"pin_{pin_id}_{slug}"
    elif pin_id:
        base = f"pin_{pin_id}_{index + 1}" if index > 0 else f"pin_{pin_id}"
    elif slug:
        base = f"pinterest_{slug}_{index + 1}"
    else:
        base = f"pinterest_{index + 1}"

    title = f"{base}.{ext}"
    url_hash = hashlib.sha256(url.encode("utf-8")).hexdigest()[:12]
    w = int(width) if isinstance(width, (int, float)) and width > 0 else None
    h = int(height) if isinstance(height, (int, float)) and height > 0 else None
    res_hint = f"{w}x{h}" if w and h else None

    return {
        "id": f"pin_{index}_{url_hash}",
        "url": url,
        "title": title,
        "category": "VIDEOS" if is_video else "IMAGES",
        "format": ext.upper(),
        "mimeType": _mime_for_ext(ext, is_video=is_video, is_stream=is_stream),
        "isVideo": bool(is_video),
        "isAudio": False,
        "isStream": bool(is_stream),
        "thumbnailUrl": thumbnail_url or (None if is_video else url),
        "width": w,
        "height": h,
        "resolutionHint": res_hint,
        "sizeBytes": None,
        "source": source,
        "pageUrl": page_url,
    }


def _dedupe_items(items):
    """Deduplicates extracted items by URL, keeping video and image entries distinct."""
    seen = set()
    out = []
    for item in items:
        u = (item.get("url") or "").strip()
        if not u:
            continue
        key = ("v" if item.get("isVideo") else "i", u)
        if key in seen:
            continue
        seen.add(key)
        out.append(item)
    return out


# ---------------------------------------------------------------------------
# Pin / Video / Story / Carousel JSON Parsing (shared by Tier 2 & Tier 3)
# ---------------------------------------------------------------------------

def _pick_best_video_from_list(video_list):
    """
    Selects the best video candidate from Pinterest's `video_list` dict
    (e.g. V_720P, V_EXP7, V_EXP6, V_HLSV4, V_HLSV3_WEB, V_HLSV3_MOBILE).
    Prefers highest-resolution direct MP4; falls back to highest-resolution HLS (.m3u8).
    """
    if not isinstance(video_list, dict) or not video_list:
        return None

    mp4_candidates = []
    hls_candidates = []

    for key, info in video_list.items():
        if not isinstance(info, dict):
            continue
        v_url = info.get("url")
        if not v_url or not isinstance(v_url, str):
            continue
        w = info.get("width") or 0
        h = info.get("height") or 0
        try:
            score = int(w) * int(h)
        except Exception:
            score = 0
        # Slight tie-break boost for V_720P / V_EXP7
        k_upper = str(key).upper()
        if "720" in k_upper or "1080" in k_upper:
            score += 100
        elif "HLSV4" in k_upper:
            score += 50

        entry = {
            "url": v_url,
            "width": int(w) if w else None,
            "height": int(h) if h else None,
            "thumbnail": info.get("thumbnail"),
            "score": score,
            "is_stream": ".m3u8" in v_url.lower(),
        }
        if entry["is_stream"]:
            hls_candidates.append(entry)
        elif ".mp4" in v_url.lower() or "/videos/" in v_url.lower():
            mp4_candidates.append(entry)

    if mp4_candidates:
        mp4_candidates.sort(key=lambda x: x["score"], reverse=True)
        return mp4_candidates[0]
    if hls_candidates:
        hls_candidates.sort(key=lambda x: x["score"], reverse=True)
        return hls_candidates[0]
    return None


def _pick_best_image_from_dict(images_dict):
    """Selects `orig` or the highest-resolution image from Pinterest's `images` dict."""
    if not isinstance(images_dict, dict) or not images_dict:
        return None

    orig = images_dict.get("orig")
    if isinstance(orig, dict) and orig.get("url"):
        return {
            "url": orig["url"],
            "width": orig.get("width"),
            "height": orig.get("height"),
        }

    best = None
    best_score = -1
    for _k, info in images_dict.items():
        if not isinstance(info, dict) or not info.get("url"):
            continue
        w = info.get("width") or 0
        h = info.get("height") or 0
        try:
            score = int(w) * int(h)
        except Exception:
            score = 0
        if score >= best_score:
            best_score = score
            best = {
                "url": info["url"],
                "width": int(w) if w else None,
                "height": int(h) if h else None,
            }
    if best:
        best["url"] = upgrade_pinimg_image_url(best["url"])
    return best


def _extract_pin_caption(pin_data):
    if not isinstance(pin_data, dict):
        return "", ""
    title = (
        pin_data.get("title")
        or pin_data.get("grid_title")
        or pin_data.get("auto_alt_text")
        or ""
    )
    desc = (
        pin_data.get("description")
        or pin_data.get("closeup_unified_description")
        or pin_data.get("alt_text")
        or ""
    )
    return str(title).strip(), str(desc).strip()


def parse_pin_data(pin_data, page_url="", source="pinterest-dl-api", start_index=0):
    """
    Parses a single Pinterest pin JSON object into normalized media items.
    Handles:
      - Standard video pins (`videos.video_list`)
      - Idea / Story pins (`story_pin_data.pages[].blocks[]`)
      - Carousel pins (`carousel_data.carousel_slots[]`)
      - Standard image & GIF pins (`images.orig`)
    """
    if not isinstance(pin_data, dict):
        return [], "", "", ""

    pin_id = str(pin_data.get("id") or "")
    title, desc = _extract_pin_caption(pin_data)
    caption = title or desc
    items = []
    idx = start_index

    best_img = _pick_best_image_from_dict(pin_data.get("images"))
    poster_url = best_img["url"] if best_img else None

    # 1. Story / Idea Pins (multi-page videos and/or images)
    story_data = pin_data.get("story_pin_data")
    if isinstance(story_data, dict):
        pages = story_data.get("pages") or []
        for page in pages:
            if not isinstance(page, dict):
                continue
            page_found = False
            blocks = page.get("blocks") or []
            for block in blocks:
                if not isinstance(block, dict):
                    continue
                vid_obj = block.get("video")
                if isinstance(vid_obj, dict):
                    v_best = _pick_best_video_from_list(vid_obj.get("video_list"))
                    if v_best:
                        items.append(
                            _make_item(
                                url=v_best["url"],
                                is_video=True,
                                is_stream=v_best["is_stream"],
                                pin_id=pin_id,
                                index=idx,
                                caption=caption,
                                thumbnail_url=v_best.get("thumbnail") or poster_url,
                                width=v_best.get("width"),
                                height=v_best.get("height"),
                                page_url=page_url,
                                source=source,
                            )
                        )
                        idx += 1
                        page_found = True
                        continue
                img_obj = block.get("image")
                if isinstance(img_obj, dict):
                    img_best = _pick_best_image_from_dict(img_obj.get("images"))
                    if img_best:
                        items.append(
                            _make_item(
                                url=img_best["url"],
                                is_video=False,
                                pin_id=pin_id,
                                index=idx,
                                caption=caption,
                                thumbnail_url=img_best["url"],
                                width=img_best.get("width"),
                                height=img_best.get("height"),
                                page_url=page_url,
                                source=source,
                            )
                        )
                        idx += 1
                        page_found = True
            if not page_found:
                p_img = _pick_best_image_from_dict((page.get("image") or {}).get("images"))
                if p_img:
                    items.append(
                        _make_item(
                            url=p_img["url"],
                            is_video=False,
                            pin_id=pin_id,
                            index=idx,
                            caption=caption,
                            thumbnail_url=p_img["url"],
                            width=p_img.get("width"),
                            height=p_img.get("height"),
                            page_url=page_url,
                            source=source,
                        )
                    )
                    idx += 1

    # 2. Carousel Pins
    carousel_data = pin_data.get("carousel_data")
    if isinstance(carousel_data, dict):
        slots = carousel_data.get("carousel_slots") or []
        for slot in slots:
            if not isinstance(slot, dict):
                continue
            slot_img = _pick_best_image_from_dict(slot.get("images"))
            if slot_img:
                slot_title = slot.get("title") or caption
                items.append(
                    _make_item(
                        url=slot_img["url"],
                        is_video=False,
                        pin_id=pin_id,
                        index=idx,
                        caption=slot_title,
                        thumbnail_url=slot_img["url"],
                        width=slot_img.get("width"),
                        height=slot_img.get("height"),
                        page_url=page_url,
                        source=source,
                    )
                )
                idx += 1

    # 3. Standard Video Pin
    videos_obj = pin_data.get("videos")
    if isinstance(videos_obj, dict):
        v_best = _pick_best_video_from_list(videos_obj.get("video_list"))
        if v_best:
            items.append(
                _make_item(
                    url=v_best["url"],
                    is_video=True,
                    is_stream=v_best["is_stream"],
                    pin_id=pin_id,
                    index=idx,
                    caption=caption,
                    thumbnail_url=v_best.get("thumbnail") or poster_url,
                    width=v_best.get("width"),
                    height=v_best.get("height"),
                    page_url=page_url,
                    source=source,
                )
            )
            idx += 1

    # 4. Standard Image / GIF Pin (if not already populated by video/story/carousel)
    if not items and best_img:
        items.append(
            _make_item(
                url=best_img["url"],
                is_video=False,
                pin_id=pin_id,
                index=idx,
                caption=caption,
                thumbnail_url=best_img["url"],
                width=best_img.get("width"),
                height=best_img.get("height"),
                page_url=page_url,
                source=source,
            )
        )
        idx += 1

    items = _dedupe_items(items)
    preview = poster_url or (items[0].get("thumbnailUrl") if items else "") or ""
    page_title = title or (desc[:80] if desc else (f"Pinterest Pin ({pin_id})" if pin_id else "Pinterest"))
    return items, page_title, desc, preview


# ---------------------------------------------------------------------------
# Tier 1: Official sean1832/pinterest-dl Python Library
# ---------------------------------------------------------------------------

def _media_obj_to_items(media_obj, index, page_url):
    """
    Converts a `pinterest_dl` `PinterestMedia` (or dict) into normalized Emre Gallery item(s).
    Compatible across pinterest-dl 0.3.x through 1.3.x.
    """
    if media_obj is None:
        return []

    if isinstance(media_obj, dict):
        data = media_obj
    elif hasattr(media_obj, "to_dict") and callable(media_obj.to_dict):
        try:
            data = media_obj.to_dict()
        except Exception:
            data = vars(media_obj)
    else:
        data = {}
        for attr in ("id", "src", "alt", "origin", "resolution", "width", "height", "media_stream", "video_stream", "video_url"):
            if hasattr(media_obj, attr):
                data[attr] = getattr(media_obj, attr)

    pin_id = str(data.get("id") or "")
    alt = str(data.get("alt") or data.get("title") or "").strip()
    src = data.get("src") or data.get("url") or ""
    if isinstance(src, str) and src:
        src = upgrade_pinimg_image_url(src)

    width = data.get("width")
    height = data.get("height")
    res = data.get("resolution")
    if isinstance(res, (tuple, list)) and len(res) >= 2:
        width = width or res[0]
        height = height or res[1]

    # Inspect video stream (pinterest-dl 1.x attaches `media_stream` or `video_stream`)
    stream_obj = data.get("media_stream") or data.get("video_stream")
    video_url = data.get("video_url")
    v_width = width
    v_height = height

    if stream_obj is not None and not video_url:
        if isinstance(stream_obj, str):
            video_url = stream_obj
        elif isinstance(stream_obj, dict):
            video_url = (
                stream_obj.get("video_url")
                or stream_obj.get("url")
                or stream_obj.get("hls_url")
                or stream_obj.get("src")
            )
            v_width = stream_obj.get("width") or v_width
            v_height = stream_obj.get("height") or v_height
        else:
            video_url = (
                getattr(stream_obj, "video_url", None)
                or getattr(stream_obj, "url", None)
                or getattr(stream_obj, "hls_url", None)
                or getattr(stream_obj, "src", None)
            )
            v_width = getattr(stream_obj, "width", None) or v_width
            v_height = getattr(stream_obj, "height", None) or v_height

    items = []
    if video_url and isinstance(video_url, str) and video_url.startswith("http"):
        is_hls = ".m3u8" in video_url.lower()
        items.append(
            _make_item(
                url=video_url,
                is_video=True,
                is_stream=is_hls,
                pin_id=pin_id,
                index=index,
                caption=alt,
                thumbnail_url=src or None,
                width=v_width,
                height=v_height,
                page_url=data.get("origin") or page_url,
                source="pinterest-dl",
            )
        )
    elif src and isinstance(src, str) and src.startswith("http"):
        is_vid = _is_pinimg_video_url(src)
        items.append(
            _make_item(
                url=src,
                is_video=is_vid,
                is_stream=(".m3u8" in src.lower()),
                pin_id=pin_id,
                index=index,
                caption=alt,
                thumbnail_url=None if is_vid else src,
                width=width,
                height=height,
                page_url=data.get("origin") or page_url,
                source="pinterest-dl",
            )
        )
    return items


def _call_with_supported_kwargs(fn, *args, **kwargs):
    """Calls `fn` passing only the keyword arguments accepted by its signature."""
    try:
        sig = inspect.signature(fn)
        has_var_kw = any(
            p.kind == inspect.Parameter.VAR_KEYWORD for p in sig.parameters.values()
        )
        if has_var_kw:
            return fn(*args, **kwargs)
        filtered = {k: v for k, v in kwargs.items() if k in sig.parameters}
        return fn(*args, **filtered)
    except Exception:
        return fn(*args, **kwargs)


def _extract_via_pinterest_dl_library(parsed_target, resolved_url, cookies_dict, timeout, limit):
    """Primary strategy: uses `sean1832/pinterest-dl` (`PinterestDL.with_api()`)."""
    if not PINTEREST_DL_LIB_AVAILABLE or PinterestDL is None:
        return [], "", "", "", "pinterest-dl library not installed"

    try:
        scraper = _call_with_supported_kwargs(
            PinterestDL.with_api,
            timeout=timeout,
            verbose=False,
            ensure_alt=False,
        )

        if cookies_dict and hasattr(scraper, "with_cookies"):
            try:
                scraper = scraper.with_cookies(_cookies_dict_to_list(cookies_dict))
            except Exception:
                try:
                    scraper = scraper.with_cookies(cookies_dict)
                except Exception:
                    pass

        kind = parsed_target.get("kind")
        if kind == "search":
            query = parsed_target.get("query", "")
            raw_media = _call_with_supported_kwargs(
                scraper.search,
                query=query,
                num=limit,
                min_resolution=(0, 0),
                include_videos=True,
                scrape_videos=True,
                video=True,
            )
            page_title = f"Pinterest Search: {query}"
        else:
            # For a single pin, default num=1 so we return the requested pin (not 30 related pins)
            scrape_num = 1 if kind == "pin" and limit <= 1 else limit
            raw_media = _call_with_supported_kwargs(
                scraper.scrape,
                url=resolved_url,
                num=scrape_num,
                min_resolution=(0, 0),
                include_videos=True,
                scrape_videos=True,
                video=True,
            )
            page_title = ""

        if not raw_media:
            return [], "", "", "", "pinterest-dl returned no media"

        items = []
        first_alt = ""
        for idx, m_obj in enumerate(raw_media):
            converted = _media_obj_to_items(m_obj, idx, resolved_url)
            if converted:
                if not first_alt:
                    alt_val = m_obj.get("alt") if isinstance(m_obj, dict) else getattr(m_obj, "alt", "")
                    if alt_val:
                        first_alt = str(alt_val).strip()
                items.extend(converted)

        items = _dedupe_items(items)
        if not items:
            return [], "", "", "", "pinterest-dl produced no usable media URLs"

        if not page_title:
            if kind == "pin":
                page_title = first_alt[:80] if first_alt else f"Pinterest Pin ({parsed_target.get('pin_id', '')})"
            elif kind == "board":
                page_title = f"{parsed_target.get('username', '')}/{parsed_target.get('board_slug', '')} on Pinterest"
            elif kind == "section":
                page_title = f"{parsed_target.get('board_slug', '')} / {parsed_target.get('section_slug', '')} on Pinterest"
            else:
                page_title = first_alt[:80] if first_alt else "Pinterest"

        preview = items[0].get("thumbnailUrl") or ("" if items[0].get("isVideo") else items[0].get("url", ""))
        return items, page_title, first_alt, preview, None
    except Exception as e:
        return [], "", "", "", f"pinterest-dl error: {e.__class__.__name__}: {e}"


# ---------------------------------------------------------------------------
# Tier 2: Direct Pinterest Resource API Fallback (modeled on sean1832/pinterest-dl)
# ---------------------------------------------------------------------------

def _build_session(user_agent, cookies_dict):
    sess = requests.Session()
    sess.headers.update({
        "User-Agent": user_agent or DEFAULT_DESKTOP_UA,
        "Accept": "application/json, text/javascript, */*; q=0.01",
        "Accept-Language": "en-US,en;q=0.9",
        "Referer": "https://www.pinterest.com/",
        "Origin": "https://www.pinterest.com",
    })
    if cookies_dict:
        for k, v in cookies_dict.items():
            sess.cookies.set(k, v, domain=".pinterest.com")
    return sess


def _resolve_short_url(url, sess, timeout):
    """Resolves `pin.it` short URLs to their canonical `pinterest.com` destination."""
    try:
        resp = sess.head(url, allow_redirects=True, timeout=min(timeout, 15))
        if resp.url and "pin.it" not in (urllib.parse.urlparse(resp.url).hostname or "").lower():
            return resp.url
    except Exception:
        pass
    try:
        resp = sess.get(url, allow_redirects=True, timeout=min(timeout, 15), stream=True)
        final_url = resp.url
        resp.close()
        if final_url:
            return final_url
    except Exception:
        pass
    return url


def _ensure_csrf_token(sess, timeout):
    token = sess.cookies.get("csrftoken")
    if token:
        return token
    try:
        sess.get(
            "https://www.pinterest.com/",
            headers={"Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"},
            timeout=min(timeout, 12),
        )
        return sess.cookies.get("csrftoken") or ""
    except Exception:
        return ""


def _call_pinterest_resource(sess, resource_name, source_url, options_dict, timeout, csrf_token=""):
    api_url = f"https://www.pinterest.com/resource/{resource_name}/get/"
    params = {
        "source_url": source_url,
        "data": json.dumps({"options": options_dict, "context": {}}, separators=(",", ":")),
    }
    headers = {
        "X-Requested-With": "XMLHttpRequest",
        "X-Pinterest-AppState": "active",
        "X-Pinterest-Source-Url": source_url,
        "X-Pinterest-PWS-Handler": "www/[username]/[slug].js",
        "Referer": f"https://www.pinterest.com{source_url}",
    }
    if csrf_token:
        headers["X-CSRFToken"] = csrf_token
    resp = sess.get(api_url, params=params, headers=headers, timeout=timeout)
    if resp.status_code != 200:
        return None, f"HTTP {resp.status_code}"
    payload = resp.json()
    res_resp = payload.get("resource_response") or {}
    return res_resp, None


def _extract_via_resource_api(parsed_target, resolved_url, sess, timeout, limit):
    """Direct Pinterest internal Resource API fallback matching pinterest-dl's API endpoints."""
    kind = parsed_target.get("kind")
    csrf = _ensure_csrf_token(sess, timeout)
    last_err = None

    try:
        if kind == "pin":
            pin_id = parsed_target["pin_id"]
            source_url = f"/pin/{pin_id}/"
            for fsk in ("detailed", "unauth_react_main_pin"):
                res_resp, err = _call_pinterest_resource(
                    sess,
                    "PinResource",
                    source_url,
                    {"id": pin_id, "field_set_key": fsk},
                    timeout,
                    csrf,
                )
                if res_resp and isinstance(res_resp.get("data"), dict):
                    items, title, desc, preview = parse_pin_data(
                        res_resp["data"], page_url=resolved_url, source="pinterest-dl-api"
                    )
                    if items:
                        return items, title, desc, preview, None
                last_err = err
            return [], "", "", "", last_err or "PinResource returned no data"

        if kind in ("board", "section"):
            username = parsed_target["username"]
            board_slug = parsed_target["board_slug"]
            source_url = f"/{urllib.parse.quote(username)}/{urllib.parse.quote(board_slug)}/"
            board_resp, err = _call_pinterest_resource(
                sess,
                "BoardResource",
                source_url,
                {"username": username, "slug": board_slug, "field_set_key": "detailed"},
                timeout,
                csrf,
            )
            if not board_resp or not isinstance(board_resp.get("data"), dict):
                return [], "", "", "", err or "BoardResource failed"

            board_data = board_resp["data"]
            board_id = str(board_data.get("id") or "")
            board_name = board_data.get("name") or f"{username}/{board_slug}"
            if not board_id:
                return [], "", "", "", "Board ID not found"

            section_id = None
            if kind == "section":
                target_sec = parsed_target["section_slug"].lower()
                sec_resp, _ = _call_pinterest_resource(
                    sess,
                    "BoardSectionsResource",
                    source_url,
                    {"board_id": board_id},
                    timeout,
                    csrf,
                )
                for sec in (sec_resp or {}).get("data") or []:
                    if not isinstance(sec, dict):
                        continue
                    slug = str(sec.get("slug") or "").lower()
                    title_s = str(sec.get("title") or "").lower()
                    if slug == target_sec or title_s == target_sec.replace("-", " "):
                        section_id = str(sec.get("id") or "")
                        board_name = f"{board_name} / {sec.get('title') or parsed_target['section_slug']}"
                        break

            items = []
            bookmarks = []
            while len(items) < limit:
                if section_id:
                    opts = {
                        "section_id": section_id,
                        "page_size": min(25, limit - len(items)),
                        "field_set_key": "react_grid_pin",
                    }
                    if bookmarks:
                        opts["bookmarks"] = bookmarks
                    feed_resp, err = _call_pinterest_resource(
                        sess, "BoardSectionPinsResource", source_url, opts, timeout, csrf
                    )
                else:
                    opts = {
                        "board_id": board_id,
                        "board_url": source_url,
                        "page_size": min(25, limit - len(items)),
                        "field_set_key": "react_grid_pin",
                    }
                    if bookmarks:
                        opts["bookmarks"] = bookmarks
                    feed_resp, err = _call_pinterest_resource(
                        sess, "BoardFeedResource", source_url, opts, timeout, csrf
                    )

                pins = (feed_resp or {}).get("data") or []
                if not isinstance(pins, list) or not pins:
                    break
                for p in pins:
                    if len(items) >= limit:
                        break
                    p_items, _, _, _ = parse_pin_data(
                        p, page_url=resolved_url, source="pinterest-dl-api", start_index=len(items)
                    )
                    items.extend(p_items)
                next_bm = (feed_resp or {}).get("bookmark")
                if not next_bm or next_bm == "-end-":
                    break
                bookmarks = [next_bm]

            items = _dedupe_items(items)[:limit]
            if items:
                preview = items[0].get("thumbnailUrl") or items[0].get("url", "")
                return items, f"{board_name} on Pinterest", board_data.get("description") or "", preview, None
            return [], "", "", "", last_err or "Board feed returned no pins"

        if kind == "search":
            query = parsed_target["query"]
            source_url = f"/search/pins/?q={urllib.parse.quote(query)}"
            items = []
            bookmarks = []
            while len(items) < limit:
                opts = {"query": query, "scope": "pins", "page_size": min(25, limit - len(items))}
                if bookmarks:
                    opts["bookmarks"] = bookmarks
                search_resp, err = _call_pinterest_resource(
                    sess, "BaseSearchResource", source_url, opts, timeout, csrf
                )
                results = ((search_resp or {}).get("data") or {}).get("results") or []
                if not isinstance(results, list) or not results:
                    break
                for p in results:
                    if len(items) >= limit:
                        break
                    p_items, _, _, _ = parse_pin_data(
                        p, page_url=resolved_url, source="pinterest-dl-api", start_index=len(items)
                    )
                    items.extend(p_items)
                next_bm = (search_resp or {}).get("bookmark")
                if not next_bm or next_bm == "-end-":
                    break
                bookmarks = [next_bm]

            items = _dedupe_items(items)[:limit]
            if items:
                preview = items[0].get("thumbnailUrl") or items[0].get("url", "")
                return items, f"Pinterest Search: {query}", "", preview, None
            return [], "", "", "", err or "BaseSearchResource returned no pins"

        if kind == "profile":
            username = parsed_target["username"]
            source_url = f"/{urllib.parse.quote(username)}/"
            feed_resp, err = _call_pinterest_resource(
                sess,
                "UserActivityPinsResource",
                source_url,
                {"username": username, "page_size": min(25, limit)},
                timeout,
                csrf,
            )
            pins = (feed_resp or {}).get("data") or []
            items = []
            for p in pins:
                if len(items) >= limit:
                    break
                p_items, _, _, _ = parse_pin_data(
                    p, page_url=resolved_url, source="pinterest-dl-api", start_index=len(items)
                )
                items.extend(p_items)
            items = _dedupe_items(items)[:limit]
            if items:
                preview = items[0].get("thumbnailUrl") or items[0].get("url", "")
                return items, f"@{username} on Pinterest", "", preview, None
            return [], "", "", "", err or "UserActivityPinsResource returned no pins"

    except Exception as e:
        return [], "", "", "", f"Pinterest API error: {e.__class__.__name__}: {e}"

    return [], "", "", "", "Unsupported Pinterest target for Resource API"


# ---------------------------------------------------------------------------
# Tier 3: Logged-Out HTML (__PWS_DATA__ / Relay JSON / JSON-LD / OG) + RSS + oEmbed
# ---------------------------------------------------------------------------

def _walk_for_pin_dicts(obj, target_pin_id=None, found=None, depth=0):
    """Recursively locates Pinterest pin dicts inside __PWS_DATA__ or Relay JSON blobs."""
    if found is None:
        found = []
    if depth > 20 or len(found) >= 40:
        return found
    if isinstance(obj, dict):
        obj_id = str(obj.get("id") or "")
        has_media = isinstance(obj.get("images"), dict) or isinstance(obj.get("videos"), dict) or isinstance(obj.get("story_pin_data"), dict)
        if has_media:
            if target_pin_id:
                if obj_id == str(target_pin_id):
                    found.insert(0, obj)
                    return found
            else:
                found.append(obj)
        for v in obj.values():
            if isinstance(v, (dict, list)):
                _walk_for_pin_dicts(v, target_pin_id=target_pin_id, found=found, depth=depth + 1)
    elif isinstance(obj, list):
        for elem in obj:
            if isinstance(elem, (dict, list)):
                _walk_for_pin_dicts(elem, target_pin_id=target_pin_id, found=found, depth=depth + 1)
    return found


def parse_pinterest_html(html_text, pin_id="", page_url="", source="pinterest-dl-html"):
    """
    Parses a Pinterest HTML page for embedded `__PWS_DATA__`, Relay JSON, JSON-LD,
    and OpenGraph media tags. Works even when internal `/resource/` endpoints return 403.
    """
    if not html_text:
        return [], "", "", ""

    # 1. __PWS_DATA__ and Relay JSON script blocks
    script_patterns = [
        r'<script[^>]+id=["\']__PWS_DATA__["\'][^>]*>(.*?)</script>',
        r'<script[^>]+data-relay-response=["\']true["\'][^>]*>(.*?)</script>',
        r'<script[^>]+type=["\']application/json["\'][^>]*>(.*?)</script>',
    ]
    for pat in script_patterns:
        for m in re.finditer(pat, html_text, re.DOTALL | re.IGNORECASE):
            raw = m.group(1).strip()
            if not raw or not (raw.startswith("{") or raw.startswith("[")):
                continue
            if "pinimg.com" not in raw and "video_list" not in raw:
                continue
            try:
                blob = json.loads(raw)
            except Exception:
                continue
            pins = _walk_for_pin_dicts(blob, target_pin_id=pin_id or None)
            if not pins and pin_id:
                pins = _walk_for_pin_dicts(blob, target_pin_id=None)
            if pins:
                # For a specific pin page, take the matching pin first
                target_pins = [pins[0]] if pin_id else pins[:25]
                all_items = []
                page_title = ""
                page_desc = ""
                preview = ""
                for p in target_pins:
                    p_items, t, d, prev = parse_pin_data(
                        p, page_url=page_url, source=source, start_index=len(all_items)
                    )
                    if p_items:
                        all_items.extend(p_items)
                        if not page_title and t:
                            page_title = t
                        if not page_desc and d:
                            page_desc = d
                        if not preview and prev:
                            preview = prev
                all_items = _dedupe_items(all_items)
                if all_items:
                    return all_items, page_title, page_desc, preview

    # 2. JSON-LD (<script type="application/ld+json">)
    ld_items = []
    ld_title = ""
    ld_desc = ""
    for m in re.finditer(
        r'<script[^>]+type=["\']application/ld\+json["\'][^>]*>(.*?)</script>',
        html_text,
        re.DOTALL | re.IGNORECASE,
    ):
        try:
            ld = json.loads(m.group(1).strip())
        except Exception:
            continue
        nodes = ld if isinstance(ld, list) else [ld]
        for node in nodes:
            if not isinstance(node, dict):
                continue
            ld_title = ld_title or str(node.get("name") or node.get("headline") or "").strip()
            ld_desc = ld_desc or str(node.get("description") or "").strip()
            content_url = node.get("contentUrl") or node.get("embedUrl")
            img_val = node.get("image")
            if isinstance(img_val, dict):
                img_val = img_val.get("url")
            elif isinstance(img_val, list) and img_val:
                img_val = img_val[0].get("url") if isinstance(img_val[0], dict) else img_val[0]
            if isinstance(content_url, str) and content_url.startswith("http"):
                is_vid = _is_pinimg_video_url(content_url) or node.get("@type") == "VideoObject"
                final_u = content_url if is_vid else upgrade_pinimg_image_url(content_url)
                ld_items.append(
                    _make_item(
                        url=final_u,
                        is_video=is_vid,
                        is_stream=(".m3u8" in final_u.lower()),
                        pin_id=pin_id,
                        index=len(ld_items),
                        caption=ld_title or ld_desc,
                        thumbnail_url=upgrade_pinimg_image_url(img_val) if isinstance(img_val, str) else None,
                        page_url=page_url,
                        source=source,
                    )
                )
            elif isinstance(img_val, str) and img_val.startswith("http"):
                final_img = upgrade_pinimg_image_url(img_val)
                ld_items.append(
                    _make_item(
                        url=final_img,
                        is_video=False,
                        pin_id=pin_id,
                        index=len(ld_items),
                        caption=ld_title or ld_desc,
                        thumbnail_url=final_img,
                        page_url=page_url,
                        source=source,
                    )
                )
    if ld_items:
        ld_items = _dedupe_items(ld_items)
        return ld_items, ld_title or f"Pinterest Pin ({pin_id})", ld_desc, ld_items[0].get("thumbnailUrl") or ld_items[0]["url"]

    # 3. Direct v1.pinimg.com/videos/...mp4 or .m3u8 regex + OpenGraph meta tags
    og_title = ""
    og_desc = ""
    og_image = ""
    og_video = ""
    for prop, var in (("og:title", "title"), ("og:description", "desc"), ("og:image", "image"), ("og:video", "video")):
        m = re.search(
            rf'<meta[^>]+(?:property|name)=["\']{re.escape(prop)}["\'][^>]+content=["\']([^"\']+)["\']',
            html_text,
            re.IGNORECASE,
        )
        if not m:
            m = re.search(
                rf'<meta[^>]+content=["\']([^"\']+)["\'][^>]+(?:property|name)=["\']{re.escape(prop)}["\']',
                html_text,
                re.IGNORECASE,
            )
        if m:
            val = html_lib.unescape(m.group(1).strip())
            if var == "title":
                og_title = val
            elif var == "desc":
                og_desc = val
            elif var == "image":
                og_image = val
            elif var == "video":
                og_video = val

    items = []
    # Check raw embedded video URLs in HTML scripts
    for vm in re.finditer(
        r'https:(?:\\/|/){2}v\d*\.pinimg\.com/videos/[^"\'\s\\<>]+?\.(?:mp4|m3u8)(?:\?[^"\'\s\\<>]*)?',
        html_text,
    ):
        v_url = vm.group(0).replace("\\/", "/").replace("\\u0026", "&")
        items.append(
            _make_item(
                url=v_url,
                is_video=True,
                is_stream=(".m3u8" in v_url.lower()),
                pin_id=pin_id,
                index=len(items),
                caption=og_title or og_desc,
                thumbnail_url=upgrade_pinimg_image_url(og_image) if og_image else None,
                page_url=page_url,
                source=source,
            )
        )
        break  # Prefer first/primary video stream

    if not items and og_video and og_video.startswith("http"):
        items.append(
            _make_item(
                url=og_video,
                is_video=True,
                is_stream=(".m3u8" in og_video.lower()),
                pin_id=pin_id,
                index=0,
                caption=og_title or og_desc,
                thumbnail_url=upgrade_pinimg_image_url(og_image) if og_image else None,
                page_url=page_url,
                source=source,
            )
        )

    if not items and og_image and "pinimg.com" in og_image:
        upgraded = upgrade_pinimg_image_url(og_image)
        items.append(
            _make_item(
                url=upgraded,
                is_video=False,
                pin_id=pin_id,
                index=0,
                caption=og_title or og_desc,
                thumbnail_url=upgraded,
                page_url=page_url,
                source=source,
            )
        )

    items = _dedupe_items(items)
    if items:
        return items, og_title or "Pinterest", og_desc, items[0].get("thumbnailUrl") or items[0]["url"]
    return [], "", "", ""


def parse_pinterest_rss(xml_text, page_url="", limit=30, source="pinterest-dl-rss"):
    """Parses a public Pinterest board or profile RSS feed into normalized image items."""
    if not xml_text:
        return [], "", "", ""
    try:
        root = ET.fromstring(xml_text)
    except Exception:
        return [], "", "", ""

    channel = root.find("channel")
    if channel is None:
        return [], "", "", ""

    chan_title = (channel.findtext("title") or "Pinterest").strip()
    chan_desc = (channel.findtext("description") or "").strip()
    items = []

    for entry in channel.findall("item"):
        if len(items) >= limit:
            break
        title = (entry.findtext("title") or "").strip()
        link = (entry.findtext("link") or page_url).strip()
        desc = entry.findtext("description") or ""
        m = re.search(r'https?://i\.pinimg\.com/[^"\'\s<>]+', desc)
        if not m:
            continue
        img_url = upgrade_pinimg_image_url(html_lib.unescape(m.group(0)))
        pin_m = re.search(r"/pin/(\d+)", link)
        pin_id = pin_m.group(1) if pin_m else ""
        items.append(
            _make_item(
                url=img_url,
                is_video=False,
                pin_id=pin_id,
                index=len(items),
                caption=title,
                thumbnail_url=img_url,
                page_url=link,
                source=source,
            )
        )

    items = _dedupe_items(items)
    preview = items[0]["url"] if items else ""
    return items, chan_title, chan_desc, preview


def _extract_via_html_and_rss(parsed_target, resolved_url, sess, timeout, limit):
    """Tier 3 fallback: public HTML (__PWS_DATA__ / JSON-LD / OG), Board/Profile RSS, and oEmbed."""
    kind = parsed_target.get("kind")

    # 3a. Public Board or Profile RSS feed
    if kind in ("board", "profile"):
        username = urllib.parse.quote(parsed_target["username"])
        if kind == "board":
            board_slug = urllib.parse.quote(parsed_target["board_slug"])
            rss_url = f"https://www.pinterest.com/{username}/{board_slug}.rss"
        else:
            rss_url = f"https://www.pinterest.com/{username}/feed.rss"
        try:
            resp = sess.get(rss_url, timeout=timeout)
            if resp.status_code == 200 and "<rss" in resp.text[:500].lower():
                items, title, desc, preview = parse_pinterest_rss(
                    resp.text, page_url=resolved_url, limit=limit
                )
                if items:
                    return items, title, desc, preview
        except Exception:
            pass

    # 3b. Direct HTML page fetch (__PWS_DATA__, Relay JSON, JSON-LD, OG)
    try:
        resp = sess.get(
            resolved_url,
            headers={"Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"},
            timeout=timeout,
        )
        if resp.status_code == 200 and resp.text:
            items, title, desc, preview = parse_pinterest_html(
                resp.text,
                pin_id=parsed_target.get("pin_id", ""),
                page_url=resolved_url,
            )
            if items:
                return items[:limit], title, desc, preview
    except Exception:
        pass

    # 3c. Official Pinterest oEmbed fallback (lightweight, rarely blocked)
    try:
        oembed_url = f"https://www.pinterest.com/oembed.json?url={urllib.parse.quote(resolved_url, safe='')}"
        resp = sess.get(oembed_url, timeout=min(timeout, 12))
        if resp.status_code == 200:
            data = resp.json()
            thumb = data.get("thumbnail_url")
            if isinstance(thumb, str) and thumb.startswith("http"):
                upgraded = upgrade_pinimg_image_url(thumb)
                title = str(data.get("title") or data.get("author_name") or "Pinterest").strip()
                item = _make_item(
                    url=upgraded,
                    is_video=False,
                    pin_id=parsed_target.get("pin_id", ""),
                    index=0,
                    caption=title,
                    thumbnail_url=thumb,
                    width=data.get("thumbnail_width"),
                    height=data.get("thumbnail_height"),
                    page_url=resolved_url,
                    source="pinterest-dl-oembed",
                )
                return [item], title, "", upgraded
    except Exception:
        pass

    return [], "", "", ""


# ---------------------------------------------------------------------------
# Public Entry Point (called by gallery_dl_runner.py / GalleryDlEngine)
# ---------------------------------------------------------------------------

def extract_pinterest(url, options_json="{}"):
    """
    Main entry point called by gallery_dl_runner.py / GalleryDlEngine.
    Returns a JSON string compatible with GalleryDlEngine.parseExtractedItems().
    """
    clean_url = (url or "").strip()
    if not clean_url or not is_pinterest_url(clean_url):
        return json.dumps({
            "success": False,
            "engine": "pinterest-dl",
            "error": "Not a valid Pinterest URL",
            "items": [],
        })

    try:
        opts = json.loads(options_json) if options_json else {}
    except Exception:
        opts = {}

    user_agent = opts.get("user_agent") or DEFAULT_DESKTOP_UA
    cookies_dict = _parse_cookie_string(opts.get("cookies") or "")
    timeout = int(opts.get("timeout") or 25)
    parsed = parse_pinterest_url(clean_url)
    sess = _build_session(user_agent, cookies_dict)

    # Direct CDN URL fast path
    if parsed.get("kind") == "cdn":
        is_vid = bool(parsed.get("is_video"))
        final_u = clean_url if is_vid else upgrade_pinimg_image_url(clean_url)
        item = _make_item(
            url=final_u,
            is_video=is_vid,
            is_stream=(".m3u8" in final_u.lower()),
            index=0,
            thumbnail_url=None if is_vid else final_u,
            page_url=clean_url,
            source="pinterest-dl",
        )
        return json.dumps({
            "success": True,
            "engine": "pinterest-dl",
            "extractor": "PinterestDL",
            "pageTitle": item["title"],
            "pageDescription": "",
            "previewImageUrl": "" if is_vid else final_u,
            "itemsCount": 1,
            "items": [item],
        })

    # Resolve pin.it short URLs first
    resolved_url = clean_url
    if parsed.get("kind") == "short":
        resolved_url = _resolve_short_url(clean_url, sess, timeout)
        parsed = parse_pinterest_url(resolved_url)

    default_limit = 1 if parsed.get("kind") == "pin" else 30
    limit = int(opts.get("limit") or default_limit)
    limit = max(1, min(limit, 100))
    last_error = None

    # Tier 1: Official sean1832/pinterest-dl library
    if PINTEREST_DL_LIB_AVAILABLE and parsed.get("kind") in ("pin", "board", "section", "search", "profile"):
        items, title, desc, preview, err = _extract_via_pinterest_dl_library(
            parsed, resolved_url, cookies_dict, timeout, limit
        )
        if items:
            return json.dumps({
                "success": True,
                "engine": "pinterest-dl",
                "extractor": "PinterestDL",
                "pageTitle": title,
                "pageDescription": (desc or "")[:500],
                "previewImageUrl": preview or (items[0]["url"] if not items[0]["isVideo"] else (items[0].get("thumbnailUrl") or "")),
                "itemsCount": len(items),
                "items": items,
            })
        last_error = err

    # Tier 2: Direct Pinterest Resource API (PinResource / BoardFeedResource / etc.)
    if parsed.get("kind") in ("pin", "board", "section", "search", "profile"):
        items, title, desc, preview, err = _extract_via_resource_api(
            parsed, resolved_url, sess, timeout, limit
        )
        if items:
            return json.dumps({
                "success": True,
                "engine": "pinterest-dl",
                "extractor": "PinterestDL",
                "pageTitle": title,
                "pageDescription": (desc or "")[:500],
                "previewImageUrl": preview or (items[0]["url"] if not items[0]["isVideo"] else (items[0].get("thumbnailUrl") or "")),
                "itemsCount": len(items),
                "items": items,
            })
        if err and not last_error:
            last_error = err

    # Tier 3: Logged-out HTML (__PWS_DATA__ / Relay JSON / JSON-LD / OG), RSS, & oEmbed
    items, title, desc, preview = _extract_via_html_and_rss(
        parsed, resolved_url, sess, timeout, limit
    )
    if items:
        return json.dumps({
            "success": True,
            "engine": "pinterest-dl",
            "extractor": "PinterestDL",
            "pageTitle": title,
            "pageDescription": (desc or "")[:500],
            "previewImageUrl": preview or (items[0]["url"] if not items[0]["isVideo"] else (items[0].get("thumbnailUrl") or "")),
            "itemsCount": len(items),
            "items": items,
        })

    return json.dumps({
        "success": False,
        "engine": "pinterest-dl",
        "extractor": "PinterestDL",
        "error": last_error or "pinterest-dl could not extract media from this Pinterest URL",
        "items": [],
    })
