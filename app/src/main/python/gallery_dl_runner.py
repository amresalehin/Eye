import json
import os
import re
import sys
import time
import traceback
import hashlib
import threading

_gdl_lock = threading.Lock()

try:
    import gallery_dl
    from gallery_dl import extractor as gdl_extractor, job as gdl_job, config as gdl_config, exception as gdl_exception
    GALLERY_DL_AVAILABLE = True
except Exception:
    GALLERY_DL_AVAILABLE = False

try:
    import yt_dlp
    YTDLP_AVAILABLE = True
except Exception:
    YTDLP_AVAILABLE = False

POPULAR_EXTRACTORS = [
    {"name": "Reddit", "domains": ["reddit.com", "redd.it", "i.redd.it", "v.redd.it"], "icon": "reddit"},
    {"name": "Instagram", "domains": ["instagram.com", "instagr.am"], "icon": "camera"},
    {"name": "Imgur", "domains": ["imgur.com", "i.imgur.com"], "icon": "image"},
    {"name": "Twitter / X", "domains": ["twitter.com", "x.com", "pbs.twimg.com"], "icon": "tag"},
    {"name": "Pinterest", "domains": ["pinterest.com", "pin.it", "i.pinimg.com", "v1.pinimg.com"], "icon": "pin"},
    {"name": "ArtStation", "domains": ["artstation.com"], "icon": "brush"},
    {"name": "DeviantArt", "domains": ["deviantart.com"], "icon": "palette"},
    {"name": "Flickr", "domains": ["flickr.com"], "icon": "camera"}
]

SOCIAL_VIDEO_HOSTS = [
    "youtube.com", "youtu.be", "vimeo.com", "tiktok.com", "dailymotion.com",
    "twitch.tv", "soundcloud.com", "bandcamp.com", "facebook.com", "fb.watch",
    "instagram.com", "instagr.am", "reddit.com", "redd.it", "v.redd.it",
    "twitter.com", "x.com", "pinterest.com", "pin.it", "pinimg.com", "v1.pinimg.com",
    "imgur.com", "i.imgur.com"
]

def _parse_cookie_dict(cookies):
    """Converts a raw HTTP Cookie header string ('k1=v1; k2=v2') into a dict for gallery-dl."""
    if not cookies:
        return None
    if isinstance(cookies, dict):
        return cookies
    if isinstance(cookies, str):
        cookie_map = {}
        for part in cookies.split(";"):
            trimmed = part.strip()
            if "=" in trimmed:
                k, v = trimmed.split("=", 1)
                k = k.strip()
                v = v.strip()
                if k:
                    cookie_map[k] = v
        return cookie_map if cookie_map else None
    return None

def _normalize_reddit_probe_url(media_url, referer=None):
    """
    Normalizes raw v.redd.it/ID/DASH_*.mp4 URLs to either the Reddit post referer URL
    or the canonical https://v.redd.it/ID base URL so yt-dlp's RedditVideoIE extracts
    both video and audio DASH/HLS tracks.
    """
    lower_media = (media_url or "").lower()
    lower_ref = (referer or "").lower()
    if "v.redd.it/" in lower_media:
        if referer and ("reddit.com" in lower_ref or "redd.it" in lower_ref) and "dash_" not in lower_ref:
            return referer
        m = re.match(r"^(https?://v\.redd\.it/[^/?#&]+)", media_url.strip(), re.IGNORECASE)
        if m:
            return m.group(1)
    return media_url

def _notify_progress(callback, bytes_read, total_bytes, speed=0):
    if callback is None:
        return
    try:
        b_read = int(bytes_read or 0)
        t_bytes = int(total_bytes if total_bytes is not None else -1)
        spd = int(speed or 0)
        if hasattr(callback, "onProgress"):
            callback.onProgress(b_read, t_bytes, spd)
        elif callable(callback):
            callback(b_read, t_bytes, spd)
    except Exception:
        pass

def _make_ydl_progress_hook(progress_callback, weight_offset=0.0, weight_span=1.0):
    last_emit = [0.0]
    def _hook(d):
        if progress_callback is None:
            return
        status = d.get("status")
        if status == "downloading":
            now = time.time()
            if now - last_emit[0] < 0.25:
                return
            last_emit[0] = now
            downloaded = int(d.get("downloaded_bytes") or 0)
            total = int(d.get("total_bytes") or d.get("total_bytes_estimate") or -1)
            speed = int(d.get("speed") or 0)
            if total > 0 and (weight_offset > 0.0 or weight_span < 1.0):
                virtual_total = int(total / weight_span) if weight_span > 0 else total
                virtual_read = int(weight_offset * virtual_total + downloaded)
                _notify_progress(progress_callback, virtual_read, virtual_total, speed)
            else:
                _notify_progress(progress_callback, downloaded, total, speed)
    return _hook

def _mux_with_android_media_muxer(video_path, audio_path, target_path):
    """Invokes AndroidMediaMuxer via Chaquopy if running on Android."""
    try:
        from java import jclass
        muxer_cls = jclass("com.amresalehin.emregallery.data.AndroidMediaMuxer")
        return bool(muxer_cls.muxVideoAndAudioPaths(video_path, audio_path, target_path))
    except Exception:
        return False

def get_engine_status():
    """Returns runtime status and version information synchronized with GalleryDlEngine."""
    gdl_ver = getattr(gallery_dl, "__version__", "1.32.19") if GALLERY_DL_AVAILABLE else "N/A"
    ytdlp_ver = getattr(yt_dlp, "__version__", "2026.10.21") if YTDLP_AVAILABLE else "N/A"
    available = GALLERY_DL_AVAILABLE or YTDLP_AVAILABLE
    engine_name = f"gallery-dl {gdl_ver} / yt-dlp {ytdlp_ver}" if available else "Python Engine Unavailable"

    return json.dumps({
        "available": available,
        "version": gdl_ver,
        "engine": engine_name,
        "gallery_dl_available": GALLERY_DL_AVAILABLE,
        "gallery_dl_version": gdl_ver,
        "yt_dlp_available": YTDLP_AVAILABLE,
        "yt_dlp_version": ytdlp_ver,
        "python_version": sys.version.split()[0],
        "supported_categories_count": 287 if GALLERY_DL_AVAILABLE else 0,
        "popular_extractors": POPULAR_EXTRACTORS
    })

