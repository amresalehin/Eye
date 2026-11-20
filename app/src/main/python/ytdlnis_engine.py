"""
YTDLnis-inspired yt-dlp orchestration engine for Emre Gallery (Chaquopy Python 3.10).

Provides:
- Rich single-item and playlist/channel metadata + format/chapter/subtitle extraction
- Bulk URL / playlist / search-query (.txt) parsing
- Filename template rendering with live preview & subfolder modes
- Audio, Video (with multi-audio track selection), and Custom Command yt-dlp execution
- SponsorBlock, cut ranges, chapter splitting, metadata/thumbnail/subtitle embedding options
- Cookies (Netscape format) and YouTube PO token injection
- Synced & plain lyrics search via lrclib.net + LRC timestamp offset shifting
- Safe in-process yt-dlp terminal runner (no forbidden Android subprocess fork)
"""

import errno
import os
import sys
import types


def install_android_subproc_guard():
    """
    Neutralizes _posixsubprocess.fork_exec, os.fork, and subprocess.Popen in pure Python
    so Chaquopy running inside Android ART never invokes native _posixsubprocess.cpython-311.so
    (which triggers `E/libsigchain: Setting SIGSEGV to SIG_DFL`) while still allowing
    `import subprocess`, `import yt_dlp`, and `import gallery_dl` to succeed cleanly.
    """
    def _disabled_fork_exec(*args, **kwargs):
        raise FileNotFoundError(errno.ENOENT, "External subprocess fork_exec is disabled on Android ART")

    def _disabled_fork(*args, **kwargs):
        raise OSError(errno.ENOSYS, "os.fork is disabled on Android ART")

    for fn_name in ("fork", "forkpty", "posix_spawn", "posix_spawnp", "system", "popen"):
        if hasattr(os, fn_name):
            try:
                setattr(os, fn_name, _disabled_fork)
            except Exception:
                pass

    existing_ps = sys.modules.get("_posixsubprocess")
    if existing_ps is not None:
        try:
            existing_ps.fork_exec = _disabled_fork_exec
        except Exception:
            pass

    stub_ps = types.ModuleType("_posixsubprocess")
    stub_ps.fork_exec = _disabled_fork_exec
    sys.modules["_posixsubprocess"] = stub_ps

    try:
        import subprocess
        subprocess._fork_exec = _disabled_fork_exec
        subprocess._posixsubprocess = stub_ps

        def _disabled_popen_init(self, *args, **kwargs):
            self.stdin = None
            self.stdout = None
            self.stderr = None
            self.pid = None
            self.returncode = 127
            self._closed_child_pipe_fds = True
            self._child_created = False
            _disabled_fork_exec()

        if hasattr(subprocess, "Popen"):
            subprocess.Popen.__init__ = _disabled_popen_init
            subprocess.Popen._execute_child = lambda self, *a, **kw: _disabled_fork_exec()

        subprocess.run = lambda *a, **kw: _disabled_fork_exec()
        subprocess.call = lambda *a, **kw: 1
        subprocess.check_call = lambda *a, **kw: _disabled_fork_exec()
        subprocess.check_output = lambda *a, **kw: _disabled_fork_exec()
    except Exception:
        pass


install_android_subproc_guard()

import io
import json
import re
import shlex
import tempfile
import traceback
import urllib.parse
import urllib.request
from contextlib import redirect_stdout, redirect_stderr

try:
    import yt_dlp
    YTDLP_AVAILABLE = True
    try:
        from yt_dlp.postprocessor.ffmpeg import FFmpegPostProcessor
        FFmpegPostProcessor._determine_executables = lambda self: None
        FFmpegPostProcessor._versions = {}
    except Exception:
        pass
except Exception:
    yt_dlp = None
    YTDLP_AVAILABLE = False

YTDLNIS_ENGINE_VERSION = "1.0.0-emre"

SPONSORBLOCK_CATEGORIES = [
    "sponsor",
    "intro",
    "outro",
    "selfpromo",
    "preview",
    "filler",
    "interaction",
    "music_offtopic",
]


def get_version():
    return YTDLNIS_ENGINE_VERSION


def is_available():
    return YTDLP_AVAILABLE


def get_status():
    return json.dumps({
        "available": YTDLP_AVAILABLE,
        "engine_version": YTDLNIS_ENGINE_VERSION,
        "yt_dlp_version": getattr(yt_dlp, "__version__", "unavailable") if YTDLP_AVAILABLE else "unavailable",
        "python_version": sys.version.split()[0],
        "sponsorblock_categories": SPONSORBLOCK_CATEGORIES,
    })


def sanitize_filename(name, max_len=160):
    if not name:
        return "untitled"
    cleaned = re.sub(r'[\\/:*?"<>|\r\n\t]+', "_", str(name))
    cleaned = re.sub(r"\s+", " ", cleaned).strip(" ._-")
    if not cleaned:
        return "untitled"
    return cleaned[:max_len]


def parse_timestamp_to_seconds(ts):
    """Parses HH:MM:SS(.ms), MM:SS(.ms), or seconds string into float seconds."""
    if ts is None:
        return None
    raw = str(ts).strip()
    if not raw:
        return None
    if re.fullmatch(r"\d+(\.\d+)?", raw):
        return float(raw)
    parts = raw.split(":")
    if len(parts) == 2:
        try:
            return int(parts[0]) * 60.0 + float(parts[1])
        except ValueError:
            return None
    if len(parts) == 3:
        try:
            return int(parts[0]) * 3600.0 + int(parts[1]) * 60.0 + float(parts[2])
        except ValueError:
            return None
    return None


def format_seconds_to_timestamp(seconds):
    if seconds is None or seconds < 0:
        return "00:00"
    total = int(round(float(seconds)))
    h = total // 3600
    m = (total % 3600) // 60
    s = total % 60
    if h > 0:
        return f"{h:02d}:{m:02d}:{s:02d}"
    return f"{m:02d}:{s:02d}"


def parse_bulk_inputs(raw_text):
    """
    Parses multi-line text or .txt file contents containing URLs, playlists,
    or plain search queries into normalized download targets.
    """
    if not raw_text:
        return json.dumps({"items": [], "count": 0})
    items = []
    seen = set()
    url_pattern = re.compile(r"https?://[^\s<>\"']+", re.IGNORECASE)
    for raw_line in str(raw_text).splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or line.startswith("//"):
            continue
        urls = url_pattern.findall(line)
        if urls:
            for u in urls:
                cleaned = u.rstrip(".,;)]}")
                if cleaned not in seen:
                    seen.add(cleaned)
                    items.append({"input": cleaned, "kind": "url", "query": cleaned})
        else:
            query = line if line.startswith("ytsearch") else f"ytsearch1:{line}"
            if query not in seen:
                seen.add(query)
                items.append({"input": line, "kind": "search", "query": query})
    return json.dumps({"items": items, "count": len(items)})


