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

import io
import json
import os
import re
import shlex
import sys
import tempfile
import traceback
import urllib.parse
import urllib.request
from contextlib import redirect_stdout, redirect_stderr

# Guard against fatal SIGSEGV on Android ART if any library attempts fork()
sys.modules['_posixsubprocess'] = None

try:
    import yt_dlp
    YTDLP_AVAILABLE = True
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


def _write_temp_cookiefile(cookies_text):
    if not cookies_text or not str(cookies_text).strip():
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

    cookie_path = _write_temp_cookiefile(opts.get("cookies"))
    try:
        ydl_opts = {
            "quiet": True,
            "no_warnings": True,
            "skip_download": True,
            "ignoreerrors": True,
            "extract_flat": "in_playlist" if opts.get("flat_playlist", True) else False,
            "socket_timeout": int(opts.get("timeout", 30)),
            "retries": int(opts.get("retries", 3)),
        }
        if not opts.get("allow_playlist", True):
            ydl_opts["noplaylist"] = True
        if cookie_path:
            ydl_opts["cookiefile"] = cookie_path
        if opts.get("proxy"):
            ydl_opts["proxy"] = opts["proxy"]
        if opts.get("po_token"):
            ydl_opts["extractor_args"] = {
                "youtube": {
                    "po_token": [f"web+{opts['po_token']}"]
                }
            }

        target = str(url_or_query).strip()
        if not re.match(r"^https?://", target, re.IGNORECASE) and not target.startswith("ytsearch"):
            target = f"ytsearch5:{target}"

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
        else:
            max_h = int(config_dict.get("max_height") or 0)
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


def download_item(config_json, output_dir):
    """
    Downloads a media item via yt-dlp in-process (Chaquopy) and returns the downloaded
    file path(s), subtitle paths, thumbnail path, chapters, and execution log so Android
    FfmpegKitEngine can apply any post-processing (cut, crop, split chapters, remux,
    compatible re-encode, burn subtitles, embed metadata/lyrics).
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

    download_type = str(config.get("type") or "video").lower()
    if download_type == "command" and config.get("custom_command"):
        return run_terminal_command(f"{config['custom_command']} {shlex.quote(url)}", output_dir)

    cookie_path = _write_temp_cookiefile(config.get("cookies"))
    logs = []
    downloaded_files = []

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
        # Strip non-yt-dlp metadata helper keys before passing to YoutubeDL
        ydl_opts.pop("sponsorblock_categories", None)
        ydl_opts.pop("sponsorblock_action", None)
        ydl_opts.pop("cut_sections", None)

        if cookie_path:
            ydl_opts["cookiefile"] = cookie_path
        ydl_opts["logger"] = _ListLogger()
        ydl_opts["progress_hooks"] = [_hook]

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=True)
            prepared = ydl.prepare_filename(info) if info else ""

        primary_file = ""
        for candidate in downloaded_files:
            if os.path.exists(candidate):
                primary_file = candidate
                break
        if not primary_file and prepared and os.path.exists(prepared):
            primary_file = prepared

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