def check_support(url):
    """
    Checks if gallery-dl or yt-dlp supports the given URL.
    Returns JSON: {"supported": bool, "category": str, "subcategory": str, "description": str}
    """
    try:
        import gallery_dl.extractor as gdl_extractor
        extr = gdl_extractor.find(url)
        if extr is not None and extr.category != "generic":
            return json.dumps({
                "supported": True,
                "category": getattr(extr, "category", "") or "",
                "subcategory": getattr(extr, "subcategory", "") or "",
                "description": getattr(extr, "__doc__", "") or f"{extr.category}:{extr.subcategory}"
            })
    except Exception:
        pass

    lower_url = (url or "").lower()
    for entry in POPULAR_EXTRACTORS:
        for dom in entry["domains"]:
            if dom in lower_url:
                return json.dumps({
                    "supported": True,
                    "category": entry.get("name", "").lower(),
                    "subcategory": "domain_match",
                    "description": entry["name"]
                })

    return json.dumps({
        "supported": False,
        "category": "",
        "subcategory": "",
        "description": ""
    })


def _parse_cookie_dict(cookies):
    """Converts a raw HTTP Cookie header string ('k1=v1; k2=v2') into a dict for gallery-dl."""
    if not cookies:
        return None
    if isinstance(cookies, dict):
        return cookies
    if isinstance(cookies, str):
        cookie_map = {}
        for part in cookies.split(";"):
            trimmed = part.strip()
            if "=" in trimmed:
                k, v = trimmed.split("=", 1)
                k = k.strip()
                v = v.strip()
                if k:
                    cookie_map[k] = v
        return cookie_map if cookie_map else None
    return None

def _normalize_reddit_probe_url(media_url, referer=None):
    """
    Normalizes raw v.redd.it/ID/DASH_*.mp4 URLs to either the Reddit post referer URL
    or the canonical https://v.redd.it/ID base URL so yt-dlp's RedditVideoIE extracts
    both video and audio DASH/HLS tracks.
    """
    lower_media = (media_url or "").lower()
    lower_ref = (referer or "").lower()
    if "v.redd.it/" in lower_media:
        if referer and ("reddit.com" in lower_ref or "redd.it" in lower_ref) and "dash_" not in lower_ref:
            return referer
        m = re.match(r"^(https?://v\.redd\.it/[^/?#&]+)", media_url.strip(), re.IGNORECASE)
        if m:
            return m.group(1)
    return media_url

def _notify_progress(callback, bytes_read, total_bytes, speed=0):
    if callback is None:
        return
    try:
        b_read = int(bytes_read or 0)
        t_bytes = int(total_bytes if total_bytes is not None else -1)
        spd = int(speed or 0)
        if hasattr(callback, "onProgress"):
            callback.onProgress(b_read, t_bytes, spd)
        elif callable(callback):
            callback(b_read, t_bytes, spd)
    except Exception:
        pass

def _make_ydl_progress_hook(progress_callback, weight_offset=0.0, weight_span=1.0):
    last_emit = [0.0]
    def _hook(d):
        if progress_callback is None:
            return
        status = d.get("status")
        if status == "downloading":
            now = time.time()
            if now - last_emit[0] < 0.25:
                return
            last_emit[0] = now
            downloaded = int(d.get("downloaded_bytes") or 0)
            total = int(d.get("total_bytes") or d.get("total_bytes_estimate") or -1)
            speed = int(d.get("speed") or 0)
            if total > 0 and (weight_offset > 0.0 or weight_span < 1.0):
                virtual_total = int(total / weight_span) if weight_span > 0 else total
                virtual_read = int(weight_offset * virtual_total + downloaded)
                _notify_progress(progress_callback, virtual_read, virtual_total, speed)
            else:
                _notify_progress(progress_callback, downloaded, total, speed)
    return _hook

def _mux_with_android_media_muxer(video_path, audio_path, target_path):
    """Invokes AndroidMediaMuxer via Chaquopy if running on Android."""
    try:
        from java import jclass
        muxer_cls = jclass("com.amresalehin.emregallery.data.AndroidMediaMuxer")
        return bool(muxer_cls.muxVideoAndAudioPaths(video_path, audio_path, target_path))
    except Exception:
        return False

def get_engine_status():
    """Returns runtime status and version information synchronized with GalleryDlEngine."""
    gdl_ver = getattr(gallery_dl, "__version__", "1.32.19") if GALLERY_DL_AVAILABLE else "N/A"
    ytdlp_ver = getattr(yt_dlp, "__version__", "2026.10.21") if YTDLP_AVAILABLE else "N/A"
    available = GALLERY_DL_AVAILABLE or YTDLP_AVAILABLE
    engine_name = f"gallery-dl {gdl_ver} / yt-dlp {ytdlp_ver}" if available else "Python Engine Unavailable"

    return json.dumps({
        "available": available,
        "version": gdl_ver,
        "engine": engine_name,
        "gallery_dl_available": GALLERY_DL_AVAILABLE,
        "gallery_dl_version": gdl_ver,
        "yt_dlp_available": YTDLP_AVAILABLE,
        "yt_dlp_version": ytdlp_ver,
        "python_version": sys.version.split()[0],
        "supported_categories_count": 287 if GALLERY_DL_AVAILABLE else 0,
        "popular_extractors": POPULAR_EXTRACTORS
    })