def render_filename_template(template, metadata_json="{}"):
    """
    Renders a YTDLnis / yt-dlp filename template with live preview values.
    Supports %(title)s, %(uploader)s, %(author)s, %(id)s, %(ext)s,
    %(upload_date)s, %(playlist_title)s, %(playlist_index)s, %(resolution)s, %(extractor)s.
    """
    try:
        meta = json.loads(metadata_json) if isinstance(metadata_json, str) else (metadata_json or {})
    except Exception:
        meta = {}

    tmpl = (template or "%(title)s.%(ext)s").strip() or "%(title)s.%(ext)s"
    defaults = {
        "title": meta.get("title") or "Sample Video Title",
        "uploader": meta.get("uploader") or meta.get("author") or "Creator",
        "author": meta.get("author") or meta.get("uploader") or "Creator",
        "channel": meta.get("channel") or meta.get("uploader") or "Channel",
        "id": meta.get("id") or "dQw4w9WgXcQ",
        "ext": meta.get("ext") or "mp4",
        "upload_date": meta.get("upload_date") or "20261119",
        "playlist_title": meta.get("playlist_title") or "Playlist",
        "playlist_index": str(meta.get("playlist_index") or "01"),
        "resolution": meta.get("resolution") or "1080p",
        "extractor": meta.get("extractor") or "youtube",
    }

    def _replace(match):
        key = match.group(1)
        val = defaults.get(key, "")
        return sanitize_filename(val) if key != "ext" else re.sub(r"[^a-zA-Z0-9]", "", str(val)) or "mp4"

    rendered = re.sub(r"%\(([a-zA-Z0-9_]+)\)s", _replace, tmpl)
    # Also support {token} shorthand used elsewhere in Emre Gallery
    for k, v in defaults.items():
        rendered = rendered.replace("{" + k + "}", sanitize_filename(v) if k != "ext" else str(v))
    rendered = sanitize_filename(rendered, max_len=180)
    return rendered


_YOUTUBE_AUTH_COOKIE_KEYS = (
    # SID is a Google account session cookie. Netscape exports often contain it
    # without SAPISID, so treating it as anonymous incorrectly discards valid sessions.
    "SID",
    "SAPISID",
    "__Secure-3PSID",
    "__Secure-1PSID",
    "LOGIN_INFO",
    "SSID",
    "HSID",
)


def is_youtube_url(url):
    if not url:
        return False
    raw = str(url).strip().lower()
    if raw.startswith("ytsearch"):
        return True
    return any(
        h in raw
        for h in (
            "youtube.com/",
            "youtu.be/",
            "youtube-nocookie.com/",
            "music.youtube.com/",
        )
    )


def extract_youtube_video_id(url):
    if not url:
        return None
    raw = str(url).strip()
    patterns = (
        r"(?:v=|/v/|youtu\.be/|/embed/|/shorts/|/live/)([A-Za-z0-9_-]{11})",
        r"^([A-Za-z0-9_-]{11})$",
    )
    for pat in patterns:
        m = re.search(pat, raw)
        if m:
            return m.group(1)
    return None


def normalize_youtube_url(url, allow_playlist=True):
    if not url:
        return url
    raw = str(url).strip()
    if not is_youtube_url(raw) or raw.startswith("ytsearch"):
        return raw
    try:
        parsed = urllib.parse.urlparse(raw)
        host = (parsed.hostname or "").lower()
        query_params = urllib.parse.parse_qs(parsed.query)
        vid = extract_youtube_video_id(raw)
        list_id = (query_params.get("list") or [None])[0]
        if vid:
            if allow_playlist and list_id and ("/playlist" in parsed.path or "list=" in raw):
                return f"https://www.youtube.com/watch?v={vid}&list={list_id}"
            return f"https://www.youtube.com/watch?v={vid}"
        if host in ("m.youtube.com", "music.youtube.com"):
            parsed = parsed._replace(netloc="www.youtube.com")
            return urllib.parse.urlunparse(parsed)
    except Exception:
        pass
    return raw


def has_authenticated_youtube_cookies(cookies_text):
    """
    Returns True ONLY if the cookie string contains genuine YouTube login session cookies.
    Anonymous guest cookies (VISITOR_INFO1_LIVE, YSC, PREF, GPS) must NEVER be passed to yt-dlp
    for YouTube because yt-dlp disables android_vr/ios/android clients whenever a cookiefile is set.
    """
    if not cookies_text or not str(cookies_text).strip():
        return False
    raw = str(cookies_text)
    for key in _YOUTUBE_AUTH_COOKIE_KEYS:
        if re.search(rf"(?:^|[;\s\t]){re.escape(key)}(?:=|\t)", raw):
            return True
    return False


def _write_temp_cookiefile(cookies_text, target_url=None):
    if not cookies_text or not str(cookies_text).strip():
        return None
    if target_url and is_youtube_url(target_url) and not has_authenticated_youtube_cookies(cookies_text):
        return None
    text = str(cookies_text).strip()
    fd, path = tempfile.mkstemp(prefix="ytdlnis_cookies_", suffix=".txt")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        if not text.startswith("# Netscape HTTP Cookie File") and "\t" in text:
            f.write("# Netscape HTTP Cookie File\n")
        f.write(text)
        if not text.endswith("\n"):
            f.write("\n")
    return path


