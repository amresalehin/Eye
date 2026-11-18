"""
Instaloader-based Instagram media extractor for Emre Gallery (Chaquopy Python).

Uses Instaloader (Post.from_shortcode / Profile.from_username) as the primary engine
for Instagram posts, reels, carousels (GraphSidecar), TV posts, and public profiles,
with resilient Instaloader-derived fallbacks:
  1. Instaloader Python API (if installed) with optional session cookies.
  2. Instagram GraphQL persisted queries (xdt_api__v1__media__shortcode__web_info &
     xdt_shortcode_media) using Instaloader's doc_ids and x-ig-app-id header.
  3. Instagram mobile/iPhone API (/api/v1/media/{media_id}/info/) using Instaloader's
     exact shortcode_to_mediaid Base64 conversion when session cookies are available.
  4. Anonymous /p/{shortcode}/embed/captioned/ & page HTML data-sjs JSON extraction.
"""

import hashlib
import html as html_lib
import json
import re
from urllib.parse import urlparse, urlencode, unquote

try:
    import requests
    REQUESTS_AVAILABLE = True
except Exception:
    REQUESTS_AVAILABLE = False

try:
    import instaloader
    INSTALOADER_AVAILABLE = True
except Exception:
    instaloader = None
    INSTALOADER_AVAILABLE = False


IG_APP_ID = "936619743392459"
IG_ASBD_ID = "129477"
DEFAULT_MOBILE_UA = (
    "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36"
)
DEFAULT_DESKTOP_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)

# Current and fallback Instaloader GraphQL persisted query doc_ids for post metadata.
# 27128499623469141 -> xdt_api__v1__media__shortcode__web_info (Instaloader PR #2706 / v4.15.3+)
# 10015901848480474 / 8845758582119845 -> legacy xdt_shortcode_media fallback
POST_GRAPHQL_DOC_IDS = (
    "27128499623469141",
    "10015901848480474",
    "8845758582119845",
)

# Instaloader's exact Base64 alphabet for shortcode <-> media_id conversion
_SHORTCODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

_IG_HOSTS = {
    "instagram.com",
    "www.instagram.com",
    "m.instagram.com",
    "instagr.am",
    "www.instagr.am",
    "ddinstagram.com",
    "www.ddinstagram.com",
    "kkinstagram.com",
    "www.kkinstagram.com",
}

_RESERVED_PROFILE_SEGMENTS = {
    "p", "reel", "reels", "tv", "stories", "explore", "accounts",
    "direct", "about", "legal", "developer", "graphql", "api",
    "challenge", "oauth", "web", "static", "reel_audio", "audio",
}

_SHORTCODE_RE = re.compile(
    r"^/(?:[^/]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]{5,32})(?:/|$)"
)
_STORY_RE = re.compile(r"^/stories/([A-Za-z0-9._]+)/([0-9]+)(?:/|$)")
_PROFILE_RE = re.compile(r"^/([A-Za-z0-9._]{1,64})(?:/(?:reels|tagged|saved)?)?/?$")


def is_instaloader_available():
    """Returns True if Instaloader or its fallback extractor is available."""
    return True


def is_instaloader_library_installed():
    """Returns True only if the instaloader PyPI package is importable."""
    return bool(INSTALOADER_AVAILABLE)


def get_instaloader_version():
    """Returns the installed instaloader version or 'fallback'."""
    if INSTALOADER_AVAILABLE:
        return getattr(instaloader, "__version__", "4.16")
    return "fallback"


def is_instagram_url(url):
    """Returns True if the URL points to an Instagram host."""
    if not url or not isinstance(url, str):
        return False
    raw = url.strip()
    if not raw:
        return False
    if "://" not in raw:
        raw = "https://" + raw
    try:
        host = (urlparse(raw).hostname or "").lower()
    except Exception:
        return False
    return host in _IG_HOSTS or host.endswith(".instagram.com")