def extract_gallery_dl(url, options_json="{}"):
    """
    Extracts media items using gallery-dl exclusively.
    """
    clean_url = url.strip() if url else ""
    if not clean_url or not GALLERY_DL_AVAILABLE:
        return json.dumps({"success": False, "items": [], "error": "gallery-dl not available or empty URL"})

    try:
        opts = json.loads(options_json) if options_json else {}
    except Exception:
        opts = {}

    user_agent = opts.get("user_agent") or "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    cookies = opts.get("cookies")
    cookie_dict = _parse_cookie_dict(cookies)

    items = []
    page_title = ""
    seen_urls = set()

    try:
        with _gdl_lock:
            gdl_config.clear()
            gdl_config.set(("extractor",), "user-agent", user_agent)
            if cookie_dict:
                gdl_config.set(("extractor",), "cookies", cookie_dict)

            ext = gdl_extractor.find(clean_url)
            if ext:
                data_job = gdl_job.DataJob(ext, file=None, resolve=True)
                data_job.run()

                for post in getattr(data_job, "data_post", []):
                    if isinstance(post, dict):
                        candidate_title = post.get("title") or post.get("gallery_title") or post.get("album_title") or post.get("post_title") or post.get("description") or post.get("name")
                        if candidate_title and not page_title:
                            page_title = str(candidate_title)[:120]

                for entry in getattr(data_job, "data", []):
                    if not isinstance(entry, (list, tuple)) or len(entry) < 2:
                        continue

                    item_url = None
                    kwdict = {}

                    if len(entry) >= 3 and isinstance(entry[1], str) and (entry[1].startswith("http://") or entry[1].startswith("https://")):
                        item_url = entry[1]
                        if isinstance(entry[2], dict):
                            kwdict = entry[2]
                    elif isinstance(entry[1], dict):
                        kwdict = entry[1]
                        item_url = kwdict.get("url") or kwdict.get("file_url") or kwdict.get("video_url")

                    if not item_url:
                        continue

                    # Upgrade Imgur .gifv pseudo-GIFs to real .mp4 video streams
                    if item_url.lower().endswith(".gifv"):
                        item_url = item_url[:-5] + ".mp4"
                        kwdict["extension"] = "mp4"

                    if item_url in seen_urls:
                        continue

                    seen_urls.add(item_url)

                    fn = kwdict.get("filename") or kwdict.get("name") or kwdict.get("title") or kwdict.get("id") or ""
                    ext_suffix = kwdict.get("extension") or ""
                    if fn and ext_suffix and not str(fn).endswith("." + str(ext_suffix)):
                        item_title = f"{fn}.{ext_suffix}"
                    elif fn:
                        item_title = str(fn)
                    else:
                        clean_path = item_url.split("?")[0].split("#")[0]
                        item_title = os.path.basename(clean_path) or f"media_{len(items) + 1}.bin"

                    if not page_title:
                        page_title = kwdict.get("gallery_title") or kwdict.get("album_title") or kwdict.get("title") or kwdict.get("description") or kwdict.get("author") or ""

                    lower_url = item_url.lower()
                    lower_ext = str(ext_suffix).lower() if ext_suffix else ""
                    is_video = (
                        lower_ext in ["mp4", "webm", "mkv", "mov", "avi", "gifv"]
                        or lower_url.endswith(".mp4")
                        or lower_url.endswith(".webm")
                        or ".mp4?" in lower_url
                        or "v.redd.it/" in lower_url
                        or "v1.pinimg.com/videos/" in lower_url
                        or "format=mp4" in lower_url
                    )
                    is_stream = lower_ext in ["m3u8", "mpd"] or lower_url.endswith(".m3u8") or ".m3u8?" in lower_url or lower_url.endswith(".mpd")
                    is_audio = lower_ext in ["mp3", "m4a", "aac", "wav", "flac", "ogg", "opus"] or lower_url.endswith(".mp3") or lower_url.endswith(".m4a")

                    if is_stream:
                        category = "STREAMS"
                        format_label = "HLS/DASH"
                        mime_type = "application/x-mpegURL"
                    elif is_video:
                        category = "VIDEOS"
                        format_label = lower_ext.upper() if lower_ext in ["mp4", "webm", "mkv", "mov", "avi"] else "MP4"
                        mime_type = f"video/{lower_ext}" if lower_ext in ["mp4", "webm", "mkv", "mov"] else "video/mp4"
                    elif is_audio:
                        category = "AUDIO"
                        format_label = lower_ext.upper() if lower_ext else "MP3"
                        mime_type = f"audio/{lower_ext}" if lower_ext else "audio/mpeg"
                    else:
                        category = "IMAGES"
                        format_label = lower_ext.upper() if lower_ext else "IMAGE"
                        mime_type = f"image/{lower_ext}" if lower_ext in ["png", "jpeg", "jpg", "webp", "gif", "avif"] else "image/jpeg"

                    width = kwdict.get("width")
                    height = kwdict.get("height")
                    resolution_hint = f"{width}x{height}" if width and height else None
                    size_bytes = kwdict.get("filesize") or kwdict.get("size")
                    try:
                        size_bytes = int(size_bytes) if size_bytes else None
                    except Exception:
                        size_bytes = None

                    thumb = (
                        kwdict.get("thumbnail")
                        or kwdict.get("thumb")
                        or kwdict.get("display_url")
                        or kwdict.get("preview_url")
                        or kwdict.get("poster")
                        or (item_url if category == "IMAGES" else None)
                    )

                    # Route Reddit DASH videos and HLS streams extracted by gallery-dl through yt-dlp so audio is muxed
                    item_source = "yt-dlp" if (("v.redd.it/" in lower_url and is_video) or is_stream) else "gallery-dl"

                    url_hash = hashlib.sha256(item_url.encode('utf-8')).hexdigest()[:12]
                    items.append({
                        "id": f"gdl_{len(items)}_{url_hash}",
                        "url": item_url,
                        "title": item_title,
                        "category": category,
                        "format": format_label,
                        "mimeType": mime_type,
                        "isVideo": is_video or is_stream,
                        "isAudio": is_audio,
                        "isStream": is_stream,
                        "thumbnailUrl": thumb,
                        "resolutionHint": resolution_hint,
                        "sizeBytes": size_bytes,
                        "source": item_source
                    })
    except Exception as e:
        return json.dumps({
            "success": False,
            "error": f"{e.__class__.__name__}: {str(e)}",
            "items": []
        })

    return json.dumps({
        "success": len(items) > 0,
        "engine": "gallery-dl",
        "pageTitle": str(page_title) if page_title else "",
        "itemsCount": len(items),
        "items": items
    })