def _extract_youtube_innertube_or_piped_fallback(video_id, original_url=""):
    """
    Multi-tier direct YouTube stream resolver used when yt-dlp's bundled extractor hits
    datacenter BotGuard ('Sign in to confirm you are not a bot').
    Tier A: Direct InnerTube player API with ANDROID_VR, IOS, and ANDROID_TESTSUITE clients.
    Tier B: Public Piped & Invidious instances returning direct googlevideo/HLS streams.
    """
    if not video_id:
        return None

    canonical_url = original_url or f"https://www.youtube.com/watch?v={video_id}"

    # Tier A: Direct InnerTube player requests with non-web native client contexts
    innertube_clients = [
        {
            "name": "ANDROID_VR",
            "ua": "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            "client_id": "28",
            "version": "1.60.19",
            "context": {
                "client": {
                    "clientName": "ANDROID_VR",
                    "clientVersion": "1.60.19",
                    "deviceMake": "Oculus",
                    "deviceModel": "Quest 3",
                    "osName": "Android",
                    "osVersion": "12L",
                    "androidSdkVersion": 32,
                    "hl": "en",
                    "gl": "US",
                }
            },
        },
        {
            "name": "IOS",
            "ua": "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)",
            "client_id": "5",
            "version": "19.45.4",
            "context": {
                "client": {
                    "clientName": "IOS",
                    "clientVersion": "19.45.4",
                    "deviceMake": "Apple",
                    "deviceModel": "iPhone16,2",
                    "osName": "iOS",
                    "osVersion": "18.1.0.22B83",
                    "hl": "en",
                    "gl": "US",
                }
            },
        },
        {
            "name": "ANDROID_TESTSUITE",
            "ua": "com.google.android.youtube/1.9 (Linux; U; Android 11) gzip",
            "client_id": "30",
            "version": "1.9",
            "context": {
                "client": {
                    "clientName": "ANDROID_TESTSUITE",
                    "clientVersion": "1.9",
                    "androidSdkVersion": 30,
                    "osName": "Android",
                    "osVersion": "11",
                    "hl": "en",
                    "gl": "US",
                }
            },
        },
    ]

    for client_cfg in innertube_clients:
        try:
            payload = json.dumps({
                "videoId": video_id,
                "context": client_cfg["context"],
                "contentCheckOk": True,
                "racyCheckOk": True,
            }).encode("utf-8")
            req = urllib.request.Request(
                "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                data=payload,
                headers={
                    "Content-Type": "application/json",
                    "User-Agent": client_cfg["ua"],
                    "X-YouTube-Client-Name": client_cfg["client_id"],
                    "X-YouTube-Client-Version": client_cfg["version"],
                    "Origin": "https://www.youtube.com",
                },
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=8) as resp:
                data = json.loads(resp.read().decode("utf-8", errors="replace"))

            streaming = data.get("streamingData") or {}
            raw_fmts = (streaming.get("formats") or []) + (streaming.get("adaptiveFormats") or [])
            hls_url = streaming.get("hlsManifestUrl") or ""
            parsed_formats = []

            for f in raw_fmts:
                f_url = f.get("url")
                if not f_url:
                    continue
                mime_raw = str(f.get("mimeType") or "")
                mime_base = mime_raw.split(";")[0].strip()
                ext = "mp4"
                if "/" in mime_base:
                    ext = mime_base.split("/")[1].strip()
                    if ext == "mp4" and mime_base.startswith("audio/"):
                        ext = "m4a"
                codecs_part = ""
                if 'codecs="' in mime_raw:
                    codecs_part = mime_raw.split('codecs="', 1)[1].split('"', 1)[0]
                codecs = [c.strip() for c in codecs_part.split(",") if c.strip()]
                is_video_mime = mime_base.startswith("video/")
                is_audio_mime = mime_base.startswith("audio/")
                vcodec = codecs[0] if (is_video_mime and codecs) else ("h264" if is_video_mime else "none")
                acodec = (
                    codecs[1]
                    if (is_video_mime and len(codecs) > 1)
                    else (codecs[0] if (is_audio_mime and codecs) else ("aac" if is_audio_mime else "none"))
                )
                height = int(f.get("height") or 0)
                width = int(f.get("width") or 0)
                clen = int(f.get("contentLength") or 0)
                bitrate = float(f.get("bitrate") or 0) / 1000.0
                parsed_formats.append({
                    "format_id": str(f.get("itag") or f"itag_{len(parsed_formats)}"),
                    "url": f_url,
                    "ext": ext,
                    "vcodec": vcodec,
                    "acodec": acodec,
                    "height": height,
                    "width": width,
                    "fps": float(f.get("fps") or 0),
                    "abr": bitrate if is_audio_mime else 0.0,
                    "tbr": bitrate,
                    "filesize": clen,
                    "resolution": f"{width}x{height}" if width and height else (f"{height}p" if height else "audio only"),
                    "format_note": str(f.get("qualityLabel") or ("audio" if is_audio_mime else "")),
                })

            if hls_url and not parsed_formats:
                parsed_formats.append({
                    "format_id": "hls",
                    "url": hls_url,
                    "ext": "mp4",
                    "vcodec": "h264",
                    "acodec": "aac",
                    "height": 720,
                    "width": 1280,
                    "fps": 30.0,
                    "abr": 128.0,
                    "tbr": 2000.0,
                    "filesize": 0,
                    "resolution": "1280x720",
                    "format_note": "HLS Stream",
                })

            if parsed_formats:
                vd = data.get("videoDetails") or {}
                thumbs = ((vd.get("thumbnail") or {}).get("thumbnails") or [])
                best_thumb = thumbs[-1].get("url") if thumbs else f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg"
                muxed = [x for x in parsed_formats if x.get("vcodec") != "none" and x.get("acodec") != "none"]
                best_direct = (muxed[-1] if muxed else parsed_formats[0])
                return {
                    "id": video_id,
                    "url": best_direct.get("url") or canonical_url,
                    "webpage_url": canonical_url,
                    "title": vd.get("title") or f"YouTube Video {video_id}",
                    "uploader": vd.get("author") or "YouTube",
                    "duration": int(vd.get("lengthSeconds") or 0),
                    "thumbnail": best_thumb,
                    "description": vd.get("shortDescription") or "",
                    "extractor": f"youtube:{client_cfg['name'].lower()}",
                    "ext": best_direct.get("ext") or "mp4",
                    "vcodec": best_direct.get("vcodec") or "h264",
                    "acodec": best_direct.get("acodec") or "aac",
                    "width": best_direct.get("width") or 0,
                    "height": best_direct.get("height") or 0,
                    "formats": parsed_formats,
                }
        except Exception:
            continue

    # Tier B: Piped & Invidious API fallback for datacenter-blocked IPs
    piped_hosts = (
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://pipedapi.drgns.space",
        "https://api.piped.private.coffee",
    )
    for host in piped_hosts:
        try:
            req = urllib.request.Request(
                f"{host}/streams/{video_id}",
                headers={"User-Agent": "Mozilla/5.0 (Android 14; Mobile)"},
            )
            with urllib.request.urlopen(req, timeout=7) as resp:
                data = json.loads(resp.read().decode("utf-8", errors="replace"))
            parsed_formats = []
            for vs in (data.get("videoStreams") or []):
                v_url = vs.get("url")
                if not v_url:
                    continue
                video_only = bool(vs.get("videoOnly", False))
                height = int(vs.get("height") or 0)
                width = int(vs.get("width") or 0)
                fmt_str = str(vs.get("format") or "MPEG_4").lower()
                ext = "webm" if "webm" in fmt_str else "mp4"
                parsed_formats.append({
                    "format_id": str(vs.get("itag") or f"v_{height}p_{ext}"),
                    "url": v_url,
                    "ext": ext,
                    "vcodec": str(vs.get("codec") or "h264"),
                    "acodec": "none" if video_only else "aac",
                    "height": height,
                    "width": width,
                    "fps": float(vs.get("fps") or 30),
                    "abr": 0.0,
                    "tbr": float(vs.get("bitrate") or 0) / 1000.0,
                    "filesize": int(vs.get("contentLength") or 0),
                    "resolution": f"{width}x{height}" if width and height else f"{height}p",
                    "format_note": str(vs.get("quality") or ""),
                })
            for aus in (data.get("audioStreams") or []):
                a_url = aus.get("url")
                if not a_url:
                    continue
                fmt_str = str(aus.get("format") or "M4A").lower()
                ext = "webm" if "webm" in fmt_str else "m4a"
                abr = float(aus.get("bitrate") or 0) / 1000.0
                parsed_formats.append({
                    "format_id": str(aus.get("itag") or f"a_{int(abr)}k_{ext}"),
                    "url": a_url,
                    "ext": ext,
                    "vcodec": "none",
                    "acodec": str(aus.get("codec") or "aac"),
                    "height": 0,
                    "width": 0,
                    "fps": 0.0,
                    "abr": abr,
                    "tbr": abr,
                    "filesize": int(aus.get("contentLength") or 0),
                    "resolution": "audio only",
                    "format_note": str(aus.get("quality") or "audio"),
                })
            hls_url = data.get("hls")
            if hls_url and not parsed_formats:
                parsed_formats.append({
                    "format_id": "hls",
                    "url": hls_url,
                    "ext": "mp4",
                    "vcodec": "h264",
                    "acodec": "aac",
                    "height": 720,
                    "width": 1280,
                    "fps": 30.0,
                    "abr": 128.0,
                    "tbr": 2000.0,
                    "filesize": 0,
                    "resolution": "1280x720",
                    "format_note": "HLS",
                })
            if parsed_formats:
                muxed = [x for x in parsed_formats if x.get("vcodec") != "none" and x.get("acodec") != "none"]
                best_direct = muxed[-1] if muxed else parsed_formats[0]
                return {
                    "id": video_id,
                    "url": best_direct.get("url") or canonical_url,
                    "webpage_url": canonical_url,
                    "title": data.get("title") or f"YouTube Video {video_id}",
                    "uploader": data.get("uploader") or "YouTube",
                    "duration": int(data.get("duration") or 0),
                    "thumbnail": data.get("thumbnailUrl") or f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg",
                    "description": data.get("description") or "",
                    "extractor": "youtube:piped",
                    "ext": best_direct.get("ext") or "mp4",
                    "vcodec": best_direct.get("vcodec") or "h264",
                    "acodec": best_direct.get("acodec") or "aac",
                    "width": best_direct.get("width") or 0,
                    "height": best_direct.get("height") or 0,
                    "formats": parsed_formats,
                }
        except Exception:
            continue

    invidious_hosts = (
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://yewtu.be",
    )
    for host in invidious_hosts:
        try:
            req = urllib.request.Request(
                f"{host}/api/v1/videos/{video_id}",
                headers={"User-Agent": "Mozilla/5.0 (Android 14; Mobile)"},
            )
            with urllib.request.urlopen(req, timeout=7) as resp:
                data = json.loads(resp.read().decode("utf-8", errors="replace"))
            parsed_formats = []
            for fs in (data.get("formatStreams") or []):
                f_url = fs.get("url")
                if not f_url:
                    continue
                res_str = str(fs.get("resolution") or "360p").replace("p", "")
                height = int(res_str) if res_str.isdigit() else 360
                parsed_formats.append({
                    "format_id": str(fs.get("itag") or f"muxed_{height}p"),
                    "url": f_url,
                    "ext": str(fs.get("container") or "mp4"),
                    "vcodec": "h264",
                    "acodec": "aac",
                    "height": height,
                    "width": int(height * 16 / 9),
                    "fps": float(fs.get("fps") or 30),
                    "abr": 128.0,
                    "tbr": 1500.0,
                    "filesize": 0,
                    "resolution": f"{height}p",
                    "format_note": str(fs.get("qualityLabel") or f"{height}p"),
                })
            for af in (data.get("adaptiveFormats") or []):
                f_url = af.get("url")
                if not f_url:
                    continue
                t_str = str(af.get("type") or "")
                is_aud = t_str.startswith("audio/")
                res_str = str(af.get("resolution") or "0").replace("p", "")
                height = int(res_str) if res_str.isdigit() else 0
                parsed_formats.append({
                    "format_id": str(af.get("itag") or f"adapt_{len(parsed_formats)}"),
                    "url": f_url,
                    "ext": "m4a" if is_aud else str(af.get("container") or "mp4"),
                    "vcodec": "none" if is_aud else str(af.get("encoding") or "h264"),
                    "acodec": str(af.get("encoding") or "aac") if is_aud else "none",
                    "height": height,
                    "width": int(height * 16 / 9) if height else 0,
                    "fps": float(af.get("fps") or 0),
                    "abr": float(af.get("bitrate") or 0) / 1000.0 if is_aud else 0.0,
                    "tbr": float(af.get("bitrate") or 0) / 1000.0,
                    "filesize": int(af.get("clen") or 0),
                    "resolution": "audio only" if is_aud else f"{height}p",
                    "format_note": str(af.get("qualityLabel") or ("audio" if is_aud else "")),
                })
            if parsed_formats:
                muxed = [x for x in parsed_formats if x.get("vcodec") != "none" and x.get("acodec") != "none"]
                best_direct = muxed[-1] if muxed else parsed_formats[0]
                return {
                    "id": video_id,
                    "url": best_direct.get("url") or canonical_url,
                    "webpage_url": canonical_url,
                    "title": data.get("title") or f"YouTube Video {video_id}",
                    "uploader": data.get("author") or "YouTube",
                    "duration": int(data.get("lengthSeconds") or 0),
                    "thumbnail": f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg",
                    "description": data.get("description") or "",
                    "extractor": "youtube:invidious",
                    "ext": best_direct.get("ext") or "mp4",
                    "vcodec": best_direct.get("vcodec") or "h264",
                    "acodec": best_direct.get("acodec") or "aac",
                    "width": best_direct.get("width") or 0,
                    "height": best_direct.get("height") or 0,
                    "formats": parsed_formats,
                }
        except Exception:
            continue

    return None