def parse_instagram_url(url):
    """
    Parses an Instagram URL into a structured dict:
      {"kind": "post"|"story"|"profile"|"unknown", "shortcode": ..., "username": ..., "story_id": ...}
    """
    if not is_instagram_url(url):
        return {"kind": "unknown"}
    raw = url.strip()
    if "://" not in raw:
        raw = "https://" + raw
    path = urlparse(raw).path or "/"

    m = _SHORTCODE_RE.match(path)
    if m:
        return {"kind": "post", "shortcode": m.group(1)}

    m = _STORY_RE.match(path)
    if m:
        return {"kind": "story", "username": m.group(1), "story_id": m.group(2)}

    m = _PROFILE_RE.match(path)
    if m:
        username = m.group(1)
        if username.lower() not in _RESERVED_PROFILE_SEGMENTS:
            return {"kind": "profile", "username": username}

    return {"kind": "unknown"}


def shortcode_to_mediaid(shortcode):
    """
    Converts an Instagram shortcode to its numeric media ID using Instaloader's
    exact Base64-alphabet algorithm (instaloader.Post.shortcode_to_mediaid).
    """
    if not shortcode:
        return None
    # Strip private-post suffix if present (shortcodes > 11 chars can include trailing hash)
    code = shortcode[:11] if len(shortcode) > 11 else shortcode
    media_id = 0
    for ch in code:
        idx = _SHORTCODE_ALPHABET.find(ch)
        if idx < 0:
            return None
        media_id = (media_id * 64) + idx
    return media_id


def _parse_cookie_string(cookies_str):
    """Parses a 'k=v; k2=v2' header string into a dict."""
    result = {}
    if not cookies_str or not isinstance(cookies_str, str):
        return result
    for part in cookies_str.split(";"):
        part = part.strip()
        if not part or "=" not in part:
            continue
        k, v = part.split("=", 1)
        k = k.strip()
        v = v.strip()
        if k:
            result[k] = v
    return result


def _guess_extension(url, is_video):
    clean = (url or "").split("?")[0].split("#")[0].lower()
    for ext in ("mp4", "webm", "mov", "jpg", "jpeg", "png", "webp", "heic", "avif"):
        if clean.endswith("." + ext):
            return "jpg" if ext == "jpeg" else ext
    return "mp4" if is_video else "jpg"


def _make_item(
    url,
    index,
    is_video,
    shortcode="",
    owner="",
    thumbnail_url=None,
    width=None,
    height=None,
    page_url="",
    source="instaloader",
):
    ext = _guess_extension(url, is_video)
    prefix = f"@{owner}_" if owner else ""
    base = f"{prefix}{shortcode}_{index + 1}" if shortcode else f"instagram_{index + 1}"
    title = f"{base}.{ext}"
    url_hash = hashlib.sha256(url.encode("utf-8")).hexdigest()[:12]
    w = int(width) if isinstance(width, (int, float)) and width > 0 else None
    h = int(height) if isinstance(height, (int, float)) and height > 0 else None
    res_hint = f"{w}x{h}" if w and h else None

    return {
        "id": f"ig_{index}_{url_hash}",
        "url": url,
        "title": title,
        "category": "VIDEOS" if is_video else "IMAGES",
        "format": ext.upper(),
        "mimeType": f"video/{ext}" if is_video else f"image/{'jpeg' if ext == 'jpg' else ext}",
        "isVideo": bool(is_video),
        "isAudio": False,
        "isStream": False,
        "thumbnailUrl": thumbnail_url or (None if is_video else url),
        "width": w,
        "height": h,
        "resolutionHint": res_hint,
        "sizeBytes": None,
        "source": source,
        "pageUrl": page_url,
    }


def _pick_best_candidate(candidates):
    """Selects the highest-resolution candidate dict (url, width, height) from an Instagram list."""
    if not isinstance(candidates, list) or not candidates:
        return None
    best = None
    best_score = -1
    for cand in candidates:
        if not isinstance(cand, dict):
            continue
        c_url = cand.get("url") or cand.get("src")
        if not c_url:
            continue
        w = cand.get("width") or cand.get("config_width") or 0
        h = cand.get("height") or cand.get("config_height") or 0
        score = int(w) * int(h)
        if score >= best_score:
            best_score = score
            best = {
                "url": c_url,
                "width": int(w) if w else None,
                "height": int(h) if h else None,
            }
    return best