def _get_android_sdk_int():
    try:
        from java import jclass
        ver_cls = jclass("android.os.Build$VERSION")
        return int(getattr(ver_cls, "SDK_INT", 34))
    except Exception:
        return 34

def _is_webm_pair(video_fmt, audio_fmt=None):
    v_ext = ((video_fmt or {}).get("ext") or "").lower()
    v_codec = ((video_fmt or {}).get("vcodec") or "").lower()
    if v_codec.startswith(("avc", "h264", "hev", "hvc", "mp4v", "av01")):
        return False
    return v_ext == "webm" or v_codec.startswith(("vp9", "vp09", "vp8"))

def _select_best_ytdlp_formats(formats, max_height=None, sdk_int=None):
    """
    Analyzes yt-dlp formats list across all resolutions (up to 2K/4K/8K) and selects either:
    - ('muxed', muxed_fmt, None) when a pre-muxed stream matches the highest available resolution, or
    - ('split', video_fmt, audio_fmt) pairing strictly container-compatible tracks:
        * MP4 container family: H.264/HEVC (and AV1 on Android 14+ / API 34+) video + M4A/AAC audio, or
        * WebM container family: VP9/VP8 2K/4K/8K video + Opus/Vorbis audio (supported via MUXER_OUTPUT_WEBM on API 26+).
    Never selects a silent video-only format when audio tracks exist, and never mixes H.264 video with WebM-only audio.
    """
    if not formats:
        return ("default", None, None)

    if sdk_int is None:
        sdk_int = _get_android_sdk_int()

    def _height_ok(f):
        h = f.get("height")
        if max_height and int(max_height) > 0 and h and int(h) > int(max_height):
            return False
        return True

    def _is_direct_http_protocol(f):
        proto = (f.get("protocol") or "").lower()
        return proto in ("https", "http", "")

    muxed = [
        f for f in formats
        if f.get("vcodec") not in (None, "none")
        and f.get("acodec") not in (None, "none")
        and _height_ok(f)
    ]

    video_only = [
        f for f in formats
        if f.get("vcodec") not in (None, "none")
        and f.get("acodec") in (None, "none")
        and _height_ok(f)
    ]

    audio_only = [
        f for f in formats
        if f.get("acodec") not in (None, "none")
        and f.get("vcodec") in (None, "none")
    ]

    # 1. MP4 Family (MUXER_OUTPUT_MPEG_4): AVC, HEVC, MP4V, plus AV1 on Android 14+ (API 34+)
    def _is_mp4_compatible_video(f):
        ext = (f.get("ext") or "").lower()
        vc = (f.get("vcodec") or "").lower()
        proto = (f.get("protocol") or "").lower()
        if "m3u8" in proto:
            return False
        if vc.startswith(("vp9", "vp09", "vp8")):
            return False
        if vc.startswith("av01"):
            return int(sdk_int) >= 34
        return ext in ("mp4", "m4v", "mov") or vc.startswith(("avc", "h264", "hev", "hvc", "mp4v"))

    def _is_mp4_compatible_audio(f):
        ext = (f.get("ext") or "").lower()
        ac = (f.get("acodec") or "").lower()
        proto = (f.get("protocol") or "").lower()
        if "m3u8" in proto:
            return False
        if ac.startswith(("opus", "vorbis")):
            return False
        return ext in ("m4a", "mp4", "aac") or ac.startswith(("mp4a", "aac"))

    # 2. WebM Family (MUXER_OUTPUT_WEBM): VP9 / VP09 / VP8 video + Opus / Vorbis audio (4K/8K YouTube/Vimeo)
    def _is_webm_compatible_video(f):
        ext = (f.get("ext") or "").lower()
        vc = (f.get("vcodec") or "").lower()
        proto = (f.get("protocol") or "").lower()
        if "m3u8" in proto:
            return False
        return vc.startswith(("vp9", "vp09", "vp8")) or (ext == "webm" and not vc.startswith("av01"))

    def _is_webm_compatible_audio(f):
        ext = (f.get("ext") or "").lower()
        ac = (f.get("acodec") or "").lower()
        proto = (f.get("protocol") or "").lower()
        if "m3u8" in proto:
            return False
        return ac.startswith(("opus", "vorbis")) or ext in ("webm", "ogg")

    mp4_videos = [f for f in video_only if _is_mp4_compatible_video(f)]
    mp4_audios = [f for f in audio_only if _is_mp4_compatible_audio(f)]
    webm_videos = [f for f in video_only if _is_webm_compatible_video(f)]
    webm_audios = [f for f in audio_only if _is_webm_compatible_audio(f)]

    def _video_score(f):
        return (
            int(f.get("height") or 0),
            int(f.get("width") or 0),
            1 if _is_direct_http_protocol(f) else 0,
            float(f.get("fps") or 0.0),
            float(f.get("tbr") or f.get("vbr") or 0.0),
            int(f.get("filesize") or f.get("filesize_approx") or 0)
        )

    def _audio_score(f):
        return (
            1 if _is_direct_http_protocol(f) else 0,
            float(f.get("abr") or f.get("tbr") or 0.0),
            int(f.get("asr") or 0),
            int(f.get("filesize") or f.get("filesize_approx") or 0)
        )

    best_muxed = max(muxed, key=_video_score) if muxed else None
    best_mp4_video = max(mp4_videos, key=_video_score) if mp4_videos else None
    best_mp4_audio = max(mp4_audios, key=_audio_score) if mp4_audios else None
    best_webm_video = max(webm_videos, key=_video_score) if webm_videos else None
    best_webm_audio = max(webm_audios, key=_audio_score) if webm_audios else None

    # Pick the highest-resolution valid (video, audio) split pair across MP4 and WebM families
    split_candidates = []
    if best_mp4_video and best_mp4_audio:
        # Tie-breaker +1 prefers MP4 when MP4 and WebM have identical height/width/fps
        split_candidates.append((_video_score(best_mp4_video) + (1,), best_mp4_video, best_mp4_audio))
    if best_webm_video and best_webm_audio:
        split_candidates.append((_video_score(best_webm_video) + (0,), best_webm_video, best_webm_audio))

    best_split_pair = max(split_candidates, key=lambda c: c[0]) if split_candidates else None

    if best_split_pair:
        _, split_v, split_a = best_split_pair
        muxed_h = int(best_muxed.get("height") or 0) if best_muxed else 0
        split_h = int(split_v.get("height") or 0)
        if not best_muxed or split_h > muxed_h:
            return ("split", split_v, split_a)

    if best_muxed:
        return ("muxed", best_muxed, None)

    if best_split_pair:
        _, split_v, split_a = best_split_pair
        return ("split", split_v, split_a)

    # Only if the media genuinely has no audio tracks on the server (e.g. silent GIF/clip)
    if not audio_only and video_only:
        best_silent = max(mp4_videos or webm_videos or video_only, key=_video_score)
        return ("muxed", best_silent, None)

    return ("default", None, None)