def extract_youtube_info_resilient(target_url, base_ydl_opts, cookie_path=None, po_token=None):
    """
    Executes yt-dlp against a YouTube URL with automatic InnerTube client rotation,
    guest-cookie isolation, and Direct InnerTube / Piped / Invidious fallback.
    """
    profiles = []
    if cookie_path or po_token:
        yt_args = {"player_client": ["tv", "web", "mweb", "web_creator"]}
        if po_token:
            yt_args["po_token"] = [f"web+{po_token}"]
        profiles.append({"use_cookies": bool(cookie_path), "youtube_args": yt_args})

    # Cookie-less native mobile/VR/TV profiles (never pass guest cookies so android_vr/ios/android stay enabled)
    profiles.append({
        "use_cookies": False,
        "youtube_args": {
            "player_client": ["android_vr", "tv", "web_embedded", "ios", "android"],
            "player_skip": ["webpage", "configs"],
        },
    })
    profiles.append({
        "use_cookies": False,
        "youtube_args": {
            "player_client": ["ios", "android", "tv_embedded"],
            "player_skip": ["webpage"],
        },
    })

    last_err = None
    for prof in profiles:
        try_opts = dict(base_ydl_opts)
        # Never pass custom browser User-Agent / Sec-Ch-Ua headers to YouTube InnerTube clients
        try_opts.pop("http_headers", None)
        if prof["use_cookies"] and cookie_path:
            try_opts["cookiefile"] = cookie_path
        else:
            try_opts.pop("cookiefile", None)

        ext_args = dict(try_opts.get("extractor_args") or {})
        ext_args["youtube"] = prof["youtube_args"]
        try_opts["extractor_args"] = ext_args

        try:
            with yt_dlp.YoutubeDL(try_opts) as ydl:
                info = ydl.extract_info(target_url, download=False)
            if info:
                # Verify that either playlist entries or playable formats/url were returned
                if info.get("_type") == "playlist" or info.get("entries") or info.get("formats") or info.get("url"):
                    return info, None
        except Exception as exc:
            last_err = exc
            continue

    # Layer 3 fallback: Direct InnerTube (ANDROID_VR / IOS / ANDROID_TESTSUITE) + Piped + Invidious
    vid = extract_youtube_video_id(target_url)
    if vid:
        fallback_info = _extract_youtube_innertube_or_piped_fallback(vid, target_url)
        if fallback_info:
            return fallback_info, None

    return None, last_err


def _normalize_format(fmt):
    vcodec = fmt.get("vcodec") or "none"
    acodec = fmt.get("acodec") or "none"
    has_video = vcodec != "none"
    has_audio = acodec != "none"
    height = fmt.get("height") or 0
    width = fmt.get("width") or 0
    fps = fmt.get("fps") or 0
    abr = fmt.get("abr") or 0
    tbr = fmt.get("tbr") or 0
    filesize = fmt.get("filesize") or fmt.get("filesize_approx") or 0
    resolution = fmt.get("resolution") or (f"{width}x{height}" if width and height else (f"{height}p" if height else "audio only"))
    return {
        "format_id": str(fmt.get("format_id") or ""),
        "ext": str(fmt.get("ext") or ""),
        "vcodec": str(vcodec),
        "acodec": str(acodec),
        "has_video": has_video,
        "has_audio": has_audio,
        "height": int(height) if isinstance(height, (int, float)) else 0,
        "width": int(width) if isinstance(width, (int, float)) else 0,
        "fps": float(fps) if isinstance(fps, (int, float)) else 0.0,
        "abr": float(abr) if isinstance(abr, (int, float)) else 0.0,
        "tbr": float(tbr) if isinstance(tbr, (int, float)) else 0.0,
        "filesize": int(filesize) if isinstance(filesize, (int, float)) else 0,
        "resolution": str(resolution),
        "format_note": str(fmt.get("format_note") or ""),
        "language": str(fmt.get("language") or ""),
    }