def _extract_caption_text(node):
    if not isinstance(node, dict):
        return ""
    cap = node.get("caption")
    if isinstance(cap, dict):
        text = cap.get("text")
        if isinstance(text, str):
            return text.strip()
    elif isinstance(cap, str):
        return cap.strip()
    edges = (node.get("edge_media_to_caption") or {}).get("edges") or []
    if edges and isinstance(edges[0], dict):
        text = (edges[0].get("node") or {}).get("text")
        if isinstance(text, str):
            return text.strip()
    return ""


def _build_page_title(owner, caption, shortcode=""):
    first_line = ""
    if caption:
        first_line = caption.strip().splitlines()[0].strip()
        if len(first_line) > 80:
            first_line = first_line[:77].rstrip() + "..."
    if owner and first_line:
        return f"@{owner}: {first_line}"
    if owner:
        return f"@{owner} on Instagram"
    if first_line:
        return first_line
    return f"Instagram Post ({shortcode})" if shortcode else "Instagram"


def parse_v1_media_node(node, shortcode="", page_url="", source="instaloader"):
    """
    Parses an Instagram v1/XDT media dict (returned by Instaloader's
    xdt_api__v1__media__shortcode__web_info or /api/v1/media/{id}/info/).
    Supports single photos, single videos/reels, and mixed photo+video carousels.
    """
    if not isinstance(node, dict):
        return [], "", "", ""

    owner = ""
    user_obj = node.get("user") or node.get("owner")
    if isinstance(user_obj, dict):
        owner = user_obj.get("username") or ""

    code = node.get("code") or node.get("shortcode") or shortcode
    caption = _extract_caption_text(node)
    items = []
    seen = set()
    preview_url = ""

    def _consume_single(media_obj):
        nonlocal preview_url
        if not isinstance(media_obj, dict):
            return
        img_best = _pick_best_candidate(
            (media_obj.get("image_versions2") or {}).get("candidates") or []
        )
        vid_best = _pick_best_candidate(media_obj.get("video_versions") or [])
        thumb = img_best["url"] if img_best else None
        if thumb and not preview_url:
            preview_url = thumb

        is_vid = bool(vid_best) or media_obj.get("media_type") == 2
        if is_vid and vid_best and vid_best["url"] not in seen:
            seen.add(vid_best["url"])
            items.append(
                _make_item(
                    url=vid_best["url"],
                    index=len(items),
                    is_video=True,
                    shortcode=code,
                    owner=owner,
                    thumbnail_url=thumb,
                    width=vid_best.get("width") or media_obj.get("original_width"),
                    height=vid_best.get("height") or media_obj.get("original_height"),
                    page_url=page_url,
                    source=source,
                )
            )
        elif img_best and img_best["url"] not in seen:
            seen.add(img_best["url"])
            items.append(
                _make_item(
                    url=img_best["url"],
                    index=len(items),
                    is_video=False,
                    shortcode=code,
                    owner=owner,
                    thumbnail_url=img_best["url"],
                    width=img_best.get("width") or media_obj.get("original_width"),
                    height=img_best.get("height") or media_obj.get("original_height"),
                    page_url=page_url,
                    source=source,
                )
            )

    carousel = node.get("carousel_media")
    if isinstance(carousel, list) and carousel:
        for child in carousel:
            _consume_single(child)
    else:
        _consume_single(node)

    page_title = _build_page_title(owner, caption, code)
    return items, page_title, caption, preview_url


