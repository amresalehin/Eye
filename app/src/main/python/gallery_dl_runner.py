import json
import os
import sys
import time
import traceback
import hashlib
import re
import shutil
import threading

_gdl_lock = threading.Lock()


def _parse_cookie_dict(cookies_str):
    if not cookies_str:
        return {}
    cookie_dict = {}
    for part in str(cookies_str).split(";"):
        part = part.strip()
        if "=" in part:
            k, v = part.split("=", 1)
            k = k.strip()
            if k:
                cookie_dict[k] = v.strip()
    return cookie_dict


def _parse_duration_millis(raw_val):
    if raw_val is None or isinstance(raw_val, bool):
        return None
    if isinstance(raw_val, (int, float)):
        val = float(raw_val)
        return int(round(val * 1000.0)) if val > 0 else None
    text = str(raw_val).strip()
    if not text:
        return None
    # Numeric string (seconds)
    try:
        val = float(text)
        return int(round(val * 1000.0)) if val > 0 else None
    except Exception:
        pass
    # HH:MM:SS or MM:SS
    if ":" in text:
        parts = text.split(":")
        if 2 <= len(parts) <= 3:
            try:
                nums = [float(p.strip()) for p in parts]
                total_sec = (nums[0] * 3600 + nums[1] * 60 + nums[2]) if len(nums) == 3 else (nums[0] * 60 + nums[1])
                return int(round(total_sec * 1000.0)) if total_sec > 0 else None
            except Exception:
                pass
    # ISO-8601 duration e.g. PT1H2M34S
    iso_match = re.match(r"^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$", text, re.IGNORECASE)
    if iso_match and any(iso_match.groups()):
        days = float(iso_match.group(1) or 0)
        hours = float(iso_match.group(2) or 0)
        mins = float(iso_match.group(3) or 0)
        secs = float(iso_match.group(4) or 0)
        total_sec = days * 86400 + hours * 3600 + mins * 60 + secs
        return int(round(total_sec * 1000.0)) if total_sec > 0 else None
    return None

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
    {"name": "Imgur", "domains": ["imgur.com", "i.imgur.com"], "icon": "image"},
    {"name": "Twitter / X", "domains": ["twitter.com", "x.com", "pbs.twimg.com"], "icon": "tag"},
    {"name": "Pinterest", "domains": ["pinterest.com", "i.pinimg.com"], "icon": "pin"},
    {"name": "ArtStation", "domains": ["artstation.com"], "icon": "brush"},
    {"name": "DeviantArt", "domains": ["deviantart.com"], "icon": "palette"},
    {"name": "Flickr", "domains": ["flickr.com"], "icon": "camera"}
]