def _normalize_entry(info, fallback_url=""):
    formats_raw = info.get("formats") or []
    formats = [_normalize_format(f) for f in formats_raw if f.get("format_id")]
    chapters_raw = info.get("chapters") or []
    chapters = [
        {
            "title": str(c.get("title") or f"Chapter {idx + 1}"),
            "start_time": float(c.get("start_time") or 0.0),
            "end_time": float(c.get("end_time") or 0.0),
        }
        for idx, c in enumerate(chapters_raw)
    ]
    subtitles = sorted(list((info.get("subtitles") or {}).keys()))
    auto_captions = sorted(list((info.get("automatic_captions") or {}).keys()))
    return {
        "id": str(info.get("id") or ""),
        "url": str(info.get("webpage_url") or info.get("original_url") or info.get("url") or fallback_url),
        "title": str(info.get("title") or "Untitled"),
        "author": str(info.get("uploader") or info.get("channel") or info.get("creator") or ""),
        "duration": int(info.get("duration") or 0),
        "duration_string": str(info.get("duration_string") or format_seconds_to_timestamp(info.get("duration") or 0)),
        "thumbnail": str(info.get("thumbnail") or ""),
        "description": str(info.get("description") or "")[:1000],
        "upload_date": str(info.get("upload_date") or ""),
        "extractor": str(info.get("extractor_key") or info.get("extractor") or "generic"),
        "playlist_title": str(info.get("playlist_title") or ""),
        "playlist_index": int(info.get("playlist_index") or 0),
        "formats": formats,
        "chapters": chapters,
        "subtitles": subtitles,
        "auto_captions": auto_captions,
    }


def fetch_video_info(url_or_query, options_json="{}"):
    """
    Fetches rich video/audio/playlist metadata using yt-dlp without downloading.
    Supports playlists, single URLs, and ytsearch queries.
    """
    if not YTDLP_AVAILABLE:
        return json.dumps({"success": False, "error": "yt-dlp is not available in Python runtime"})

    try:
        opts = json.loads(options_json) if options_json else {}
    except Exception:
        opts = {}

    target = str(url_or_query).strip()
    if not re.match(r"^https?://", target, re.IGNORECASE) and not target.startswith("ytsearch"):
        if re.match(r"^[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}(/.*)?$", target):
            target = f"https://{target}"
        else:
            target = f"ytsearch5:{target}"

    allow_playlist = bool(opts.get("allow_playlist", True))
    is_yt = is_youtube_url(target)
    if is_yt:
        target = normalize_youtube_url(target, allow_playlist=allow_playlist)

    cookie_path = _write_temp_cookiefile(opts.get("cookies"), target_url=target)
    try:
        ydl_opts = {
            "quiet": True,
            "no_warnings": True,
            "skip_download": True,
            "ignoreerrors": not is_yt,
            "ffmpeg_location": None,
            "prefer_ffmpeg": False,
            "extract_flat": "in_playlist" if opts.get("flat_playlist", True) else False,
            "socket_timeout": int(opts.get("timeout", 30)),
            "retries": int(opts.get("retries", 3)),
        }
        if not allow_playlist:
            ydl_opts["noplaylist"] = True
        if cookie_path:
            ydl_opts["cookiefile"] = cookie_path
        if opts.get("proxy"):
            ydl_opts["proxy"] = opts["proxy"]
        po_token = str(opts.get("po_token") or "").strip() or None
        if po_token:
            ydl_opts["extractor_args"] = {
                "youtube": {
                    "po_token": [f"web+{po_token}"]
                }
            }

        if is_yt:
            info, yt_err = extract_youtube_info_resilient(
                target_url=target,
                base_ydl_opts=ydl_opts,
                cookie_path=cookie_path,
                po_token=po_token,
            )
            if not info and yt_err is not None:
                raise yt_err
        else:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(target, download=False)

        if not info:
            return json.dumps({"success": False, "error": "No media info returned for query"})

        if info.get("_type") == "playlist" or "entries" in info:
            raw_entries = [e for e in (info.get("entries") or []) if e]
            entries = [_normalize_entry(e, fallback_url=target) for e in raw_entries]
            return json.dumps({
                "success": True,
                "is_playlist": True,
                "playlist_title": str(info.get("title") or "Playlist"),
                "playlist_uploader": str(info.get("uploader") or ""),
                "entries": entries,
            })
        else:
            entry = _normalize_entry(info, fallback_url=target)
            return json.dumps({
                "success": True,
                "is_playlist": False,
                "playlist_title": "",
                "playlist_uploader": "",
                "entries": [entry],
            })
    except Exception as exc:
        return json.dumps({
            "success": False,
            "error": str(exc),
            "trace": traceback.format_exc()[-1000:],
        })
    finally:
        if cookie_path and os.path.exists(cookie_path):
            try:
                os.remove(cookie_path)
            except OSError:
                pass


def build_ytdlp_options(config_dict, output_dir):
    """
    Constructs a yt-dlp YoutubeDL option dictionary from a YTDLnis download config.
    Separated as a pure helper so it is thoroughly unit-testable.
    """
    download_type = str(config_dict.get("type") or "video").lower()
    format_id = str(config_dict.get("format_id") or "").strip()
    audio_format_ids = [str(x).strip() for x in (config_dict.get("audio_format_ids") or []) if str(x).strip()]
    video_only = bool(config_dict.get("video_only", False))
    max_h = int(config_dict.get("max_height") or 0)
    container = str(config_dict.get("container") or "").strip().lower()
    filename_template = str(config_dict.get("filename_template") or "%(title)s.%(ext)s").strip()
    if "%(ext)s" not in filename_template:
        filename_template = f"{filename_template}.%(ext)s"

    outtmpl = os.path.join(output_dir, filename_template)
    ydl_opts = {
        "outtmpl": outtmpl,
        "noplaylist": not bool(config_dict.get("allow_playlist", False)),
        "socket_timeout": int(config_dict.get("timeout", 35)),
        "retries": min(int(config_dict.get("retries", 3)), 2),
        "fragment_retries": min(int(config_dict.get("retries", 3)), 3),
        "continuedl": True,
        "geo_bypass": True,
    }

    # Format selection matching YTDLnis Audio / Video / Multi-audio modes
    if download_type == "audio":
        if format_id and format_id != "best":
            ydl_opts["format"] = format_id
        else:
            ydl_opts["format"] = "bestaudio/best"
    else:
        if format_id and format_id != "best":
            if audio_format_ids:
                joined_audio = "+".join(audio_format_ids)
                ydl_opts["format"] = f"{format_id}+{joined_audio}/best"
                if len(audio_format_ids) > 1:
                    ydl_opts["allow_multiple_audio_streams"] = True
            else:
                ydl_opts["format"] = f"{format_id}+bestaudio/{format_id}/best"
        elif audio_format_ids:
            joined_audio = "+".join(audio_format_ids)
            ydl_opts["format"] = f"bestvideo+{joined_audio}/best"
            if len(audio_format_ids) > 1:
                ydl_opts["allow_multiple_audio_streams"] = True
        elif video_only:
            ydl_opts["format"] = f"bestvideo[height<={max_h}]/bestvideo/best" if max_h > 0 else "bestvideo/best"
        else:
            if max_h > 0:
                ydl_opts["format"] = f"bestvideo[height<={max_h}]+bestaudio/best[height<={max_h}]/best"
            else:
                ydl_opts["format"] = "bestvideo+bestaudio/best"

    if container and download_type == "video" and container in ("mp4", "mkv", "webm"):
        ydl_opts["merge_output_format"] = container

    # Subtitles
    if config_dict.get("download_subtitles") or config_dict.get("embed_subtitles") or config_dict.get("burn_subtitles"):
        ydl_opts["writesubtitles"] = True
        if config_dict.get("include_auto_subtitles", True):
            ydl_opts["writeautomaticsub"] = True
        langs = config_dict.get("subtitle_languages") or ["en"]
        ydl_opts["subtitleslangs"] = [str(l).strip() for l in langs if str(l).strip()] or ["en"]
        ydl_opts["subtitlesformat"] = "srt/vtt/best"

    # Thumbnail
    if config_dict.get("embed_thumbnail") or config_dict.get("save_thumbnail"):
        ydl_opts["writethumbnail"] = True

    # Proxy & YouTube PO Token
    if config_dict.get("proxy"):
        ydl_opts["proxy"] = str(config_dict["proxy"]).strip()
    if config_dict.get("po_token"):
        ydl_opts["extractor_args"] = {
            "youtube": {
                "po_token": [f"web+{str(config_dict['po_token']).strip()}"]
            }
        }

    # SponsorBlock API metadata fetch (actual cut/chapter marking is performed cleanly via FFmpegKit on Android)
    sb_categories = [c for c in (config_dict.get("sponsorblock_categories") or []) if c in SPONSORBLOCK_CATEGORIES]
    sb_action = str(config_dict.get("sponsorblock_action") or "none").lower()
    if sb_categories and sb_action in ("remove", "mark_chapter"):
        ydl_opts["sponsorblock_categories"] = sb_categories
        ydl_opts["sponsorblock_action"] = sb_action

    # Cut sections metadata (stored for FFmpegKit post-processing on Android)
    cut_sections = config_dict.get("cut_sections") or []
    if cut_sections:
        ydl_opts["cut_sections"] = cut_sections

    return ydl_opts