def parse_graphql_shortcode_media(media, shortcode="", page_url="", source="instaloader"):
    """
    Parses an Instagram GraphQL xdt_shortcode_media / shortcode_media dict
    (GraphImage, GraphVideo, GraphSidecar).
    """
    if not isinstance(media, dict):
        return [], "", "", ""

    owner = (media.get("owner") or {}).get("username") or ""
    code = media.get("shortcode") or shortcode
    caption = _extract_caption_text(media)
    items = []
    seen = set()
    preview_url = media.get("display_url") or ""

    def _consume_gql_node(n):
        nonlocal preview_url
        if not isinstance(n, dict):
            return
        dims = n.get("dimensions") or {}
        w = dims.get("width")
        h = dims.get("height")
        best_img = _pick_best_candidate(n.get("display_resources") or [])
        disp_url = (best_img["url"] if best_img else None) or n.get("display_url")
        if disp_url and not preview_url:
            preview_url = disp_url
        if best_img:
            w = best_img.get("width") or w
            h = best_img.get("height") or h

        is_vid = bool(n.get("is_video")) or bool(n.get("video_url"))
        vid_url = n.get("video_url")
        if is_vid and vid_url and vid_url not in seen:
            seen.add(vid_url)
            items.append(
                _make_item(
                    url=vid_url,
                    index=len(items),
                    is_video=True,
                    shortcode=code,
                    owner=owner,
                    thumbnail_url=disp_url,
                    width=w,
                    height=h,
                    page_url=page_url,
                    source=source,
                )
            )
        elif disp_url and disp_url not in seen:
            seen.add(disp_url)
            items.append(
                _make_item(
                    url=disp_url,
                    index=len(items),
                    is_video=False,
                    shortcode=code,
                    owner=owner,
                    thumbnail_url=disp_url,
                    width=w,
                    height=h,
                    page_url=page_url,
                    source=source,
                )
            )

    sidecar_edges = (media.get("edge_sidecar_to_children") or {}).get("edges") or []
    if sidecar_edges:
        for edge in sidecar_edges:
            if isinstance(edge, dict) and isinstance(edge.get("node"), dict):
                _consume_gql_node(edge["node"])
    else:
        _consume_gql_node(media)

    page_title = _build_page_title(owner, caption, code)
    return items, page_title, caption, preview_url


def parse_graphql_payload(payload, shortcode="", page_url="", source="instaloader"):
    """Parses any of Instagram's GraphQL or v1 API JSON payloads into normalized items."""
    if not isinstance(payload, dict):
        return [], "", "", ""

    data = payload.get("data") if isinstance(payload.get("data"), dict) else {}

    # 1. Instaloader v4.15.3+ / PR #2706 schema: data.xdt_api__v1__media__shortcode__web_info.items
    web_info = data.get("xdt_api__v1__media__shortcode__web_info")
    if isinstance(web_info, dict):
        web_items = web_info.get("items")
        if isinstance(web_items, list) and web_items:
            return parse_v1_media_node(web_items[0], shortcode, page_url, source)

    # 2. Relay GraphQL schema: data.xdt_shortcode_media or data.shortcode_media
    gql_media = data.get("xdt_shortcode_media") or data.get("shortcode_media")
    if isinstance(gql_media, dict):
        return parse_graphql_shortcode_media(gql_media, shortcode, page_url, source)

    # 3. Mobile / iPhone API schema: {"items": [...]}
    top_items = payload.get("items")
    if isinstance(top_items, list) and top_items:
        return parse_v1_media_node(top_items[0], shortcode, page_url, source)

    # 4. Legacy web JSON: {"graphql": {"shortcode_media": {...}}}
    legacy = (payload.get("graphql") or {}).get("shortcode_media")
    if isinstance(legacy, dict):
        return parse_graphql_shortcode_media(legacy, shortcode, page_url, source)

    return [], "", "", ""


def _unescape_ig_url(raw_url):
    if not raw_url:
        return ""
    cleaned = raw_url.replace("\\u0026", "&").replace("\\/", "/")
    cleaned = html_lib.unescape(cleaned).strip()
    return cleaned