SOCIAL_VIDEO_HOSTS = [
    "youtube.com", "youtu.be", "vimeo.com", "tiktok.com", "dailymotion.com",
    "twitch.tv", "soundcloud.com", "bandcamp.com", "facebook.com", "fb.watch",
    "instagram.com", "reddit.com", "redd.it", "v.redd.it", "twitter.com", "x.com"
]

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
    Checks if a URL is handled by gallery-dl or yt-dlp site extractors.
    """
    if not url:
        return json.dumps({"supported": False, "category": "", "extractor": "", "engine": ""})

    clean_url = url.strip()

    # 1. Check gallery-dl site extractors (skip generic directlink matcher)
    if GALLERY_DL_AVAILABLE:
        try:
            ext = gdl_extractor.find(clean_url)
            if ext is not None:
                cat = getattr(ext, "category", "") or ext.__class__.__name__.lower().replace("extractor", "")
                if cat != "directlink":
                    subcat = getattr(ext, "subcategory", "") or ""
                    name = ext.__class__.__name__
                    return json.dumps({
                        "supported": True,
                        "category": cat,
                        "subcategory": subcat,
                        "extractor": name,
                        "engine": "gallery-dl"
                    })
        except Exception:
            pass

    # 2. Check yt-dlp site extractors (fast regex check across all built-in extractors, excluding generic)
    if YTDLP_AVAILABLE:
        try:
            lower = clean_url.lower()
            if any(vh in lower for vh in SOCIAL_VIDEO_HOSTS) or ".m3u8" in lower or ".mpd" in lower:
                return json.dumps({
                    "supported": True,
                    "category": "video",
                    "subcategory": "stream",
                    "extractor": "YoutubeDL",
                    "engine": "yt-dlp"
                })
            for ydl_ext in yt_dlp.extractor.gen_extractors():
                ie_name = getattr(ydl_ext, "IE_NAME", "generic")
                if ie_name and ie_name.lower() != "generic" and ydl_ext.suitable(clean_url):
                    return json.dumps({
                        "supported": True,
                        "category": "video",
                        "subcategory": "extractor",
                        "extractor": ie_name,
                        "engine": "yt-dlp"
                    })
        except Exception:
            pass

    return json.dumps({"supported": False, "category": "", "extractor": "", "engine": ""})

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

    items = []
    page_title = ""
    seen_urls = set()

    try:
        with _gdl_lock:
            gdl_config.set(("extractor",), "user-agent", user_agent)
            gdl_config.set(("output",), "mode", "null")
            if cookies:
                gdl_config.set(("extractor",), "headers", {"Cookie": cookies})
                cookie_dict = _parse_cookie_dict(cookies)
                if cookie_dict:
                    gdl_config.set(("extractor",), "cookies", cookie_dict)

            ext = gdl_extractor.find(clean_url)
            if ext:
                data_job = gdl_job.DataJob(ext, file=None, resolve=True)
                data_job.run()

                for post in getattr(data_job, "data_post", []):
                    if isinstance(post, dict):
                        candidate_title = post.get("title") or post.get("gallery_title") or post.get("album_title") or post.get("post_title") or post.get("name")
                        if candidate_title and not page_title:
                            page_title = str(candidate_title)

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
                        item_url = kwdict.get("url") or kwdict.get("file_url")

                    if not item_url or item_url in seen_urls:
                        continue

                    seen_urls.add(item_url)

                    fn = kwdict.get("filename") or kwdict.get("name") or kwdict.get("title") or ""
                    ext_suffix = kwdict.get("extension") or ""
                    if fn and ext_suffix and not fn.endswith("." + ext_suffix):
                        item_title = f"{fn}.{ext_suffix}"
                    elif fn:
                        item_title = fn
                    else:
                        clean_path = item_url.split("?")[0].split("#")[0]
                        item_title = os.path.basename(clean_path) or f"media_{len(items) + 1}.bin"

                    if not page_title:
                        page_title = kwdict.get("gallery_title") or kwdict.get("album_title") or kwdict.get("title") or kwdict.get("author") or ""

                    lower_url = item_url.lower()
                    lower_ext = ext_suffix.lower() if ext_suffix else ""
                    is_video = lower_ext in ["mp4", "webm", "mkv", "mov", "avi"] or lower_url.endswith(".mp4") or lower_url.endswith(".webm") or ".mp4?" in lower_url or "v.redd.it/" in lower_url
                    is_stream = lower_ext in ["m3u8", "mpd"] or lower_url.endswith(".m3u8") or ".m3u8?" in lower_url or lower_url.endswith(".mpd")
                    is_audio = lower_ext in ["mp3", "m4a", "aac", "wav", "flac", "ogg", "opus"] or lower_url.endswith(".mp3") or lower_url.endswith(".m4a")

                    if is_stream:
                        category = "STREAMS"
                        format_label = "HLS/DASH"
                        mime_type = "application/x-mpegURL"
                    elif is_video:
                        category = "VIDEOS"
                        format_label = ext_suffix.upper() if ext_suffix else "MP4"
                        mime_type = f"video/{lower_ext}" if lower_ext else "video/mp4"
                    elif is_audio:
                        category = "AUDIO"
                        format_label = ext_suffix.upper() if ext_suffix else "MP3"
                        mime_type = f"audio/{lower_ext}" if lower_ext else "audio/mpeg"
                    else:
                        category = "IMAGES"
                        format_label = ext_suffix.upper() if ext_suffix else "IMAGE"
                        mime_type = f"image/{lower_ext}" if lower_ext in ["png", "jpeg", "jpg", "webp", "gif", "avif"] else "image/jpeg"

                    width = kwdict.get("width")
                    height = kwdict.get("height")
                    resolution_hint = f"{width}x{height}" if width and height else None
                    size_bytes = kwdict.get("filesize") or kwdict.get("size")
                    try:
                        size_bytes = int(size_bytes) if size_bytes else None
                    except Exception:
                        size_bytes = None

                    thumb = kwdict.get("thumbnail") or kwdict.get("thumb") or (item_url if category == "IMAGES" else None)
                    duration_ms = _parse_duration_millis(kwdict.get("duration"))
                    if duration_ms is None and kwdict.get("duration_ms") is not None:
                        try:
                            d_ms = int(kwdict.get("duration_ms"))
                            if d_ms > 0:
                                duration_ms = d_ms
                        except Exception:
                            pass

                    # Route Reddit DASH videos extracted by gallery-dl through yt-dlp so audio is muxed
                    item_source = "yt-dlp" if ("v.redd.it/" in lower_url and is_video) else "gallery-dl"

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
                        "durationMillis": duration_ms,
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
    a_codec = ((audio_fmt or {}).get("acodec") or "").lower()
    return (
        v_ext == "webm"
        or v_codec.startswith(("vp9", "vp09", "vp8"))
        or a_codec.startswith(("opus", "vorbis"))
    )

def _select_best_ytdlp_formats(formats, max_height=None, sdk_int=None):
    """
    Analyzes yt-dlp formats list across all resolutions (up to 2K/4K/8K) and selects either:
    - ('muxed', muxed_fmt, None) when a pre-muxed stream matches the highest available resolution, or
    - ('split', video_fmt, audio_fmt) pairing either:
        * MP4 container family: H.264/HEVC (and AV1 on Android 14+ / API 34+) video + M4A/AAC audio, or
        * WebM container family: VP9/VP8 2K/4K/8K video + Opus/Vorbis audio (supported via MUXER_OUTPUT_WEBM on API 26+).
    Never selects a silent video-only format when audio tracks exist.
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
        if vc.startswith(("vp9", "vp09", "vp8")):
            return False
        if vc.startswith("av01"):
            return int(sdk_int) >= 34
        return ext in ("mp4", "m4v", "mov") or vc.startswith(("avc", "h264", "hev", "hvc", "mp4v"))

    def _is_mp4_compatible_audio(f):
        ext = (f.get("ext") or "").lower()
        ac = (f.get("acodec") or "").lower()
        if ac.startswith(("opus", "vorbis")):
            return False
        return ext in ("m4a", "mp4", "aac") or ac.startswith(("mp4a", "aac"))

    # 2. WebM Family (MUXER_OUTPUT_WEBM): VP9 / VP09 / VP8 video + Opus / Vorbis audio (4K/8K YouTube/Vimeo)
    def _is_webm_compatible_video(f):
        ext = (f.get("ext") or "").lower()
        vc = (f.get("vcodec") or "").lower()
        return vc.startswith(("vp9", "vp09", "vp8")) or (ext == "webm" and not vc.startswith("av01"))

    def _is_webm_compatible_audio(f):
        ext = (f.get("ext") or "").lower()
        ac = (f.get("acodec") or "").lower()
        return ac.startswith(("opus", "vorbis")) or ext in ("webm", "ogg")

    mp4_videos = [f for f in video_only if _is_mp4_compatible_video(f)]
    mp4_audios = [f for f in audio_only if _is_mp4_compatible_audio(f)]
    webm_videos = [f for f in video_only if _is_webm_compatible_video(f)]
    webm_audios = [f for f in audio_only if _is_webm_compatible_audio(f)]

    def _video_score(f):
        return (
            int(f.get("height") or 0),
            int(f.get("width") or 0),
            float(f.get("fps") or 0.0),
            float(f.get("tbr") or f.get("vbr") or 0.0),
            int(f.get("filesize") or f.get("filesize_approx") or 0)
        )

    def _audio_score(f):
        return (
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
    if not split_candidates and best_mp4_video and audio_only:
        fallback_audio = max(audio_only, key=_audio_score)
        split_candidates.append((_video_score(best_mp4_video) + (0,), best_mp4_video, fallback_audio))

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

def extract_yt_dlp(url, options_json="{}"):
    """
    Extracts media items using yt-dlp exclusively.
    Inspects both pre-muxed and split DASH formats so full resolution and audio availability are preserved.
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

    items = []
    page_title = ""
    seen_urls = set()

    try:
        http_headers = {"User-Agent": user_agent}
        if cookies:
            http_headers["Cookie"] = cookies

        ydl_opts = {
            "quiet": True,
            "no_warnings": True,
            "skip_download": True,
            "socket_timeout": 20,
            "user_agent": user_agent,
            "http_headers": http_headers
        }
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(clean_url, download=False)
            if info:
                entries = info.get("entries") or [info]
                for entry in entries:
                    if not entry or not isinstance(entry, dict):
                        continue

                    # Preserve webpage URL so yt-dlp downloads and muxes non-expired video+audio streams at save time
                    page_url = entry.get("webpage_url") or clean_url
                    direct_url = page_url

                    if not direct_url or direct_url in seen_urls:
                        continue

                    seen_urls.add(direct_url)

                    formats = entry.get("formats") or []
                    mode, primary_fmt, secondary_fmt = _select_best_ytdlp_formats(formats, max_height=max_height)

                    v_title = entry.get("title") or entry.get("id") or f"media_{len(items) + 1}"
                    if mode == "split":
                        v_ext = "webm" if _is_webm_pair(primary_fmt, secondary_fmt) else "mp4"
                    else:
                        v_ext = (primary_fmt or entry).get("ext") or "mp4"
                    if v_ext in ("m3u8", "mpd", "unknown_video"):
                        v_ext = "mp4"
                    item_title = f"{v_title}.{v_ext}" if not v_title.endswith(f".{v_ext}") else v_title

                    if not page_title:
                        page_title = v_title

                    width = (primary_fmt or {}).get("width") or entry.get("width")
                    height = (primary_fmt or {}).get("height") or entry.get("height")
                    resolution_hint = f"{width}x{height}" if width and height else None
                    thumb = entry.get("thumbnail")

                    if mode == "split" and primary_fmt and secondary_fmt:
                        v_size = primary_fmt.get("filesize") or primary_fmt.get("filesize_approx") or 0
                        a_size = secondary_fmt.get("filesize") or secondary_fmt.get("filesize_approx") or 0
                        filesize = (v_size + a_size) if (v_size and a_size) else (entry.get("filesize") or entry.get("filesize_approx"))
                    elif primary_fmt:
                        filesize = primary_fmt.get("filesize") or primary_fmt.get("filesize_approx") or entry.get("filesize") or entry.get("filesize_approx")
                    else:
                        filesize = entry.get("filesize") or entry.get("filesize_approx")

                    is_audio_only = (entry.get("vcodec") == "none" and mode != "split") or v_ext in ["mp3", "m4a", "opus", "aac"]
                    is_stream = (".m3u8" in clean_url) or (".mpd" in clean_url)
                    category = "AUDIO" if is_audio_only else ("STREAMS" if is_stream else "VIDEOS")
                    duration_ms = _parse_duration_millis(
                        entry.get("duration")
                        if entry.get("duration") is not None
                        else (entry.get("duration_string") or info.get("duration"))
                    )

                    url_hash = hashlib.sha256(direct_url.encode('utf-8')).hexdigest()[:12]
                    items.append({
                        "id": f"ytdlp_{len(items)}_{url_hash}",
                        "url": direct_url,
                        "title": item_title,
                        "category": category,
                        "format": v_ext.upper(),
                        "mimeType": f"audio/{v_ext}" if is_audio_only else ("application/x-mpegURL" if is_stream else f"video/{v_ext}"),
                        "isVideo": not is_audio_only,
                        "isAudio": is_audio_only,
                        "isStream": is_stream,
                        "thumbnailUrl": thumb,
                        "resolutionHint": resolution_hint,
                        "sizeBytes": filesize,
                        "durationMillis": duration_ms,
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
    Does not allow partial gallery-dl results to prevent yt-dlp from running.
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
        for itm in ytdlp_res.get("items", []):
            u = itm.get("url")
            if u and u not in seen_urls:
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

def _validate_media_file_payload(file_path):
    """
    Verifies a downloaded file is non-empty and does not contain HTML, Cloudflare challenges,
    JSON error objects, or raw HLS/DASH playlist manifests.
    Returns (is_valid, error_message).
    """
    if not file_path or not os.path.exists(file_path):
        return False, "Downloaded file missing"
    if os.path.getsize(file_path) <= 0:
        return False, "Downloaded file was empty"
    try:
        with open(file_path, "rb") as f:
            prefix = f.read(1024).lower()
    except Exception as e:
        return False, f"Failed to read downloaded file: {e}"

    if (
        b"<!doctype html" in prefix
        or b"<html" in prefix
        or b"<head" in prefix
        or b"<body" in prefix
        or b"cloudflare" in prefix
        or b"ray id:" in prefix
        or b"just a moment..." in prefix
        or b"ddos protection" in prefix
        or b"challenge-running" in prefix
        or b"403 forbidden" in prefix
        or b"404 not found" in prefix
        or b"access denied" in prefix
        or b"502 bad gateway" in prefix
        or b"503 service" in prefix
    ):
        return False, "Downloaded file contains HTML webpage content instead of media"

    trimmed = prefix.strip()
    if (trimmed.startswith(b"{") or trimmed.startswith(b"[")) and (
        b'"error"' in trimmed
        or b'"message"' in trimmed
        or b'"status"' in trimmed
        or b'"code"' in trimmed
        or b'"forbidden"' in trimmed
        or b'"unauthorized"' in trimmed
        or b'"success":false' in trimmed
    ):
        return False, "Downloaded file contains JSON error response instead of media"

    if b"#extm3u" in prefix or b"<mpd" in prefix or b"urn:mpeg:dash:schema" in prefix:
        return False, "Cannot save streaming playlist text (.m3u8/.mpd) as finished media file"

    return True, None


def _extract_http_status_from_error(err_msg):
    if not err_msg:
        return -1
    m = re.search(r"HTTP\s*(?:Error|Status)?\s*(\d{3})", str(err_msg), re.IGNORECASE)
    if m:
        try:
            return int(m.group(1))
        except Exception:
            pass
    return -1


def _stream_with_gallery_dl_session(session, media_url, target_path, http_headers, progress_callback=None):
    """
    Streams media_url to target_path using a gallery-dl extractor session with HTTP Range
    resumability, byte-level progress callbacks, and HTML/JSON/manifest payload rejection.
    Returns (success, status_code, error_message, retry_after_seconds).
    """
    os.makedirs(os.path.dirname(target_path), exist_ok=True)
    part_path = target_path + ".downloading"
    existing_bytes = os.path.getsize(part_path) if os.path.exists(part_path) else 0

    req_headers = dict(http_headers or {})
    if existing_bytes > 0:
        req_headers["Range"] = f"bytes={existing_bytes}-"

    try:
        resp = session.get(media_url, headers=req_headers, stream=True, timeout=35)
        if resp.status_code == 416 and existing_bytes > 0:
            content_range = resp.headers.get("Content-Range") or resp.headers.get("content-range") or ""
            total_len = None
            if "/" in content_range:
                try:
                    total_len = int(content_range.rsplit("/", 1)[-1].strip())
                except Exception:
                    total_len = None
            resp.close()
            if total_len and total_len > 0 and existing_bytes == total_len and os.path.exists(part_path):
                valid, v_err = _validate_media_file_payload(part_path)
                if valid:
                    os.replace(part_path, target_path)
                    _notify_progress(progress_callback, total_len, total_len, 0)
                    return True, 200, None, None
            if os.path.exists(part_path):
                os.remove(part_path)
            existing_bytes = 0
            req_headers.pop("Range", None)
            resp = session.get(media_url, headers=req_headers, stream=True, timeout=35)

        if resp.status_code not in (200, 206):
            code = int(resp.status_code)
            retry_after = None
            ra_raw = resp.headers.get("Retry-After")
            if ra_raw:
                try:
                    retry_after = max(1, min(300, int(str(ra_raw).strip())))
                except Exception:
                    retry_after = None
            resp.close()
            return False, code, f"Server returned HTTP status {code}", retry_after

        content_type = (resp.headers.get("content-type") or "").lower()
        lower_url = media_url.lower()
        is_hls_manifest = ".m3u8" in lower_url
        if (
            "text/html" in content_type
            or "application/json" in content_type
            or ("text/plain" in content_type and not is_hls_manifest and "playlist" not in lower_url)
        ):
            resp.close()
            if os.path.exists(part_path):
                os.remove(part_path)
            return False, int(resp.status_code), f"Server returned {content_type} webpage or error response instead of media content", None

        is_partial = (resp.status_code == 206 and existing_bytes > 0)
        if not is_partial and existing_bytes > 0:
            existing_bytes = 0

        content_length = 0
        try:
            content_length = int(resp.headers.get("content-length") or 0)
        except Exception:
            content_length = 0

        if is_partial:
            cr = resp.headers.get("Content-Range") or ""
            range_total = None
            if "/" in cr:
                try:
                    range_total = int(cr.rsplit("/", 1)[-1].strip())
                except Exception:
                    range_total = None
            total_bytes = range_total if (range_total and range_total > 0) else ((existing_bytes + content_length) if content_length > 0 else -1)
        else:
            total_bytes = content_length if content_length > 0 else -1

        downloaded = existing_bytes
        last_emit_time = time.time()
        last_emit_bytes = downloaded
        mode_str = "ab" if is_partial else "wb"

        with open(part_path, mode_str) as f:
            for chunk in resp.iter_content(chunk_size=65536):
                if chunk:
                    f.write(chunk)
                    downloaded += len(chunk)
                    now = time.time()
                    if now - last_emit_time >= 0.25:
                        dt = now - last_emit_time
                        speed = int((downloaded - last_emit_bytes) / dt) if dt > 0 else 0
                        last_emit_time = now
                        last_emit_bytes = downloaded
                        _notify_progress(progress_callback, downloaded, total_bytes, speed)

        resp.close()

        if total_bytes > 0 and os.path.exists(part_path) and os.path.getsize(part_path) != total_bytes:
            actual = os.path.getsize(part_path)
            return False, 200, f"Download interrupted or size mismatch ({actual} of {total_bytes} bytes)", None

        valid, v_err = _validate_media_file_payload(part_path)
        if not valid:
            if os.path.exists(part_path):
                os.remove(part_path)
            return False, 200, v_err, None

        os.replace(part_path, target_path)
        final_size = os.path.getsize(target_path)
        _notify_progress(progress_callback, final_size, total_bytes if total_bytes > 0 else final_size, 0)
        return True, 200, None, None
    except Exception as e:
        err_str = f"{e.__class__.__name__}: {e}"
        return False, _extract_http_status_from_error(err_str), err_str, None


def _download_with_gallery_dl(media_url, target_path, http_headers, cookies=None, progress_callback=None):
    """
    Resolves and downloads media through gallery-dl (supporting 280+ site extractors,
    directlink extractor, gallery-dl session cookies/headers, and DownloadJob staging fallback).
    Returns (success, status_code, error_message, retry_after_seconds).
    """
    if not GALLERY_DL_AVAILABLE:
        return False, -1, "gallery-dl is not available", None

    ua = http_headers.get("User-Agent") or "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    candidates = []
    gdl_session = None
    has_extractor = False

    try:
        with _gdl_lock:
            gdl_config.set(("extractor",), "user-agent", ua)
            gdl_config.set(("extractor",), "headers", dict(http_headers))
            gdl_config.set(("output",), "mode", "null")
            if cookies:
                cookie_dict = _parse_cookie_dict(cookies)
                if cookie_dict:
                    gdl_config.set(("extractor",), "cookies", cookie_dict)

            ext = gdl_extractor.find(media_url)
            if ext is not None:
                has_extractor = True
                gdl_session = getattr(ext, "session", None)
                cat = getattr(ext, "category", "")
                if cat == "directlink":
                    # Direct media link matched by gallery-dl DirectlinkExtractor: resolve immediately with ext.session
                    try:
                        ext.initialize()
                    except Exception:
                        pass
                    gdl_session = getattr(ext, "session", None) or gdl_session
                    candidates.append((media_url, {}))
                else:
                    # Site extractor: resolve canonical media URL + site-specific referer/headers via DataJob
                    try:
                        data_job = gdl_job.DataJob(ext, file=None, resolve=True)
                        data_job.run()
                        gdl_session = getattr(ext, "session", None) or gdl_session
                        for entry in getattr(data_job, "data", []):
                            if not isinstance(entry, (list, tuple)) or len(entry) < 2:
                                continue
                            resolved_u = None
                            kwdict = {}
                            if len(entry) >= 3 and isinstance(entry[1], str) and (entry[1].startswith("http://") or entry[1].startswith("https://")):
                                resolved_u = entry[1]
                                if isinstance(entry[2], dict):
                                    kwdict = entry[2]
                            elif isinstance(entry[1], dict):
                                kwdict = entry[1]
                                resolved_u = kwdict.get("url") or kwdict.get("file_url")
                            if resolved_u:
                                item_hdrs = kwdict.get("_http_headers") if isinstance(kwdict.get("_http_headers"), dict) else {}
                                candidates.append((resolved_u, item_hdrs))
                    except Exception:
                        pass
            if not candidates:
                candidates.append((media_url, {}))
    except Exception as e:
        return False, _extract_http_status_from_error(str(e)), f"gallery-dl init failed: {e}", None

    if gdl_session is None:
        # Use gallery-dl's HTTP requests session configured with our headers/cookies
        import requests
        gdl_session = requests.Session()
        gdl_session.headers.update(http_headers)
        if cookies:
            gdl_session.cookies.update(_parse_cookie_dict(cookies))

    last_code = -1
    last_err = "gallery-dl download failed"
    last_retry = None

    for cand_url, extra_item_hdrs in candidates:
        merged_hdrs = dict(http_headers)
        if extra_item_hdrs:
            merged_hdrs.update({str(k): str(v) for k, v in extra_item_hdrs.items()})
        ok, code, err, retry_after = _stream_with_gallery_dl_session(
            gdl_session,
            cand_url,
            target_path,
            merged_hdrs,
            progress_callback=progress_callback,
        )
        if ok:
            return True, 200, None, None
        last_code, last_err, last_retry = code, (err or last_err), retry_after

    # Fallback inside gallery-dl: DownloadJob into an isolated staging directory for special extractors (e.g., ugoira)
    if has_extractor:
        staging_dir = target_path + ".gdl_stage"
        try:
            if os.path.exists(staging_dir):
                shutil.rmtree(staging_dir, ignore_errors=True)
            os.makedirs(staging_dir, exist_ok=True)
            with _gdl_lock:
                gdl_config.set(("extractor",), "base-directory", staging_dir)
                gdl_config.set(("extractor",), "directory", [])
                gdl_config.set(("extractor",), "skip", False)
                gdl_config.set(("extractor",), "user-agent", ua)
                gdl_config.set(("extractor",), "headers", dict(http_headers))
                gdl_config.set(("downloader",), "retries", 3)
                gdl_config.set(("downloader",), "timeout", 30.0)
                gdl_config.set(("downloader",), "part", True)
                gdl_config.set(("output",), "mode", "null")
                if cookies:
                    cookie_dict = _parse_cookie_dict(cookies)
                    if cookie_dict:
                        gdl_config.set(("extractor",), "cookies", cookie_dict)
                dl_job = gdl_job.DownloadJob(media_url)
                dl_job.run()

            downloaded_files = []
            for root_dir, _, files in os.walk(staging_dir):
                for fname in files:
                    if not fname.endswith(".part"):
                        fpath = os.path.join(root_dir, fname)
                        if os.path.getsize(fpath) > 0:
                            downloaded_files.append(fpath)
            if downloaded_files:
                best_file = max(downloaded_files, key=lambda p: os.path.getsize(p))
                valid, v_err = _validate_media_file_payload(best_file)
                if valid:
                    os.makedirs(os.path.dirname(target_path), exist_ok=True)
                    shutil.move(best_file, target_path)
                    size = os.path.getsize(target_path)
                    _notify_progress(progress_callback, size, size, 0)
                    return True, 200, None, None
                last_err = v_err or last_err
        except Exception as e:
            err_str = f"{e.__class__.__name__}: {e}"
            code = _extract_http_status_from_error(err_str)
            if code != -1:
                last_code = code
            last_err = err_str
        finally:
            shutil.rmtree(staging_dir, ignore_errors=True)

    return False, last_code, last_err, last_retry


def _download_with_ytdlp_and_mux(media_url, target_path, http_headers, referer=None, progress_callback=None, max_height=0, audio_only=False):
    """
    Downloads a video/audio/stream with yt-dlp, ensuring audio is always preserved:
    1. Probes available formats for media_url (or referer if media_url is a raw v.redd.it DASH video).
    2. If split DASH video + audio tracks are needed, downloads video_part and audio_part
       and muxes them with AndroidMediaMuxer (or leaves the parts for GalleryDlEngine to mux).
    3. Otherwise downloads a verified pre-muxed format or best available file.
    Returns (success, status_code, error_message, retry_after_seconds).
    """
    if not YTDLP_AVAILABLE:
        return False, -1, "yt-dlp is not available", None

    os.makedirs(os.path.dirname(target_path), exist_ok=True)
    probe_url = referer if ("v.redd.it/" in media_url.lower() and referer and ("reddit.com" in referer.lower() or "redd.it" in referer.lower())) else media_url

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
    try:
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
            valid, v_err = _validate_media_file_payload(target_path)
            if valid:
                return True, 200, None, None
            if os.path.exists(target_path):
                os.remove(target_path)
            return False, -1, v_err or "yt-dlp audio-only download produced invalid file", None

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
                        return True, 200, None, None
                    # If running outside Android JVM or if Kotlin side will mux, keep both part files so GalleryDlEngine can mux them
                    if os.path.exists(video_part_path) and os.path.exists(audio_part_path):
                        return True, 200, None, None
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
            fmt_spec = "b[vcodec!=none][acodec!=none]/best[vcodec!=none][acodec!=none]/best"
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

        valid, v_err = _validate_media_file_payload(target_path)
        if valid:
            return True, 200, None, None
        if os.path.exists(target_path):
            os.remove(target_path)
        return False, -1, v_err or "yt-dlp produced empty or invalid file", None
    except Exception as e:
        err_str = f"{e.__class__.__name__}: {e}"
        if os.path.exists(target_path):
            try:
                os.remove(target_path)
            except Exception:
                pass
        return False, _extract_http_status_from_error(err_str), err_str, None


def download_media_file_detailed(media_url, target_path, referer=None, user_agent=None, cookies=None, headers_json=None, progress_callback=None):
    """
    Routes EVERY media download exclusively through yt-dlp and gallery-dl (with zero OkHttp direct download).
    Returns a JSON summary with success, file_path, bytes_downloaded, status_code, error, retry_after_seconds, and engine.
    """
    extra_headers = {}
    max_height = 0
    audio_only = False
    source_engine = ""
    is_video_hint = False
    is_audio_hint = False
    is_stream_hint = False

    if headers_json:
        try:
            parsed = json.loads(headers_json)
            if isinstance(parsed, dict):
                max_height = int(parsed.pop("__max_height", 0) or 0)
                audio_only = bool(parsed.pop("__audio_only", False))
                source_engine = str(parsed.pop("__source_engine", "") or "")
                is_video_hint = bool(parsed.pop("__is_video", False))
                is_audio_hint = bool(parsed.pop("__is_audio", False))
                is_stream_hint = bool(parsed.pop("__is_stream", False))
                extra_headers = {str(k): str(v) for k, v in parsed.items() if not str(k).startswith("__")}
        except Exception:
            pass

    ua = user_agent or "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    http_headers = {"User-Agent": ua}
    if referer:
        http_headers["Referer"] = referer
    if cookies:
        http_headers["Cookie"] = cookies
    http_headers.update(extra_headers)

    lower_url = media_url.lower()
    clean_path = lower_url.split("?")[0].split("#")[0]
    is_stream = is_stream_hint or (".m3u8" in lower_url) or (".mpd" in lower_url)
    is_social_host = any(h in lower_url for h in SOCIAL_VIDEO_HOSTS)
    is_image_ext = any(clean_path.endswith(ext) for ext in [".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".bmp", ".svg", ".ico"])
    is_av_ext = any(clean_path.endswith(ext) for ext in [".mp4", ".m4v", ".webm", ".mkv", ".mov", ".avi", ".flv", ".3gp", ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac"])
    prefer_ytdlp = (
        is_stream
        or is_social_host
        or audio_only
        or ("yt-dlp" in source_engine.lower())
        or ((is_video_hint or is_audio_hint or is_av_ext) and not is_image_ext)
    )

    last_code = -1
    last_err = "Download failed"
    last_retry = None
    engine_used = ""

    def _result_json(ok, code, err, retry_after, eng):
        size = os.path.getsize(target_path) if (ok and os.path.exists(target_path)) else 0
        return json.dumps({
            "success": bool(ok),
            "file_path": target_path if ok else None,
            "bytes_downloaded": int(size),
            "status_code": int(code if code is not None else -1),
            "error": err,
            "retry_after_seconds": retry_after,
            "engine": eng
        })

    if prefer_ytdlp:
        if YTDLP_AVAILABLE:
            ok, code, err, retry_after = _download_with_ytdlp_and_mux(
                media_url, target_path, http_headers, referer=referer,
                progress_callback=progress_callback, max_height=max_height, audio_only=audio_only
            )
            if ok:
                return _result_json(True, 200, None, None, "yt-dlp")
            last_code, last_err, last_retry = code, (err or last_err), retry_after

        if GALLERY_DL_AVAILABLE and not is_stream:
            ok, code, err, retry_after = _download_with_gallery_dl(
                media_url, target_path, http_headers, cookies=cookies, progress_callback=progress_callback
            )
            if ok:
                return _result_json(True, 200, None, None, "gallery-dl")
            if code != -1:
                last_code = code
            if err:
                last_err = err
            if retry_after is not None:
                last_retry = retry_after
    else:
        if GALLERY_DL_AVAILABLE:
            ok, code, err, retry_after = _download_with_gallery_dl(
                media_url, target_path, http_headers, cookies=cookies, progress_callback=progress_callback
            )
            if ok:
                return _result_json(True, 200, None, None, "gallery-dl")
            last_code, last_err, last_retry = code, (err or last_err), retry_after

        if YTDLP_AVAILABLE and not is_image_ext:
            ok, code, err, retry_after = _download_with_ytdlp_and_mux(
                media_url, target_path, http_headers, referer=referer,
                progress_callback=progress_callback, max_height=max_height, audio_only=audio_only
            )
            if ok:
                return _result_json(True, 200, None, None, "yt-dlp")
            if code != -1:
                last_code = code
            if err:
                last_err = err
            if retry_after is not None:
                last_retry = retry_after

    return _result_json(False, last_code, last_err, last_retry, engine_used)


def download_media_file(media_url, target_path, referer=None, user_agent=None, cookies=None, headers_json=None, progress_callback=None):
    """
    Boolean wrapper around download_media_file_detailed for backwards-compatible callers.
    All downloads run exclusively through yt-dlp and gallery-dl.
    """
    try:
        res_str = download_media_file_detailed(
            media_url=media_url,
            target_path=target_path,
            referer=referer,
            user_agent=user_agent,
            cookies=cookies,
            headers_json=headers_json,
            progress_callback=progress_callback
        )
        res = json.loads(res_str)
        return bool(res.get("success", False))
    except Exception:
        return False