def prepare_android_format_selection(ydl_opts, format_id, audio_format_ids, video_only=False, max_height=0):
    """
    Converts a YTDLnis format choice into an Android-safe video-only yt-dlp
    selection plus companion audio IDs for in-process FFmpegKit/MediaMuxer.
    A combined ID such as "137+140" must never reach yt-dlp here: yt-dlp
    would invoke its external FFmpegMergerPP, which cannot spawn on Android ART.
    """
    selected_video_id = str(format_id or "").strip()
    selected_audio_ids = [
        str(value).strip()
        for value in (audio_format_ids or [])
        if str(value).strip()
    ]

    if selected_video_id and "+" in selected_video_id:
        combined_parts = [
            part.strip()
            for part in selected_video_id.split("+")
            if part.strip()
        ]
        if combined_parts:
            selected_video_id = combined_parts[0]
            # Explicit audio choices from the audio-track picker take precedence.
            if not selected_audio_ids:
                selected_audio_ids = combined_parts[1:]

    if video_only:
        selected_audio_ids = []

    height_cap = max(0, int(max_height or 0))
    if selected_video_id and selected_video_id.lower() != "best":
        ydl_opts["format"] = f"{selected_video_id}/bestvideo/best"
    elif height_cap > 0:
        ydl_opts["format"] = f"bestvideo[height<={height_cap}]/bestvideo/best"
    else:
        ydl_opts["format"] = "bestvideo/best"

    if "+" in str(ydl_opts.get("format") or ""):
        raise ValueError("Android yt-dlp video selector must not contain a merge operator")
    return selected_audio_ids