def parse_embed_html(html_text, shortcode="", page_url="", source="instaloader"):
    """
    Parses Instagram's anonymous /p/{shortcode}/embed/captioned/ HTML or main page
    data-sjs JSON scripts to extract photos, videos, and carousels without login.
    """
    if not html_text or not isinstance(html_text, str):
        return [], "", "", ""

    # 1. Try embedded JSON blobs (gql_data / shortcode_media / xdt_api__v1__media__shortcode__web_info)
    for m in re.finditer(
        r'window\.__additionalDataLoaded\s*\([^,]+,\s*(\{.*?\})\s*\)\s*;',
        html_text,
        re.DOTALL,
    ):
        try:
            obj = json.loads(m.group(1))
            items, title, desc, preview = parse_graphql_payload(obj, shortcode, page_url, source)
            if items:
                return items, title, desc, preview
            if isinstance(obj.get("shortcode_media"), dict):
                items, title, desc, preview = parse_graphql_shortcode_media(
                    obj["shortcode_media"], shortcode, page_url, source
                )
                if items:
                    return items, title, desc, preview
        except Exception:
            pass

    for m in re.finditer(
        r'"gql_data"\s*:\s*(\{"shortcode_media"\s*:.*?\})\s*,\s*"[a-zA-Z0-9_]+"',
        html_text,
        re.DOTALL,
    ):
        try:
            obj = json.loads(m.group(1))
            if isinstance(obj.get("shortcode_media"), dict):
                items, title, desc, preview = parse_graphql_shortcode_media(
                    obj["shortcode_media"], shortcode, page_url, source
                )
                if items:
                    return items, title, desc, preview
        except Exception:
            pass

    # Look inside <script type="application/json" ... data-sjs> tags
    for m in re.finditer(
        r'<script[^>]*type=["\']application/json["\'][^>]*>(.*?)</script>',
        html_text,
        re.DOTALL | re.IGNORECASE,
    ):
        blob = m.group(1)
        if "xdt_api__v1__media__shortcode__web_info" not in blob and "xdt_shortcode_media" not in blob:
            continue
        try:
            parsed = json.loads(blob)
            found = _find_media_dict_recursive(parsed)
            if found is not None:
                kind, node = found
                if kind == "v1":
                    items, title, desc, preview = parse_v1_media_node(node, shortcode, page_url, source)
                else:
                    items, title, desc, preview = parse_graphql_shortcode_media(node, shortcode, page_url, source)
                if items:
                    return items, title, desc, preview
        except Exception:
            pass

    # 2. Direct embed DOM fallback (EmbeddedMediaImage / video_url / display_url)
    items = []
    seen = set()
    owner = ""
    owner_m = re.search(r'class=["\']UsernameText["\'][^>]*>([^<]+)<', html_text)
    if owner_m:
        owner = html_lib.unescape(owner_m.group(1)).strip().lstrip("@")

    caption = ""
    cap_m = re.search(r'<div class=["\']Caption["\'][^>]*>(.*?)</div>', html_text, re.DOTALL)
    if cap_m:
        caption = html_lib.unescape(re.sub(r"<[^>]+>", " ", cap_m.group(1)))
        caption = re.sub(r"\s+", " ", caption).strip()

    # Check for escaped video_url in embed JS payload first
    for vm in re.finditer(r'"video_url"\s*:\s*"(https?:\\?/\\?/[^"]+)"', html_text):
        v_url = _unescape_ig_url(vm.group(1))
        if v_url and v_url not in seen:
            seen.add(v_url)
            items.append(
                _make_item(
                    url=v_url,
                    index=len(items),
                    is_video=True,
                    shortcode=shortcode,
                    owner=owner,
                    page_url=page_url,
                    source=source,
                )
            )

    preview_url = ""
    img_m = re.search(
        r'<img[^>]*class=["\'][^"\']*EmbeddedMediaImage[^"\']*["\'][^>]*src=["\']([^"\']+)["\']',
        html_text,
        re.IGNORECASE,
    )
    if not img_m:
        img_m = re.search(
            r'<img[^>]*src=["\']([^"\']+)["\'][^>]*class=["\'][^"\']*EmbeddedMediaImage[^"\']*["\']',
            html_text,
            re.IGNORECASE,
        )
    if img_m:
        img_url = _unescape_ig_url(img_m.group(1))
        preview_url = img_url
        if not items and img_url and img_url not in seen:
            seen.add(img_url)
            items.append(
                _make_item(
                    url=img_url,
                    index=len(items),
                    is_video=False,
                    shortcode=shortcode,
                    owner=owner,
                    thumbnail_url=img_url,
                    page_url=page_url,
                    source=source,
                )
            )
        elif items and preview_url:
            items[0]["thumbnailUrl"] = preview_url

    page_title = _build_page_title(owner, caption, shortcode)
    return items, page_title, caption, preview_url


def _find_media_dict_recursive(obj, depth=0):
    if depth > 14:
        return None
    if isinstance(obj, dict):
        web_info = obj.get("xdt_api__v1__media__shortcode__web_info")
        if isinstance(web_info, dict) and isinstance(web_info.get("items"), list) and web_info["items"]:
            return ("v1", web_info["items"][0])
        gql = obj.get("xdt_shortcode_media") or obj.get("shortcode_media")
        if isinstance(gql, dict) and (gql.get("display_url") or gql.get("video_url") or gql.get("edge_sidecar_to_children")):
            return ("gql", gql)
        for v in obj.values():
            res = _find_media_dict_recursive(v, depth + 1)
            if res is not None:
                return res
    elif isinstance(obj, list):
        for el in obj:
            res = _find_media_dict_recursive(el, depth + 1)
            if res is not None:
                return res
    return None