def _extract_best_thumbnail(entry):
    if not isinstance(entry, dict):
        return None
    thumb = entry.get("thumbnail")
    if thumb and isinstance(thumb, str) and thumb.startswith(("http://", "https://")):
        return thumb
    thumbs = entry.get("thumbnails")
    if isinstance(thumbs, list) and thumbs:
        valid = [t for t in thumbs if isinstance(t, dict) and isinstance(t.get("url"), str) and t["url"].startswith(("http://", "https://"))]
        if valid:
            best_t = max(valid, key=lambda t: (int(t.get("height") or 0), int(t.get("width") or 0), int(t.get("preference") or 0)))
            return best_t.get("url")
    return None

def extract_yt_dlp(url, options_json="{}"):
    """
    Extracts media items using yt-dlp exclusively.
    Inspects both pre-muxed and split DASH formats so full resolution, multi-item carousels,
    and audio availability are preserved.
    """
    clean_url = url.strip() if url else ""
    if not clean_url or not YTDLP_AVAILABLE:
        return json.dumps({"success": False, "items": [], "error": "yt-dlp not available or empty URL"})

    try:
        opts = json.loads(options_json) if options_json else {}
    except Exception:
        opts = {}

    user_agent = opts.get("user_agent") or "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    cookies = opts.get("cookies")
    max_height = opts.get("max_height")
    audio_only = bool(opts.get("audio_only", False))

    items = []
    page_title = ""
    seen_entry_keys = set()

    try:
        http_headers = {"User-Agent": user_agent}
        if cookies:
            http_headers["Cookie"] = cookies

        probe_target = _normalize_reddit_probe_url(clean_url)
        ydl_opts = {
            "quiet": True,
            "no_warnings": True,
            "skip_download": True,
            "socket_timeout": 20,
            "user_agent": user_agent,
            "http_headers": http_headers
        }
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(probe_target, download=False)
            if info:
                top_title = info.get("title")
                if top_title and not page_title:
                    page_title = str(top_title)

                raw_entries = info.get("entries")
                entries = list(raw_entries) if raw_entries is not None else [info]
                total_entries = len(entries)

                for idx, entry in enumerate(entries):
                    if not entry or not isinstance(entry, dict):
                        continue

                    formats = entry.get("formats") or []
                    mode, primary_fmt, secondary_fmt = _select_best_ytdlp_formats(formats, max_height=max_height)

                    entry_id = str(entry.get("id") or f"idx_{idx}")
                    page_url = entry.get("webpage_url") or clean_url

                    # For multi-video carousels (e.g. Instagram / X / Reddit multi-clip posts),
                    # if entries share the same parent webpage_url and mode is muxed with a direct stream URL,
                    # use the direct stream URL so each slide has its own distinct downloadable URL.
                    direct_fmt_url = (primary_fmt or {}).get("url") or entry.get("url")
                    if total_entries > 1 and page_url == clean_url and mode == "muxed" and direct_fmt_url and direct_fmt_url.startswith(("http://", "https://")):
                        direct_url = direct_fmt_url
                    else:
                        direct_url = page_url

                    dedup_entry_key = f"{direct_url}#{entry_id}"
                    if dedup_entry_key in seen_entry_keys:
                        continue
                    seen_entry_keys.add(dedup_entry_key)

                    v_title = entry.get("title") or entry.get("id") or f"media_{len(items) + 1}"
                    if audio_only:
                        v_ext = "m4a"
                    elif mode == "split":
                        v_ext = "webm" if _is_webm_pair(primary_fmt, secondary_fmt) else "mp4"
                    else:
                        v_ext = (primary_fmt or entry).get("ext") or "mp4"
                    if v_ext in ("m3u8", "mpd", "unknown_video", "gifv"):
                        v_ext = "mp4"
                    item_title = f"{v_title}.{v_ext}" if not str(v_title).endswith(f".{v_ext}") else str(v_title)

                    if not page_title:
                        page_title = str(v_title)

                    width = (primary_fmt or {}).get("width") or entry.get("width")
                    height = (primary_fmt or {}).get("height") or entry.get("height")
                    resolution_hint = f"{width}x{height}" if (width and height and not audio_only) else None
                    thumb = _extract_best_thumbnail(entry)

                    if mode == "split" and primary_fmt and secondary_fmt and not audio_only:
                        v_size = primary_fmt.get("filesize") or primary_fmt.get("filesize_approx") or 0
                        a_size = secondary_fmt.get("filesize") or secondary_fmt.get("filesize_approx") or 0
                        filesize = (v_size + a_size) if (v_size and a_size) else (entry.get("filesize") or entry.get("filesize_approx"))
                    elif primary_fmt:
                        filesize = primary_fmt.get("filesize") or primary_fmt.get("filesize_approx") or entry.get("filesize") or entry.get("filesize_approx")
                    else:
                        filesize = entry.get("filesize") or entry.get("filesize_approx")

                    is_audio_only = audio_only or (entry.get("vcodec") == "none" and mode != "split") or v_ext in ["mp3", "m4a", "opus", "aac"]
                    is_stream = (".m3u8" in clean_url) or (".mpd" in clean_url)
                    category = "AUDIO" if is_audio_only else ("STREAMS" if is_stream else "VIDEOS")

                    url_hash = hashlib.sha256(dedup_entry_key.encode('utf-8')).hexdigest()[:12]
                    items.append({
                        "id": f"ytdlp_{len(items)}_{url_hash}",
                        "url": direct_url,
                        "title": item_title,
                        "category": category,
                        "format": v_ext.upper(),
                        "mimeType": f"audio/{'mp4' if v_ext == 'm4a' else v_ext}" if is_audio_only else ("application/x-mpegURL" if is_stream else f"video/{v_ext}"),
                        "isVideo": not is_audio_only,
                        "isAudio": is_audio_only,
                        "isStream": is_stream,
                        "thumbnailUrl": thumb,
                        "resolutionHint": resolution_hint,
                        "sizeBytes": filesize,
                        "source": "yt-dlp"
                    })
    except Exception as e:
        return json.dumps({
            "success": False,
            "error": f"{e.__class__.__name__}: {str(e)}",
            "items": []
        })

    return json.dumps({
        "success": len(items) > 0,
        "engine": "yt-dlp",
        "pageTitle": str(page_title) if page_title else "",
        "itemsCount": len(items),
        "items": items
    })