def _download_direct_http_stream(stream_url, dest_path, timeout=35):
    req = urllib.request.Request(
        stream_url,
        headers={
            "User-Agent": "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            "Accept": "*/*",
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp, open(dest_path, "wb") as out_f:
        while True:
            chunk = resp.read(65536)
            if not chunk:
                break
            out_f.write(chunk)
    return os.path.exists(dest_path) and os.path.getsize(dest_path) > 0


def _download_youtube_fallback_streams(url, output_dir, download_type, format_id, max_h, logs, video_only=False):
    vid = extract_youtube_video_id(url)
    if not vid:
        return None, "", []
    info = _extract_youtube_innertube_or_piped_fallback(vid, url)
    if not info:
        return None, "", []
    fmts = info.get("formats") or []
    if not fmts:
        return None, "", []

    safe_title = sanitize_filename(info.get("title") or f"youtube_{vid}")
    merge_audio = []

    if download_type == "audio":
        audio_candidates = [f for f in fmts if f.get("url") and f.get("vcodec") == "none" and f.get("acodec") != "none"]
        if not audio_candidates:
            audio_candidates = [f for f in fmts if f.get("url") and f.get("acodec") != "none"]
        chosen = None
        if format_id and format_id != "best":
            chosen = next((f for f in audio_candidates if str(f.get("format_id")) == format_id), None)
        if not chosen and audio_candidates:
            chosen = sorted(audio_candidates, key=lambda x: float(x.get("abr") or x.get("tbr") or 0))[-1]
        if not chosen:
            return None, "", []
        ext = chosen.get("ext") or "m4a"
        dest = os.path.join(output_dir, f"{safe_title}.{ext}")
        if _download_direct_http_stream(chosen["url"], dest):
            logs.append(f"Downloaded YouTube audio via {info.get('extractor')} fallback ({chosen.get('format_id')})")
            info["acodec"] = chosen.get("acodec") or "aac"
            info["vcodec"] = "none"
            return info, dest, []
        return None, "", []

    # Video mode
    chosen_video = None
    if format_id and format_id != "best":
        chosen_video = next((f for f in fmts if str(f.get("format_id")) == format_id and f.get("url")), None)

    if not chosen_video:
        video_candidates = [f for f in fmts if f.get("url") and f.get("vcodec") != "none"]
        if max_h > 0:
            capped = [f for f in video_candidates if int(f.get("height") or 0) <= max_h]
            if capped:
                video_candidates = capped
        # Prefer the highest resolution representation, not the best muxed
        # representation; muxing audio is handled in-process by the Android layer.
        if video_candidates:
            chosen_video = max(
                video_candidates,
                key=lambda x: (
                    int(x.get("height") or 0),
                    int(x.get("width") or 0),
                    float(x.get("tbr") or 0),
                    int(x.get("filesize") or x.get("filesize_approx") or 0),
                ),
            )

    if not chosen_video:
        return None, "", []

    v_ext = chosen_video.get("ext") or "mp4"
    primary_dest = os.path.join(output_dir, f"{safe_title}.{v_ext}")
    if not _download_direct_http_stream(chosen_video["url"], primary_dest):
        return None, "", []

    logs.append(f"Downloaded YouTube video via {info.get('extractor')} fallback ({chosen_video.get('format_id')})")
    info["acodec"] = chosen_video.get("acodec") or "none"
    info["vcodec"] = chosen_video.get("vcodec") or "h264"

    if info["acodec"] == "none" and not video_only:
        audio_candidates = [f for f in fmts if f.get("url") and f.get("vcodec") == "none" and f.get("acodec") != "none"]
        if audio_candidates:
            best_a = sorted(audio_candidates, key=lambda x: float(x.get("abr") or x.get("tbr") or 0))[-1]
            a_ext = best_a.get("ext") or "m4a"
            a_dest = os.path.join(output_dir, f"__companion_audio_0_{vid}.{a_ext}")
            try:
                if _download_direct_http_stream(best_a["url"], a_dest):
                    merge_audio.append(a_dest)
                    logs.append(f"Downloaded companion audio ({best_a.get('format_id')})")
            except Exception as a_err:
                logs.append(f"WARNING: Companion audio fallback failed: {a_err}")

    return info, primary_dest, merge_audio


def download_item(config_json, output_dir):
    """
    Downloads a media item via yt-dlp in-process (Chaquopy) and returns the downloaded
    file path(s), companion audio track path(s) for in-process FfmpegKitEngine muxing,
    subtitle paths, thumbnail path, chapters, and execution log.
    """
    if not YTDLP_AVAILABLE:
        return json.dumps({"success": False, "error": "yt-dlp is not available"})

    try:
        config = json.loads(config_json) if isinstance(config_json, str) else (config_json or {})
    except Exception as exc:
        return json.dumps({"success": False, "error": f"Invalid config JSON: {exc}"})

    os.makedirs(output_dir, exist_ok=True)
    url = str(config.get("url") or "").strip()
    if not url:
        return json.dumps({"success": False, "error": "URL is required"})
    if not re.match(r"^https?://", url, re.IGNORECASE) and not url.startswith("ytsearch"):
        if re.match(r"^[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}(/.*)?$", url):
            url = f"https://{url}"

    allow_playlist = bool(config.get("allow_playlist", False))
    is_yt = is_youtube_url(url)
    if is_yt:
        url = normalize_youtube_url(url, allow_playlist=allow_playlist)

    download_type = str(config.get("type") or "video").lower()
    if download_type == "command" and config.get("custom_command"):
        return run_terminal_command(f"{config['custom_command']} {shlex.quote(url)}", output_dir)

    cookie_path = _write_temp_cookiefile(config.get("cookies"), target_url=url)
    logs = []
    downloaded_files = []
    merge_audio_files = []

    class _ListLogger:
        def debug(self, msg):
            if msg:
                logs.append(str(msg))
        def warning(self, msg):
            if msg:
                logs.append(f"WARNING: {msg}")
        def error(self, msg):
            if msg:
                logs.append(f"ERROR: {msg}")

    def _hook(d):
        if d.get("status") == "finished":
            fn = d.get("filename")
            if fn and fn not in downloaded_files:
                downloaded_files.append(fn)

    try:
        ydl_opts = build_ytdlp_options(config, output_dir)
        # Strip non-yt-dlp metadata helper keys & CLI ffmpeg merge flags before passing to YoutubeDL
        ydl_opts.pop("sponsorblock_categories", None)
        ydl_opts.pop("sponsorblock_action", None)
        ydl_opts.pop("cut_sections", None)
        ydl_opts.pop("merge_output_format", None)
        ydl_opts.pop("allow_multiple_audio_streams", None)
        ydl_opts["ffmpeg_location"] = None
        ydl_opts["prefer_ffmpeg"] = False

        format_id = str(config.get("format_id") or "").strip()
        audio_format_ids = [str(x).strip() for x in (config.get("audio_format_ids") or []) if str(x).strip()]
        max_h = int(config.get("max_height") or 0)
        video_only = bool(config.get("video_only", False))

        # Keep the yt-dlp pass video-only on Android. Companion audio is fetched
        # separately and muxed in-process by FfmpegKitEngine/AndroidMediaMuxer.
        # Split combined UI format IDs (for example "137+140") before yt-dlp
        # can invoke its unavailable external FFmpegMergerPP.
        if download_type != "audio":
            audio_format_ids = prepare_android_format_selection(
                ydl_opts=ydl_opts,
                format_id=format_id,
                audio_format_ids=audio_format_ids,
                video_only=video_only,
                max_height=max_h,
            )
            logs.append(
                "[Android format selection] video=%s audio=%s"
                % (ydl_opts.get("format"), ",".join(audio_format_ids) or ("none (video-only)" if video_only else "auto"))
            )

        if cookie_path:
            ydl_opts["cookiefile"] = cookie_path
        ydl_opts["logger"] = _ListLogger()
        ydl_opts["progress_hooks"] = [_hook]

        info = None
        prepared = ""
        primary_file = ""

        if is_yt:
            po_token = str(config.get("po_token") or "").strip() or None
            yt_profiles = []
            if cookie_path or po_token:
                yt_args = {"player_client": ["tv", "web", "mweb", "web_creator"]}
                if po_token:
                    yt_args["po_token"] = [f"web+{po_token}"]
                yt_profiles.append({"use_cookies": bool(cookie_path), "youtube_args": yt_args})
            yt_profiles.append({
                "use_cookies": False,
                "youtube_args": {
                    "player_client": ["android_vr", "tv", "web_embedded", "ios", "android"],
                    "player_skip": ["webpage", "configs"],
                },
            })
            yt_profiles.append({
                "use_cookies": False,
                "youtube_args": {
                    "player_client": ["ios", "android", "tv_embedded"],
                    "player_skip": ["webpage"],
                },
            })

            last_yt_err = None
            for prof in yt_profiles:
                try_opts = dict(ydl_opts)
                try_opts.pop("http_headers", None)
                if prof["use_cookies"] and cookie_path:
                    try_opts["cookiefile"] = cookie_path
                else:
                    try_opts.pop("cookiefile", None)
                ext_args = dict(try_opts.get("extractor_args") or {})
                ext_args["youtube"] = prof["youtube_args"]
                try_opts["extractor_args"] = ext_args
                try:
                    with yt_dlp.YoutubeDL(try_opts) as ydl:
                        info = ydl.extract_info(url, download=True)
                        prepared = ydl.prepare_filename(info) if info else ""
                    for candidate in downloaded_files:
                        if os.path.exists(candidate):
                            primary_file = candidate
                            break
                    if not primary_file and prepared and os.path.exists(prepared):
                        primary_file = prepared
                    if primary_file:
                        ydl_opts["extractor_args"] = ext_args
                        break
                except Exception as yt_exc:
                    last_yt_err = yt_exc
                    continue

            if not primary_file:
                fb_info, fb_file, fb_audios = _download_youtube_fallback_streams(
                    url, output_dir, download_type, format_id, max_h, logs, video_only=video_only
                )
                if fb_file:
                    info = fb_info
                    primary_file = fb_file
                    merge_audio_files.extend(fb_audios)
                elif last_yt_err is not None:
                    raise last_yt_err
        else:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(url, download=True)
                prepared = ydl.prepare_filename(info) if info else ""

            for candidate in downloaded_files:
                if os.path.exists(candidate):
                    primary_file = candidate
                    break
            if not primary_file and prepared and os.path.exists(prepared):
                primary_file = prepared

        # If a video-only stream was downloaded or explicit audio track IDs were requested,
        # download companion audio stream(s) separately for in-process FfmpegKitEngine muxing.
        info_acodec = (info.get("acodec") or "") if isinstance(info, dict) else ""
        needs_companion_audio = (
            download_type != "audio"
            and not video_only
            and bool(primary_file)
            and not merge_audio_files
            and (info_acodec == "none" or bool(audio_format_ids))
        )
        if needs_companion_audio:
            audio_selectors = audio_format_ids if audio_format_ids else ["bestaudio[ext=m4a]/bestaudio"]
            for idx, a_sel in enumerate(audio_selectors):
                audio_files_this_pass = []

                def _audio_hook(d, bucket=audio_files_this_pass):
                    if d.get("status") == "finished":
                        fn = d.get("filename")
                        if fn and fn not in bucket:
                            bucket.append(fn)

                a_opts = {
                    "outtmpl": os.path.join(output_dir, f"__companion_audio_{idx}_%(id)s.%(ext)s"),
                    "format": f"{a_sel}/bestaudio",
                    "noplaylist": True,
                    "quiet": True,
                    "no_warnings": True,
                    "ffmpeg_location": None,
                    "prefer_ffmpeg": False,
                    "socket_timeout": ydl_opts.get("socket_timeout", 35),
                    "retries": ydl_opts.get("retries", 2),
                    "continuedl": True,
                    "geo_bypass": True,
                    "logger": _ListLogger(),
                    "progress_hooks": [_audio_hook],
                }
                if cookie_path:
                    a_opts["cookiefile"] = cookie_path
                if ydl_opts.get("proxy"):
                    a_opts["proxy"] = ydl_opts["proxy"]
                if ydl_opts.get("extractor_args"):
                    a_opts["extractor_args"] = ydl_opts["extractor_args"]
                try:
                    with yt_dlp.YoutubeDL(a_opts) as a_ydl:
                        a_info = a_ydl.extract_info(url, download=True)
                        a_prep = a_ydl.prepare_filename(a_info) if a_info else ""
                    found_a = ""
                    for cand in audio_files_this_pass:
                        if os.path.exists(cand) and cand != primary_file:
                            found_a = cand
                            break
                    if not found_a and a_prep and os.path.exists(a_prep) and a_prep != primary_file:
                        found_a = a_prep
                    if found_a and found_a not in merge_audio_files:
                        merge_audio_files.append(found_a)
                except Exception as a_exc:
                    logs.append(f"WARNING: Companion audio download ({a_sel}) failed: {a_exc}")

        # Discover companion subtitle and thumbnail files in output_dir
        subtitle_files = []
        thumbnail_file = ""
        for entry_name in os.listdir(output_dir):
            full = os.path.join(output_dir, entry_name)
            low = entry_name.lower()
            if low.endswith((".srt", ".vtt", ".ass")):
                subtitle_files.append(full)
            elif low.endswith((".jpg", ".jpeg", ".png", ".webp")):
                thumbnail_file = full

        chapters = []
        if info and info.get("chapters"):
            chapters = [
                {
                    "title": str(c.get("title") or f"Chapter {i + 1}"),
                    "start_time": float(c.get("start_time") or 0.0),
                    "end_time": float(c.get("end_time") or 0.0),
                }
                for i, c in enumerate(info.get("chapters") or [])
            ]

        return json.dumps({
            "success": bool(primary_file),
            "primary_file": primary_file,
            "downloaded_files": downloaded_files,
            "merge_audio_files": merge_audio_files,
            "subtitle_files": subtitle_files,
            "thumbnail_file": thumbnail_file,
            "chapters": chapters,
            "title": str(config.get("title") or (info.get("title") if info else "") or ""),
            "author": str(config.get("author") or (info.get("uploader") if info else "") or ""),
            "logs": "\n".join(logs[-200:]),
            "error": "" if primary_file else "Download finished without producing an output file",
        })
    except Exception as exc:
        logs.append(f"EXCEPTION: {exc}")
        return json.dumps({
            "success": False,
            "primary_file": "",
            "downloaded_files": downloaded_files,
            "merge_audio_files": merge_audio_files,
            "logs": "\n".join(logs[-200:]),
            "error": str(exc),
        })
    finally:
        if cookie_path and os.path.exists(cookie_path):
            try:
                os.remove(cookie_path)
            except OSError:
                pass


def run_terminal_command(command_line, working_dir=None):
    """
    Executes a YTDLnis terminal command safely inside Chaquopy Python.
    Supports `yt-dlp <args>`, `python --version`, `help`, and `clear`.
    """
    raw = str(command_line or "").strip()
    if not raw:
        return json.dumps({"success": True, "output": "", "exit_code": 0})

    try:
        tokens = shlex.split(raw)
    except ValueError as exc:
        return json.dumps({"success": False, "output": f"Syntax error: {exc}", "exit_code": 2})

    if not tokens:
        return json.dumps({"success": True, "output": "", "exit_code": 0})

    cmd = tokens[0].lower()
    args = tokens[1:]

    if cmd in ("help", "?"):
        help_text = (
            "Emre Gallery YTDLnis Terminal\n"
            "Built-in commands:\n"
            "  yt-dlp [OPTIONS] URL   Run yt-dlp directly (e.g. yt-dlp -F <url>, yt-dlp --version)\n"
            "  python --version       Show bundled Python version\n"
            "  status                 Show yt-dlp & YTDLnis engine status\n"
            "  help                   Show this help message\n"
        )
        return json.dumps({"success": True, "output": help_text, "exit_code": 0})

    if cmd == "status":
        return json.dumps({"success": True, "output": get_status(), "exit_code": 0})

    if cmd in ("python", "python3"):
        if args == ["--version"] or args == ["-V"]:
            return json.dumps({"success": True, "output": f"Python {sys.version.split()[0]}", "exit_code": 0})
        return json.dumps({
            "success": False,
            "output": "Only `python --version` is enabled in the sandboxed terminal.",
            "exit_code": 1,
        })

    # Treat bare arguments or `yt-dlp ...` as yt-dlp invocation
    ytdlp_args = args if cmd in ("yt-dlp", "ytdl", "youtube-dl") else tokens
    if not YTDLP_AVAILABLE:
        return json.dumps({"success": False, "output": "yt-dlp is not installed in Python runtime.", "exit_code": 1})

    if ytdlp_args == ["--version"]:
        return json.dumps({"success": True, "output": getattr(yt_dlp, "__version__", "unknown"), "exit_code": 0})

    out_buf = io.StringIO()
    err_buf = io.StringIO()
    prev_cwd = os.getcwd()
    try:
        if working_dir:
            os.makedirs(working_dir, exist_ok=True)
            os.chdir(working_dir)
        with redirect_stdout(out_buf), redirect_stderr(err_buf):
            try:
                yt_dlp.main(ytdlp_args)
                exit_code = 0
            except SystemExit as se:
                exit_code = int(se.code) if isinstance(se.code, int) else (0 if se.code is None else 1)
        combined = (out_buf.getvalue() + "\n" + err_buf.getvalue()).strip()
        return json.dumps({
            "success": exit_code == 0,
            "output": combined or f"yt-dlp finished with exit code {exit_code}",
            "exit_code": exit_code,
        })
    except Exception as exc:
        combined = (out_buf.getvalue() + "\n" + err_buf.getvalue() + f"\nERROR: {exc}").strip()
        return json.dumps({"success": False, "output": combined, "exit_code": 1})
    finally:
        try:
            os.chdir(prev_cwd)
        except OSError:
            pass


def search_lyrics_lrclib(track_name, artist_name="", timeout=15):
    """
    Searches synced (.lrc) and plain lyrics from lrclib.net (matching YTDLnis's lyrics feature).
    """
    q = str(track_name or "").strip()
    if not q:
        return json.dumps({"success": False, "results": [], "error": "Track name is required"})
    params = {"track_name": q}
    if artist_name and str(artist_name).strip():
        params["artist_name"] = str(artist_name).strip()
    url = "https://lrclib.net/api/search?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "EmreGallery-YTDLnis/1.0 (Android)"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
        data = json.loads(raw)
        results = []
        for item in (data or [])[:15]:
            results.append({
                "id": item.get("id", 0),
                "track_name": str(item.get("trackName") or ""),
                "artist_name": str(item.get("artistName") or ""),
                "album_name": str(item.get("albumName") or ""),
                "duration": float(item.get("duration") or 0.0),
                "synced_lyrics": str(item.get("syncedLyrics") or ""),
                "plain_lyrics": str(item.get("plainLyrics") or ""),
            })
        return json.dumps({"success": True, "results": results})
    except Exception as exc:
        return json.dumps({"success": False, "results": [], "error": str(exc)})


def adjust_lrc_timing(lrc_text, offset_ms):
    """
    Shifts every [mm:ss.xx] timestamp in an LRC lyrics string by offset_ms milliseconds.
    Clamps timestamps at 00:00.00 so lyrics never go negative.
    """
    if not lrc_text:
        return ""
    offset_sec = float(offset_ms) / 1000.0
    ts_re = re.compile(r"\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]")

    def _shift(match):
        mins = int(match.group(1))
        secs = int(match.group(2))
        frac_str = match.group(3) or "00"
        frac_sec = int(frac_str) / (1000.0 if len(frac_str) == 3 else (100.0 if len(frac_str) == 2 else 10.0))
        total = max(0.0, mins * 60.0 + secs + frac_sec + offset_sec)
        new_m = int(total // 60)
        rem = total - new_m * 60
        new_s = int(rem)
        new_cs = int(round((rem - new_s) * 100))
        if new_cs >= 100:
            new_s += 1
            new_cs -= 100
        if new_s >= 60:
            new_m += 1
            new_s -= 60
        return f"[{new_m:02d}:{new_s:02d}.{new_cs:02d}]"

    return ts_re.sub(_shift, str(lrc_text))