def _extract_via_instaloader_library(parsed_target, page_url, user_agent, cookies_dict, timeout, max_profile_posts=12):
    """Primary strategy: uses the official instaloader Python package."""
    if not INSTALOADER_AVAILABLE:
        return [], "", "", "", "instaloader library not installed"

    try:
        loader = instaloader.Instaloader(
            quiet=True,
            download_pictures=False,
            download_videos=False,
            download_video_thumbnails=False,
            download_geotags=False,
            download_comments=False,
            save_metadata=False,
            compress_json=False,
            max_connection_attempts=2,
            request_timeout=float(timeout or 25),
            user_agent=user_agent or DEFAULT_DESKTOP_UA,
        )

        if cookies_dict:
            session = loader.context._session
            for k, v in cookies_dict.items():
                session.cookies.set(k, v, domain=".instagram.com", path="/")
            if "csrftoken" in cookies_dict:
                session.headers["X-CSRFToken"] = cookies_dict["csrftoken"]
            if "sessionid" in cookies_dict and "ds_user_id" in cookies_dict:
                # Mark context as authenticated so Instaloader uses logged-in endpoints
                loader.context.username = cookies_dict.get("ds_user_id")

        kind = parsed_target.get("kind")
        if kind == "post":
            shortcode = parsed_target["shortcode"]
            post = instaloader.Post.from_shortcode(loader.context, shortcode)
            return _items_from_instaloader_post(post, page_url)

        if kind == "profile":
            username = parsed_target["username"]
            profile = instaloader.Profile.from_username(loader.context, username)
            all_items = []
            preview_url = ""
            count = 0
            for post in profile.get_posts():
                post_items, _, _, post_preview = _items_from_instaloader_post(
                    post, f"https://www.instagram.com/p/{post.shortcode}/"
                )
                if not preview_url and post_preview:
                    preview_url = post_preview
                for itm in post_items:
                    itm["id"] = f"ig_{len(all_items)}_{hashlib.sha256(itm['url'].encode('utf-8')).hexdigest()[:12]}"
                    all_items.append(itm)
                count += 1
                if count >= max_profile_posts:
                    break
            title = f"@{profile.username} ({profile.full_name})" if profile.full_name else f"@{profile.username}"
            desc = profile.biography or ""
            if not preview_url:
                preview_url = getattr(profile, "profile_pic_url", "") or ""
            return all_items, title, desc, preview_url, None

        return [], "", "", "", f"Unsupported Instagram URL type: {kind}"
    except Exception as e:
        return [], "", "", "", f"Instaloader error: {e.__class__.__name__}: {e}"