def extract_media(url, options_json="{}"):
    """
    Extracts all media items using gallery-dl AND yt-dlp progressively.
    Merges duplicate video entries when both gallery-dl and yt-dlp extract the same post video.
    """
    clean_url = url.strip() if url else ""
    if not clean_url:
        return json.dumps({
            "success": False,
            "error": "URL cannot be empty",
            "items": []
        })

    all_items = []
    seen_urls = set()
    page_title = ""

    # 1. Run gallery-dl
    gdl_res_str = extract_gallery_dl(clean_url, options_json)
    try:
        gdl_res = json.loads(gdl_res_str)
        if gdl_res.get("pageTitle"):
            page_title = gdl_res["pageTitle"]
        for itm in gdl_res.get("items", []):
            u = itm.get("url")
            if u and u not in seen_urls:
                seen_urls.add(u)
                all_items.append(itm)
    except Exception:
        pass

    # 2. Run yt-dlp (ALWAYS run, even if gallery-dl found items, e.g. embedded video in social post)
    ytdlp_res_str = extract_yt_dlp(clean_url, options_json)
    try:
        ytdlp_res = json.loads(ytdlp_res_str)
        if not page_title and ytdlp_res.get("pageTitle"):
            page_title = ytdlp_res["pageTitle"]
        ytdlp_items = ytdlp_res.get("items", [])
        gdl_video_indices = [i for i, x in enumerate(all_items) if x.get("isVideo")]

        for itm in ytdlp_items:
            u = itm.get("url")
            if not u or u in seen_urls:
                continue
            # If gallery-dl already extracted a single video from this exact post without a thumbnail,
            # upgrade it with yt-dlp's rich metadata & thumbnail instead of creating a duplicate card.
            if len(ytdlp_items) == 1 and len(gdl_video_indices) == 1 and itm.get("isVideo"):
                gdl_idx = gdl_video_indices[0]
                existing_vid = all_items[gdl_idx]
                merged_vid = dict(itm)
                if not merged_vid.get("thumbnailUrl") and existing_vid.get("thumbnailUrl"):
                    merged_vid["thumbnailUrl"] = existing_vid["thumbnailUrl"]
                all_items[gdl_idx] = merged_vid
                seen_urls.add(u)
                continue
            seen_urls.add(u)
            all_items.append(itm)
    except Exception:
        pass

    return json.dumps({
        "success": len(all_items) > 0,
        "engine": "hybrid (gallery-dl + yt-dlp)",
        "pageTitle": str(page_title) if page_title else "",
        "itemsCount": len(all_items),
        "items": all_items
    })