def _items_from_instaloader_post(post, page_url):
    """Converts an instaloader.Post instance (single photo, video, reel, or carousel) into normalized items."""
    shortcode = getattr(post, "shortcode", "") or ""
    owner = getattr(post, "owner_username", "") or ""
    caption = getattr(post, "caption", "") or ""
    post_url = getattr(post, "url", "") or ""
    preview_url = post_url
    raw_node = getattr(post, "_node", None) or {}
    items = []
    seen = set()

    if getattr(post, "typename", "") == "GraphSidecar":
        for node in post.get_sidecar_nodes():
            node_dict = getattr(node, "_node", None) or {}
            dims = node_dict.get("dimensions") or {}
            w = dims.get("width") or node_dict.get("original_width")
            h = dims.get("height") or node_dict.get("original_height")
            disp = getattr(node, "display_url", None) or ""
            if disp and not preview_url:
                preview_url = disp
            if getattr(node, "is_video", False) and getattr(node, "video_url", None):
                v_url = node.video_url
                if v_url not in seen:
                    seen.add(v_url)
                    items.append(
                        _make_item(
                            url=v_url,
                            index=len(items),
                            is_video=True,
                            shortcode=shortcode,
                            owner=owner,
                            thumbnail_url=disp,
                            width=w,
                            height=h,
                            page_url=page_url,
                            source="instaloader",
                        )
                    )
            elif disp and disp not in seen:
                seen.add(disp)
                items.append(
                    _make_item(
                        url=disp,
                        index=len(items),
                        is_video=False,
                        shortcode=shortcode,
                        owner=owner,
                        thumbnail_url=disp,
                        width=w,
                        height=h,
                        page_url=page_url,
                        source="instaloader",
                    )
                )
    else:
        dims = raw_node.get("dimensions") or {}
        w = dims.get("width") or raw_node.get("original_width")
        h = dims.get("height") or raw_node.get("original_height")
        if getattr(post, "is_video", False) and getattr(post, "video_url", None):
            v_url = post.video_url
            if v_url not in seen:
                seen.add(v_url)
                items.append(
                    _make_item(
                        url=v_url,
                        index=len(items),
                        is_video=True,
                        shortcode=shortcode,
                        owner=owner,
                        thumbnail_url=post_url,
                        width=w,
                        height=h,
                        page_url=page_url,
                        source="instaloader",
                    )
                )
        elif post_url and post_url not in seen:
            seen.add(post_url)
            items.append(
                _make_item(
                    url=post_url,
                    index=len(items),
                    is_video=False,
                    shortcode=shortcode,
                    owner=owner,
                    thumbnail_url=post_url,
                    width=w,
                    height=h,
                    page_url=page_url,
                    source="instaloader",
                )
            )

    page_title = _build_page_title(owner, caption, shortcode)
    return items, page_title, caption, preview_url, None


def _build_session(user_agent, cookies_dict):
    if not REQUESTS_AVAILABLE:
        return None
    sess = requests.Session()
    sess.headers.update({
        "User-Agent": user_agent or DEFAULT_DESKTOP_UA,
        "Accept": "*/*",
        "Accept-Language": "en-US,en;q=0.9",
        "X-IG-App-ID": IG_APP_ID,
        "X-ASBD-ID": IG_ASBD_ID,
        "X-Requested-With": "XMLHttpRequest",
        "Origin": "https://www.instagram.com",
        "Referer": "https://www.instagram.com/",
    })
    if cookies_dict:
        for k, v in cookies_dict.items():
            sess.cookies.set(k, v, domain=".instagram.com", path="/")
        if "csrftoken" in cookies_dict:
            sess.headers["X-CSRFToken"] = cookies_dict["csrftoken"]
    return sess


def _extract_via_graphql(shortcode, page_url, sess, timeout):
    """Instaloader-style GraphQL persisted query fallback."""
    if sess is None:
        return [], "", "", ""
    variables_json = json.dumps({"shortcode": shortcode}, separators=(",", ":"))
    for doc_id in POST_GRAPHQL_DOC_IDS:
        try:
            resp = sess.post(
                "https://www.instagram.com/graphql/query",
                data={
                    "variables": variables_json,
                    "doc_id": doc_id,
                    "lsd": "AVqbxe3J_YA",
                },
                headers={
                    "Content-Type": "application/x-www-form-urlencoded",
                    "X-FB-LSD": "AVqbxe3J_YA",
                    "Referer": page_url or f"https://www.instagram.com/p/{shortcode}/",
                },
                timeout=timeout,
            )
            if resp.status_code == 200:
                payload = resp.json()
                items, title, desc, preview = parse_graphql_payload(
                    payload, shortcode, page_url, source="instaloader-graphql"
                )
                if items:
                    return items, title, desc, preview
        except Exception:
            continue
    return [], "", "", ""


def _extract_via_iphone_api(shortcode, page_url, sess, timeout):
    """Instaloader-style mobile/iPhone API fallback (/api/v1/media/{media_id}/info/)."""
    if sess is None:
        return [], "", "", ""
    media_id = shortcode_to_mediaid(shortcode)
    if not media_id:
        return [], "", "", ""
    try:
        resp = sess.get(
            f"https://i.instagram.com/api/v1/media/{media_id}/info/",
            headers={
                "User-Agent": "Instagram 275.0.0.27.98 Android (33/13; 420dpi; 1080x2400; samsung; SM-G991B; o1s; exynos2100; en_US; 458229237)",
                "X-IG-App-ID": IG_APP_ID,
            },
            timeout=timeout,
        )
        if resp.status_code == 200:
            payload = resp.json()
            items, title, desc, preview = parse_graphql_payload(
                payload, shortcode, page_url, source="instaloader-api"
            )
            if items:
                return items, title, desc, preview
    except Exception:
        pass
    return [], "", "", ""


def _extract_via_embed_html(shortcode, page_url, sess, timeout):
    """Anonymous embed & post HTML fallback when GraphQL endpoints reject requests."""
    if sess is None:
        return [], "", "", ""
    urls_to_try = [
        f"https://www.instagram.com/p/{shortcode}/embed/captioned/",
        f"https://www.instagram.com/p/{shortcode}/",
    ]
    for target in urls_to_try:
        try:
            resp = sess.get(
                target,
                headers={
                    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Sec-Fetch-Dest": "document",
                    "Sec-Fetch-Mode": "navigate",
                    "Sec-Fetch-Site": "none",
                },
                timeout=timeout,
            )
            if resp.status_code == 200 and resp.text:
                items, title, desc, preview = parse_embed_html(
                    resp.text, shortcode, page_url, source="instaloader-embed"
                )
                if items:
                    return items, title, desc, preview
        except Exception:
            continue
    return [], "", "", ""


def extract_instagram(url, options_json="{}"):
    """
    Main entry point called by gallery_dl_runner.py / GalleryDlEngine.
    Returns a JSON string compatible with GalleryDlEngine.parseExtractedItems().
    """
    clean_url = (url or "").strip()
    if not clean_url or not is_instagram_url(clean_url):
        return json.dumps({
            "success": False,
            "engine": "instaloader",
            "error": "Not a valid Instagram URL",
            "items": [],
        })

    try:
        opts = json.loads(options_json) if options_json else {}
    except Exception:
        opts = {}

    user_agent = opts.get("user_agent") or DEFAULT_DESKTOP_UA
    cookies_dict = _parse_cookie_string(opts.get("cookies") or "")
    timeout = int(opts.get("timeout") or 25)
    parsed = parse_instagram_url(clean_url)
    last_error = None

    # 1. Primary: Instaloader library
    if INSTALOADER_AVAILABLE and parsed.get("kind") in ("post", "profile"):
        items, title, desc, preview, err = _extract_via_instaloader_library(
            parsed, clean_url, user_agent, cookies_dict, timeout
        )
        if items:
            return json.dumps({
                "success": True,
                "engine": "instaloader",
                "extractor": "Instaloader",
                "pageTitle": title,
                "pageDescription": (desc or "")[:500],
                "previewImageUrl": preview or (items[0]["url"] if not items[0]["isVideo"] else (items[0].get("thumbnailUrl") or "")),
                "itemsCount": len(items),
                "items": items,
            })
        last_error = err

    # 2. Direct Instaloader-derived fallbacks for shortcode posts / reels / TV
    if parsed.get("kind") == "post":
        shortcode = parsed["shortcode"]
        sess = _build_session(user_agent, cookies_dict)

        # 2a. GraphQL persisted queries (v4.15.3+ doc_id & fallbacks)
        items, title, desc, preview = _extract_via_graphql(shortcode, clean_url, sess, timeout)

        # 2b. Mobile/iPhone API using shortcode_to_mediaid (best when cookies exist)
        if not items and cookies_dict:
            items, title, desc, preview = _extract_via_iphone_api(shortcode, clean_url, sess, timeout)

        # 2c. Anonymous /embed/captioned/ and page HTML data-sjs fallback
        if not items:
            items, title, desc, preview = _extract_via_embed_html(shortcode, clean_url, sess, timeout)

        if items:
            return json.dumps({
                "success": True,
                "engine": "instaloader",
                "extractor": "Instaloader",
                "pageTitle": title,
                "pageDescription": (desc or "")[:500],
                "previewImageUrl": preview or (items[0]["url"] if not items[0]["isVideo"] else (items[0].get("thumbnailUrl") or "")),
                "itemsCount": len(items),
                "items": items,
            })

    return json.dumps({
        "success": False,
        "engine": "instaloader",
        "extractor": "Instaloader",
        "error": last_error or "Instaloader could not extract media from this Instagram URL",
        "items": [],
    })