def _download_with_ytdlp_and_mux(media_url, target_path, http_headers, referer=None, progress_callback=None, max_height=0, audio_only=False):
    """
    Downloads a video/stream with yt-dlp, ensuring audio is always preserved:
    1. Probes available formats for media_url (or normalized Reddit URL if media_url is a raw v.redd.it DASH video).
    2. Reuses extracted format info to avoid 3x-5x redundant GraphQL/API requests that trigger HTTP 429 on Instagram/Reddit.
    3. If split DASH video + audio tracks are needed, downloads video_part and audio_part and muxes them with AndroidMediaMuxer.
    4. Otherwise downloads a verified pre-muxed format (vcodec!=none and acodec!=none).
    """
    os.makedirs(os.path.dirname(target_path), exist_ok=True)
    probe_url = _normalize_reddit_probe_url(media_url, referer=referer)

    probe_opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "socket_timeout": 25,
        "http_headers": http_headers
    }

    info = None
    try:
        with yt_dlp.YoutubeDL(probe_opts) as ydl:
            extracted = ydl.extract_info(probe_url, download=False)
            if extracted:
                entries = extracted.get("entries") or [extracted]
                info = next((e for e in entries if isinstance(e, dict)), None)
    except Exception:
        info = None

    formats = (info or {}).get("formats") or []
    if audio_only:
        a_opts = {
            "outtmpl": target_path,
            "quiet": True,
            "no_warnings": True,
            "continuedl": True,
            "format": "bestaudio[ext=m4a]/bestaudio[ext=mp3]/bestaudio/best",
            "socket_timeout": 30,
            "http_headers": http_headers,
            "progress_hooks": [_make_ydl_progress_hook(progress_callback, 0.0, 1.0)]
        }
        with yt_dlp.YoutubeDL(a_opts) as ydl_a:
            ydl_a.download([probe_url])
        return os.path.exists(target_path) and os.path.getsize(target_path) > 0

    if max_height and max_height > 0:
        capped = [f for f in formats if not f.get("height") or int(f.get("height") or 0) <= max_height]
        if capped:
            formats = capped
    mode, primary_fmt, secondary_fmt = _select_best_ytdlp_formats(formats)

    # Case A: Split DASH video + audio tracks (up to 2K/4K/8K MP4 or WebM VP9+Opus, Reddit v.redd.it, YouTube, Reels)
    if mode == "split" and primary_fmt and secondary_fmt:
        video_fmt_id = primary_fmt.get("format_id")
        audio_fmt_id = secondary_fmt.get("format_id")
        is_webm = _is_webm_pair(primary_fmt, secondary_fmt)
        video_part_path = target_path + (".video_part.webm" if is_webm else ".video_part.mp4")
        audio_part_path = target_path + (".audio_part.webm" if is_webm else ".audio_part.m4a")
        try:
            for p in (video_part_path, audio_part_path):
                if os.path.exists(p):
                    os.remove(p)

            v_fallback = "bestvideo[ext=webm]/bestvideo" if is_webm else "bestvideo[ext=mp4]/bestvideo"
            v_opts = {
                "outtmpl": video_part_path,
                "quiet": True,
                "no_warnings": True,
                "continuedl": True,
                "format": f"{video_fmt_id}/{v_fallback}",
                "socket_timeout": 30,
                "http_headers": http_headers,
                "progress_hooks": [_make_ydl_progress_hook(progress_callback, 0.0, 0.78)]
            }
            with yt_dlp.YoutubeDL(v_opts) as ydl_v:
                ydl_v.download([probe_url])

            a_fallback = "bestaudio[acodec^=opus]/bestaudio[ext=webm]/bestaudio" if is_webm else "bestaudio[ext=m4a]/bestaudio[ext=mp4]/bestaudio"
            a_opts = {
                "outtmpl": audio_part_path,
                "quiet": True,
                "no_warnings": True,
                "continuedl": True,
                "format": f"{audio_fmt_id}/{a_fallback}",
                "socket_timeout": 30,
                "http_headers": http_headers,
                "progress_hooks": [_make_ydl_progress_hook(progress_callback, 0.78, 0.20)]
            }
            with yt_dlp.YoutubeDL(a_opts) as ydl_a:
                ydl_a.download([probe_url])

            if os.path.exists(video_part_path) and os.path.getsize(video_part_path) > 0 and os.path.exists(audio_part_path) and os.path.getsize(audio_part_path) > 0:
                muxed = _mux_with_android_media_muxer(video_part_path, audio_part_path, target_path)
                if muxed and os.path.exists(target_path) and os.path.getsize(target_path) > 0:
                    return True
                # If running outside Android JVM or if Kotlin side will mux, keep both part files so GalleryDlEngine can mux them
                if os.path.exists(video_part_path) and os.path.exists(audio_part_path):
                    return True
        except Exception:
            for p in (video_part_path, audio_part_path):
                try:
                    if os.path.exists(p):
                        os.remove(p)
                except Exception:
                    pass

    # Case B: Pre-muxed stream with both video and audio (never bare /best unless server has no audio at all)
    has_any_audio = any(f.get("acodec") not in (None, "none") for f in formats)
    if mode == "muxed" and primary_fmt and primary_fmt.get("format_id"):
        fmt_spec = f"{primary_fmt['format_id']}/b[vcodec!=none][acodec!=none]/best[vcodec!=none][acodec!=none]"
    elif has_any_audio or not formats:
        fmt_spec = "b[vcodec!=none][acodec!=none]/best[vcodec!=none][acodec!=none]"
    else:
        fmt_spec = "b[vcodec!=none][acodec!=none]/best[vcodec!=none][acodec!=none]/best"

    ydl_opts = {
        "outtmpl": target_path,
        "quiet": True,
        "no_warnings": True,
        "continuedl": True,
        "format": fmt_spec,
        "socket_timeout": 30,
        "http_headers": http_headers,
        "progress_hooks": [_make_ydl_progress_hook(progress_callback, 0.0, 1.0)]
    }
    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        ydl.download([probe_url])

    if os.path.exists(target_path) and os.path.getsize(target_path) > 0:
        with open(target_path, "rb") as f:
            head = f.read(512).lower()
        if b"#extm3u" in head or b"<mpd" in head:
            os.remove(target_path)
            return False
        return True
    return False

def download_media_file(media_url, target_path, referer=None, user_agent=None, cookies=None, headers_json=None, progress_callback=None):
    """
    Downloads media file using yt-dlp (with pre-muxed or split DASH muxing) or resumable requests with cookies/headers.
    Never commits raw .m3u8/.mpd playlist text or silent video-only streams when audio is available.
    """
    extra_headers = {}
    max_height = 0
    audio_only = False
    if headers_json:
        try:
            parsed = json.loads(headers_json)
            if isinstance(parsed, dict):
                max_height = int(parsed.pop("__max_height", 0) or 0)
                audio_only = bool(parsed.pop("__audio_only", False))
                extra_headers = {str(k): str(v) for k, v in parsed.items() if not str(k).startswith("__")}
        except Exception:
            pass

    ua = user_agent or "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    lower_url = media_url.lower()
    is_stream = (".m3u8" in lower_url) or (".mpd" in lower_url)
    is_direct_raster_image = any(lower_url.split("?")[0].endswith(ext) for ext in [".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".bmp", ".svg"]) and "format=mp4" not in lower_url
    is_social_or_video = (not is_direct_raster_image) and any(h in lower_url for h in SOCIAL_VIDEO_HOSTS)

    # 1. For streams or social video pages, prioritize yt-dlp with DASH audio+video mux support
    if (is_stream or is_social_or_video) and YTDLP_AVAILABLE:
        try:
            http_headers = {"User-Agent": ua}
            if referer:
                http_headers["Referer"] = referer
            if cookies:
                http_headers["Cookie"] = cookies
            http_headers.update(extra_headers)

            if _download_with_ytdlp_and_mux(media_url, target_path, http_headers, referer=referer, progress_callback=progress_callback, max_height=max_height, audio_only=audio_only):
                return True
        except Exception:
            pass

    # 2. Direct HTTP requests download with HTTP Range resume and progress reporting
    try:
        import requests
        os.makedirs(os.path.dirname(target_path), exist_ok=True)
        part_path = target_path + ".downloading"
        existing_bytes = os.path.getsize(part_path) if os.path.exists(part_path) else 0

        headers = {"User-Agent": ua}
        if referer:
            headers["Referer"] = referer
        if cookies:
            headers["Cookie"] = cookies
        headers.update(extra_headers)
        if existing_bytes > 0:
            headers["Range"] = f"bytes={existing_bytes}-"

        resp = requests.get(media_url, headers=headers, stream=True, timeout=35)
        if resp.status_code == 416 and existing_bytes > 0:
            resp.close()
            if os.path.exists(part_path):
                os.remove(part_path)
            existing_bytes = 0
            headers.pop("Range", None)
            resp = requests.get(media_url, headers=headers, stream=True, timeout=35)

        if resp.status_code in [200, 206]:
            content_type = (resp.headers.get("content-type") or "").lower()
            if "text/html" in content_type or "application/json" in content_type:
                return False

            is_partial = (resp.status_code == 206 and existing_bytes > 0)
            if not is_partial and existing_bytes > 0:
                existing_bytes = 0

            content_length = int(resp.headers.get("content-length") or 0)
            total_bytes = (existing_bytes + content_length) if (is_partial and content_length > 0) else (content_length if content_length > 0 else -1)

            first_chunk = not is_partial
            downloaded = existing_bytes
            last_emit_time = time.time()
            last_emit_bytes = downloaded
            mode_str = "ab" if is_partial else "wb"

            with open(part_path, mode_str) as f:
                for chunk in resp.iter_content(chunk_size=65536):
                    if chunk:
                        if first_chunk:
                            first_chunk = False
                            prefix = chunk[:1024].lower()
                            if (b"<!doctype html" in prefix or b"<html" in prefix or b"<head" in prefix or
                                b"cloudflare" in prefix or b"just a moment..." in prefix or
                                b"403 forbidden" in prefix or b"404 not found" in prefix or b"access denied" in prefix):
                                f.close()
                                if os.path.exists(part_path):
                                    os.remove(part_path)
                                return False
                            trimmed = prefix.strip()
                            if (trimmed.startswith(b"{") or trimmed.startswith(b"[")) and (
                                b'"error"' in trimmed or b'"message"' in trimmed or b'"status"' in trimmed or b'"forbidden"' in trimmed
                            ):
                                f.close()
                                if os.path.exists(part_path):
                                    os.remove(part_path)
                                return False
                            if b"#extm3u" in prefix or b"<mpd" in prefix:
                                f.close()
                                if os.path.exists(part_path):
                                    os.remove(part_path)
                                return False
                        f.write(chunk)
                        downloaded += len(chunk)
                        now = time.time()
                        if now - last_emit_time >= 0.25:
                            dt = now - last_emit_time
                            speed = int((downloaded - last_emit_bytes) / dt) if dt > 0 else 0
                            last_emit_time = now
                            last_emit_bytes = downloaded
                            _notify_progress(progress_callback, downloaded, total_bytes, speed)

            if os.path.exists(part_path) and os.path.getsize(part_path) > 0:
                os.replace(part_path, target_path)
                _notify_progress(progress_callback, downloaded, total_bytes if total_bytes > 0 else downloaded, 0)
                return True
    except Exception:
        pass

    # 3. Fallback to yt-dlp if direct request failed and target is a video/stream
    if YTDLP_AVAILABLE and not is_direct_raster_image and (is_stream or any(ext in lower_url for ext in [".mp4", ".mkv", ".webm", ".m3u8", ".mpd"] + SOCIAL_VIDEO_HOSTS)):
        try:
            http_headers = {"User-Agent": ua}
            if referer:
                http_headers["Referer"] = referer
            if cookies:
                http_headers["Cookie"] = cookies
            http_headers.update(extra_headers)

            if _download_with_ytdlp_and_mux(media_url, target_path, http_headers, referer=referer, progress_callback=progress_callback, max_height=max_height, audio_only=audio_only):
                return True
        except Exception:
            pass

    return False
