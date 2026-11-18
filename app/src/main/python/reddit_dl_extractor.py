"""
Reddit media extractor for Emre Gallery (Chaquopy Python), implementing the core
features of patrickkfkan/reddit-dl (https://github.com/patrickkfkan/reddit-dl).

Supported targets (full URLs and reddit-dl shorthands):
  - Subreddits: r/<subreddit>, reddit.com/r/<subreddit>[/hot|/new|/top|/rising]
  - Users: u/<username>, user/<username>, reddit.com/u/<username>, reddit.com/user/<username>
  - Single posts: p/<post_id>, redd.it/<post_id>, reddit.com/r/<sub>/comments/<id>/...,
    reddit.com/user/<user>/comments/<id>/..., reddit.com/gallery/<id>, reddit.com/r/<sub>/s/<share_id>
  - Account-specific targets (requires OAuth credentials or logged-in Reddit cookies):
    my/saved, my/joined, my/following
  - Direct RedGIFs links: redgifs.com/watch/<id>, v3.redgifs.com/watch/<id>

Extracted content per post:
  - Reddit-hosted images (i.redd.it, unescaped preview.redd.it)
  - Reddit galleries (ordered gallery_data + media_metadata, including static images, GIFs, and MP4s)
  - Reddit-hosted videos (v.redd.it) with DASH MPD / HLS playlist parsing for highest-resolution
    video + companion audio track URL (bypassing 720p cap when browser cookies are available)
  - Crosspost unwrapping (crosspost_parent_list)
  - RedGIFs videos (via RedGIFs v2 temporary token API with fallback to reddit_video_preview)
  - External direct media (Imgur .gifv -> .mp4, direct images/videos, reddit_video_preview)
  - Optional post comments (--comments) and author profile details (--post-authors)
  - Date range filtering (--after inclusive, --before exclusive), item limit (--limit),
    incremental stop on previously seen non-stickied posts (--continue), rate-limit backoff,
    and OAuth2 + browser cookie authentication.
"""

import base64
import datetime
import hashlib
import html as html_lib
import json
import re
import time
import xml.etree.ElementTree as ET
from urllib.parse import parse_qs, unquote, urlencode, urlparse

try:
    import requests
    REQUESTS_AVAILABLE = True
except Exception:
    requests = None
    REQUESTS_AVAILABLE = False

import urllib.error
import urllib.request

REDDIT_DL_VERSION = "1.1.2-py"

DEFAULT_USER_AGENT = (
    "android:com.amresalehin.emregallery:v1.1.2 (reddit-dl compatible)"
)
DEFAULT_BROWSER_UA = (
    "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36"
)

_REDDIT_HOSTS = {
    "reddit.com",
    "www.reddit.com",
    "old.reddit.com",
    "new.reddit.com",
    "m.reddit.com",
    "np.reddit.com",
    "sh.reddit.com",
    "oauth.reddit.com",
    "redd.it",
    "www.redd.it",
    "i.redd.it",
    "v.redd.it",
    "preview.redd.it",
}

_REDGIFS_HOSTS = {
    "redgifs.com",
    "www.redgifs.com",
    "v3.redgifs.com",
    "i.redgifs.com",
    "gifdeliverynetwork.com",
    "www.gifdeliverynetwork.com",
}

_SHORTHAND_SUBREDDIT_RE = re.compile(
    r"^/?r/([A-Za-z0-9_]{2,24})(?:/(hot|new|top|rising|controversial))?/?$",
    re.IGNORECASE,
)
_SHORTHAND_USER_RE = re.compile(
    r"^/?(?:u|user)/([A-Za-z0-9_-]{2,24})(?:/(submitted|posts|overview|saved))?/?$",
    re.IGNORECASE,
)
_SHORTHAND_POST_RE = re.compile(r"^/?p/([A-Za-z0-9]{4,12})/?$", re.IGNORECASE)
_SHORTHAND_MY_RE = re.compile(r"^/?my/(saved|joined|following)/?$", re.IGNORECASE)

_URL_POST_RE = re.compile(
    r"^/(?:r/[^/]+|u/[^/]+|user/[^/]+)?/?comments/([A-Za-z0-9]{4,12})(?:/|$)",
    re.IGNORECASE,
)
_URL_GALLERY_RE = re.compile(r"^/gallery/([A-Za-z0-9]{4,12})(?:/|$)", re.IGNORECASE)
_URL_SHARE_RE = re.compile(r"^/(?:r/[^/]+|u/[^/]+|user/[^/]+)/s/([A-Za-z0-9_-]+)(?:/|$)", re.IGNORECASE)
_URL_SUBREDDIT_RE = re.compile(
    r"^/r/([A-Za-z0-9_]{2,24})(?:/(hot|new|top|rising|controversial))?/?$",
    re.IGNORECASE,
)
_URL_USER_RE = re.compile(
    r"^/(?:u|user)/([A-Za-z0-9_-]{2,24})(?:/(submitted|posts|overview|saved))?/?$",
    re.IGNORECASE,
)
_REDGIFS_ID_RE = re.compile(
    r"/(?:watch|ifr|detail)/([A-Za-z0-9_-]+)",
    re.IGNORECASE,
)

_IMAGE_EXTS = ("jpg", "jpeg", "png", "gif", "webp", "avif", "bmp")
_VIDEO_EXTS = ("mp4", "webm", "mov", "mkv", "m4v")


def is_reddit_dl_available():
    """Returns True because this extractor uses pure Python stdlib/requests."""
    return True


def get_reddit_dl_version():
    """Returns the implemented reddit-dl compatibility version."""
    return REDDIT_DL_VERSION


def is_reddit_shorthand(raw):
    """Returns True if raw matches a patrickkfkan/reddit-dl shorthand target."""
    if not raw or not isinstance(raw, str):
        return False
    s = raw.strip()
    if not s or " " in s or "://" in s:
        return False
    return bool(
        _SHORTHAND_SUBREDDIT_RE.match(s)
        or _SHORTHAND_USER_RE.match(s)
        or _SHORTHAND_POST_RE.match(s)
        or _SHORTHAND_MY_RE.match(s)
    )


def is_redgifs_url(url):
    """Returns True if the URL points to RedGIFs."""
    if not url or not isinstance(url, str):
        return False
    raw = url.strip()
    if "://" not in raw:
        raw = "https://" + raw
    try:
        host = (urlparse(raw).hostname or "").lower()
    except Exception:
        return False
    return host in _REDGIFS_HOSTS or host.endswith(".redgifs.com")


def is_reddit_url(url):
    """Returns True if the URL or shorthand targets Reddit or RedGIFs."""
    if not url or not isinstance(url, str):
        return False
    raw = url.strip()
    if not raw:
        return False
    if is_reddit_shorthand(raw):
        return True
    if "://" not in raw:
        raw = "https://" + raw
    try:
        host = (urlparse(raw).hostname or "").lower()
    except Exception:
        return False
    return (
        host in _REDDIT_HOSTS
        or host.endswith(".reddit.com")
        or host in _REDGIFS_HOSTS
        or host.endswith(".redgifs.com")
    )


def normalize_reddit_target_url(raw):
    """Converts reddit-dl shorthand targets into canonical https://www.reddit.com URLs."""
    if not raw or not isinstance(raw, str):
        return ""
    s = raw.strip()
    if not s:
        return ""

    m = _SHORTHAND_SUBREDDIT_RE.match(s)
    if m:
        sub = m.group(1)
        sort = (m.group(2) or "").lower()
        return f"https://www.reddit.com/r/{sub}/{sort}" if sort else f"https://www.reddit.com/r/{sub}/"

    m = _SHORTHAND_USER_RE.match(s)
    if m:
        user = m.group(1)
        section = (m.group(2) or "submitted").lower()
        if section == "posts":
            section = "submitted"
        return f"https://www.reddit.com/user/{user}/{section}/"

    m = _SHORTHAND_POST_RE.match(s)
    if m:
        return f"https://www.reddit.com/comments/{m.group(1)}/"

    m = _SHORTHAND_MY_RE.match(s)
    if m:
        return f"https://www.reddit.com/my/{m.group(1).lower()}"

    if "://" not in s:
        return "https://" + s
    return s


def parse_reddit_target(raw):
    """
    Parses a Reddit URL or reddit-dl shorthand into a structured target dict:
      - kind: "post" | "share" | "subreddit" | "user" | "my_saved" | "my_joined" | "my_following" | "redgifs" | "direct_media" | "unknown"
    """
    if not raw or not isinstance(raw, str):
        return {"kind": "unknown", "raw": raw}

    s = raw.strip()
    if not s:
        return {"kind": "unknown", "raw": s}

    # Shorthands first
    m = _SHORTHAND_MY_RE.match(s)
    if m:
        subkind = m.group(1).lower()
        return {"kind": f"my_{subkind}", "raw": s, "canonical_url": f"https://www.reddit.com/my/{subkind}"}

    m = _SHORTHAND_POST_RE.match(s)
    if m:
        pid = m.group(1)
        return {"kind": "post", "post_id": pid, "raw": s, "canonical_url": f"https://www.reddit.com/comments/{pid}/"}

    m = _SHORTHAND_SUBREDDIT_RE.match(s)
    if m:
        sub = m.group(1)
        sort = (m.group(2) or "new").lower()
        return {
            "kind": "subreddit",
            "subreddit": sub,
            "sort": sort,
            "time_filter": None,
            "raw": s,
            "canonical_url": f"https://www.reddit.com/r/{sub}/",
        }

    m = _SHORTHAND_USER_RE.match(s)
    if m:
        user = m.group(1)
        section = (m.group(2) or "submitted").lower()
        if section == "posts":
            section = "submitted"
        return {
            "kind": "user",
            "username": user,
            "section": section,
            "sort": "new",
            "time_filter": None,
            "raw": s,
            "canonical_url": f"https://www.reddit.com/user/{user}/{section}/",
        }

    # Full URL parsing
    normalized = s if "://" in s else "https://" + s
    try:
        parsed = urlparse(normalized)
    except Exception:
        return {"kind": "unknown", "raw": s}

    host = (parsed.hostname or "").lower()
    path = parsed.path or "/"
    qs = parse_qs(parsed.query or "")
    time_filter = (qs.get("t") or [None])[0]
    sort_param = (qs.get("sort") or [None])[0]

    if host in _REDGIFS_HOSTS or host.endswith(".redgifs.com"):
        rg_match = _REDGIFS_ID_RE.search(path)
        if rg_match:
            return {"kind": "redgifs", "redgifs_id": rg_match.group(1).lower(), "raw": s, "canonical_url": normalized}
        return {"kind": "unknown", "raw": s}

    if host in ("i.redd.it", "v.redd.it", "preview.redd.it"):
        return {"kind": "direct_media", "url": normalized, "raw": s, "canonical_url": normalized}

    if host in ("redd.it", "www.redd.it"):
        seg = path.strip("/").split("/")[0]
        if seg and re.match(r"^[A-Za-z0-9]{4,12}$", seg):
            return {"kind": "post", "post_id": seg, "raw": s, "canonical_url": f"https://www.reddit.com/comments/{seg}/"}
        return {"kind": "unknown", "raw": s}

    if host not in _REDDIT_HOSTS and not host.endswith(".reddit.com"):
        return {"kind": "unknown", "raw": s}

    # Account-specific canonical URLs (/my/saved, /my/joined, /my/following)
    m = _SHORTHAND_MY_RE.match(path)
    if m:
        subkind = m.group(1).lower()
        return {"kind": f"my_{subkind}", "raw": s, "canonical_url": f"https://www.reddit.com/my/{subkind}"}

    # Mobile share link (/r/sub/s/<share_id>)
    if _URL_SHARE_RE.match(path):
        return {"kind": "share", "url": normalized, "raw": s, "canonical_url": normalized}

    # Single post (/comments/<id> or /gallery/<id>)
    m = _URL_POST_RE.match(path) or _URL_GALLERY_RE.match(path)
    if m:
        pid = m.group(1)
        return {"kind": "post", "post_id": pid, "raw": s, "canonical_url": f"https://www.reddit.com/comments/{pid}/"}

    # Subreddit listing
    m = _URL_SUBREDDIT_RE.match(path)
    if m:
        sub = m.group(1)
        sort = (m.group(2) or sort_param or "hot").lower()
        return {
            "kind": "subreddit",
            "subreddit": sub,
            "sort": sort,
            "time_filter": time_filter,
            "raw": s,
            "canonical_url": f"https://www.reddit.com/r/{sub}/",
        }

    # User listing
    m = _URL_USER_RE.match(path)
    if m:
        user = m.group(1)
        section = (m.group(2) or "submitted").lower()
        if section == "posts":
            section = "submitted"
        return {
            "kind": "user",
            "username": user,
            "section": section,
            "sort": (sort_param or "new").lower(),
            "time_filter": time_filter,
            "raw": s,
            "canonical_url": f"https://www.reddit.com/user/{user}/{section}/",
        }

    return {"kind": "unknown", "raw": s, "canonical_url": normalized}


def parse_date_filter(date_str):
    """
    Parses reddit-dl date/time strings ('yyyy-MM-dd' or 'yyyy-MM-dd HH:mm') into UTC epoch seconds.
    Returns None if empty or invalid.
    """
    if not date_str or not isinstance(date_str, str):
        return None
    val = date_str.strip()
    if not val:
        return None
    for fmt in ("%Y-%m-%d %H:%M", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d"):
        try:
            dt = datetime.datetime.strptime(val, fmt).replace(tzinfo=datetime.timezone.utc)
            return dt.timestamp()
        except ValueError:
            continue
    return None


def _unescape_url(url):
    if not url or not isinstance(url, str):
        return ""
    return html_lib.unescape(url).strip()


def _guess_extension(url, is_video=False, mime_hint=None):
    if mime_hint:
        low_mime = mime_hint.lower()
        if "png" in low_mime:
            return "png"
        if "gif" in low_mime:
            return "gif"
        if "webp" in low_mime:
            return "webp"
        if "mp4" in low_mime:
            return "mp4"
        if "webm" in low_mime:
            return "webm"
        if "jpeg" in low_mime or "jpg" in low_mime:
            return "jpg"
    clean = (url or "").split("?")[0].split("#")[0].lower()
    for ext in (*_VIDEO_EXTS, *_IMAGE_EXTS, "m3u8", "mpd"):
        if clean.endswith("." + ext):
            return "jpg" if ext == "jpeg" else ext
    return "mp4" if is_video else "jpg"


def _sanitize_slug(text, max_len=48):
    if not text:
        return ""
    cleaned = re.sub(r"[^A-Za-z0-9._-]+", "_", str(text)).strip("_")
    return cleaned[:max_len]


def _parse_dash_mpd(mpd_xml, base_url):
    """
    Parses a Reddit v.redd.it DASHPlaylist.mpd XML document to find:
      - Highest-resolution video Representation URL (width, height, bandwidth)
      - Highest-bitrate audio Representation URL
    """
    result = {
        "video_url": None,
        "audio_url": None,
        "width": None,
        "height": None,
    }
    if not mpd_xml or not base_url:
        return result

    base_dir = base_url.split("?")[0].rsplit("/", 1)[0]
    try:
        root = ET.fromstring(mpd_xml)
    except Exception:
        return result

    best_video = None
    best_video_score = (-1, -1)
    best_audio = None
    best_audio_bw = -1

    for elem in root.iter():
        tag = elem.tag.split("}")[-1] if "}" in elem.tag else elem.tag
        if tag != "AdaptationSet":
            continue
        content_type = (elem.attrib.get("contentType") or elem.attrib.get("mimeType") or "").lower()

        for rep in elem:
            rep_tag = rep.tag.split("}")[-1] if "}" in rep.tag else rep.tag
            if rep_tag != "Representation":
                continue
            rep_mime = (rep.attrib.get("mimeType") or content_type).lower()
            bw = int(rep.attrib.get("bandwidth") or 0)
            w = int(rep.attrib.get("width") or 0)
            h = int(rep.attrib.get("height") or 0)

            rep_base = None
            for child in rep:
                c_tag = child.tag.split("}")[-1] if "}" in child.tag else child.tag
                if c_tag == "BaseURL" and child.text:
                    rep_base = child.text.strip()
                    break
            if not rep_base:
                continue

            full_url = rep_base if rep_base.startswith("http") else f"{base_dir}/{rep_base.lstrip('/')}"
            is_audio_rep = (
                "audio" in rep_mime
                or "audio" in rep_base.lower()
                or (w == 0 and h == 0 and "video" not in rep_mime)
            )
            if is_audio_rep:
                if bw >= best_audio_bw:
                    best_audio_bw = bw
                    best_audio = full_url
            else:
                score = (w * h, bw)
                if score > best_video_score:
                    best_video_score = score
                    best_video = (full_url, w or None, h or None)

    if best_video:
        result["video_url"], result["width"], result["height"] = best_video
    if best_audio:
        result["audio_url"] = best_audio
    return result


class RedditHttpClient:
    """
    Lightweight HTTP client with OAuth2 bearer auth, browser cookie support,
    minimum inter-request delay (min_time), HTTP 429 / X-Ratelimit backoff,
    retries, and optional proxy support.
    """

    def __init__(self, options=None):
        opts = options or {}
        self.user_agent = opts.get("user_agent") or DEFAULT_USER_AGENT
        self.browser_ua = opts.get("browser_user_agent") or DEFAULT_BROWSER_UA
        self.cookies = (opts.get("cookies") or "").strip()
        self.timeout = int(opts.get("timeout") or 30)
        self.max_retries = max(1, int(opts.get("max_retries") or 3))
        self.min_time_sec = max(0.0, float(opts.get("min_time_ms") or 200) / 1000.0)
        self.proxy = (opts.get("proxy") or "").strip() or None
        self.verify_ssl = not bool(opts.get("proxy_insecure", False))

        # OAuth credentials (optional)
        self.client_id = (opts.get("reddit_client_id") or opts.get("client_id") or "").strip()
        self.client_secret = (opts.get("reddit_client_secret") or opts.get("client_secret") or "").strip()
        self.username = (opts.get("reddit_username") or opts.get("username") or "").strip()
        self.password = (opts.get("reddit_password") or opts.get("password") or "").strip()
        self.refresh_token = (opts.get("reddit_refresh_token") or opts.get("refresh_token") or "").strip()

        self._access_token = None
        self._token_expires_at = 0.0
        self._last_request_ts = 0.0
        self._redgifs_token = None

        self._session = requests.Session() if REQUESTS_AVAILABLE else None
        if self._session is not None and self.proxy:
            self._session.proxies = {"http": self.proxy, "https": self.proxy}

    @property
    def has_oauth_credentials(self):
        if not self.client_id:
            return False
        if self.refresh_token:
            return True
        if self.client_secret and self.username and self.password:
            return True
        if self.client_secret:
            return True
        return False

    @property
    def has_user_auth(self):
        return bool(
            (self.client_id and (self.refresh_token or (self.username and self.password)))
            or ("reddit_session=" in self.cookies or "token_v2=" in self.cookies)
        )

    def _throttle(self):
        if self.min_time_sec <= 0:
            return
        now = time.monotonic()
        elapsed = now - self._last_request_ts
        if elapsed < self.min_time_sec:
            time.sleep(self.min_time_sec - elapsed)
        self._last_request_ts = time.monotonic()

    def _raw_request(self, method, url, headers=None, data=None, allow_redirects=True):
        self._throttle()
        req_headers = dict(headers or {})
        if self._session is not None:
            resp = self._session.request(
                method=method,
                url=url,
                headers=req_headers,
                data=data,
                timeout=self.timeout,
                allow_redirects=allow_redirects,
                verify=self.verify_ssl,
            )
            return resp.status_code, resp.text, dict(resp.headers), resp.url

        encoded_data = None
        if data is not None:
            if isinstance(data, dict):
                encoded_data = urlencode(data).encode("utf-8")
            elif isinstance(data, str):
                encoded_data = data.encode("utf-8")
            else:
                encoded_data = data
        req = urllib.request.Request(url, data=encoded_data, headers=req_headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                body = resp.read().decode("utf-8", errors="replace")
                return resp.status, body, dict(resp.headers), resp.geturl()
        except urllib.error.HTTPError as he:
            body = ""
            try:
                body = he.read().decode("utf-8", errors="replace")
            except Exception:
                pass
            return he.code, body, dict(he.headers or {}), url

    def _obtain_oauth_token(self):
        if not self.has_oauth_credentials:
            return None
        now = time.time()
        if self._access_token and now < self._token_expires_at - 30:
            return self._access_token

        basic_raw = f"{self.client_id}:{self.client_secret}".encode("utf-8")
        basic_b64 = base64.b64encode(basic_raw).decode("ascii")
        headers = {
            "Authorization": f"Basic {basic_b64}",
            "User-Agent": self.user_agent,
            "Content-Type": "application/x-www-form-urlencoded",
        }
        if self.refresh_token:
            form = {"grant_type": "refresh_token", "refresh_token": self.refresh_token}
        elif self.username and self.password:
            form = {"grant_type": "password", "username": self.username, "password": self.password}
        else:
            form = {"grant_type": "client_credentials"}

        try:
            status, body, _, _ = self._raw_request(
                "POST", "https://www.reddit.com/api/v1/access_token", headers=headers, data=form
            )
            if status == 200:
                payload = json.loads(body)
                token = payload.get("access_token")
                if token:
                    self._access_token = token
                    self._token_expires_at = now + int(payload.get("expires_in") or 3600)
                    return token
        except Exception:
            pass
        return None

    def get_reddit_json(self, endpoint_path, params=None):
        """
        Fetches a Reddit API JSON endpoint using OAuth (oauth.reddit.com) when configured,
        falling back to www.reddit.com / old.reddit.com with optional browser cookies.
        """
        query_params = dict(params or {})
        query_params.setdefault("raw_json", "1")
        qs = urlencode(query_params)
        path = endpoint_path if endpoint_path.startswith("/") else "/" + endpoint_path

        token = self._obtain_oauth_token()
        candidates = []
        if token:
            candidates.append((f"https://oauth.reddit.com{path}?{qs}", True))
        candidates.append((f"https://www.reddit.com{path}.json?{qs}" if not path.endswith(".json") else f"https://www.reddit.com{path}?{qs}", False))
        candidates.append((f"https://old.reddit.com{path}.json?{qs}" if not path.endswith(".json") else f"https://old.reddit.com{path}?{qs}", False))

        last_error = "Reddit API request failed"
        for url, use_oauth in candidates:
            for attempt in range(self.max_retries):
                headers = {
                    "User-Agent": self.user_agent if use_oauth else self.browser_ua,
                    "Accept": "application/json",
                }
                if use_oauth and token:
                    headers["Authorization"] = f"Bearer {token}"
                elif self.cookies:
                    headers["Cookie"] = self.cookies

                try:
                    status, body, resp_headers, _ = self._raw_request("GET", url, headers=headers)
                    if status == 200:
                        return json.loads(body), None
                    if status == 429:
                        reset = resp_headers.get("x-ratelimit-reset") or resp_headers.get("X-Ratelimit-Reset") or resp_headers.get("Retry-After")
                        wait_s = min(float(reset), 5.0) if reset and str(reset).replace(".", "", 1).isdigit() else min(1.5 * (attempt + 1), 5.0)
                        time.sleep(wait_s)
                        last_error = "Reddit rate limit exceeded (HTTP 429)"
                        continue
                    if status in (401, 403):
                        last_error = f"Reddit authentication required or access denied (HTTP {status})"
                        break
                    if status == 404:
                        last_error = "Reddit target not found (HTTP 404)"
                        break
                    last_error = f"Reddit HTTP {status}"
                except Exception as e:
                    last_error = f"{e.__class__.__name__}: {e}"
                time.sleep(min(0.5 * (attempt + 1), 2.0))
        return None, last_error

    def resolve_redirect(self, url):
        """Resolves Reddit mobile /s/ share links or redd.it shortlinks to canonical URL."""
        headers = {"User-Agent": self.browser_ua}
        if self.cookies:
            headers["Cookie"] = self.cookies
        try:
            _, _, _, final_url = self._raw_request("GET", url, headers=headers, allow_redirects=True)
            return final_url or url
        except Exception:
            return url

    def fetch_text(self, url, extra_headers=None):
        headers = {"User-Agent": self.browser_ua}
        if self.cookies and "redd.it" in url.lower() or "reddit.com" in url.lower():
            headers["Cookie"] = self.cookies
        if extra_headers:
            headers.update(extra_headers)
        try:
            status, body, _, _ = self._raw_request("GET", url, headers=headers)
            if status == 200:
                return body
        except Exception:
            pass
        return None

    def fetch_redgifs_video(self, redgifs_id):
        """
        Resolves a RedGIFs ID via the official RedGIFs v2 temporary token API.
        Returns dict {"url": ..., "thumbnail": ..., "width": ..., "height": ...} or None.
        """
        if not redgifs_id:
            return None
        gid = redgifs_id.strip().lower()
        try:
            if not self._redgifs_token:
                status, body, _, _ = self._raw_request(
                    "GET",
                    "https://api.redgifs.com/v2/auth/temporary",
                    headers={"User-Agent": self.browser_ua, "Accept": "application/json"},
                )
                if status == 200:
                    self._redgifs_token = json.loads(body).get("token")
            if not self._redgifs_token:
                return None

            status, body, _, _ = self._raw_request(
                "GET",
                f"https://api.redgifs.com/v2/gifs/{gid}",
                headers={
                    "User-Agent": self.browser_ua,
                    "Accept": "application/json",
                    "Authorization": f"Bearer {self._redgifs_token}",
                    "Referer": "https://www.redgifs.com/",
                },
            )
            if status != 200:
                return None
            gif = (json.loads(body) or {}).get("gif") or {}
            urls = gif.get("urls") or {}
            video_url = urls.get("hd") or urls.get("sd") or urls.get("silent")
            if not video_url:
                return None
            return {
                "url": video_url,
                "thumbnail": urls.get("poster") or urls.get("thumbnail"),
                "width": gif.get("width"),
                "height": gif.get("height"),
                "duration": gif.get("duration"),
            }
        except Exception:
            return None


def _make_item(
    url,
    index,
    is_video,
    title_base="",
    thumbnail_url=None,
    width=None,
    height=None,
    page_url="",
    mime_hint=None,
    is_stream=False,
    is_audio=False,
    metadata=None,
    download_headers=None,
    source="reddit-dl",
):
    ext = _guess_extension(url, is_video=is_video, mime_hint=mime_hint)
    safe_base = _sanitize_slug(title_base) or f"reddit_{index + 1}"
    item_title = f"{safe_base}.{ext}" if not safe_base.lower().endswith("." + ext) else safe_base
    url_hash = hashlib.sha256(url.encode("utf-8")).hexdigest()[:12]
    w = int(width) if isinstance(width, (int, float)) and width > 0 else None
    h = int(height) if isinstance(height, (int, float)) and height > 0 else None
    res_hint = f"{w}x{h}" if w and h else None

    if is_stream:
        category = "STREAMS"
        fmt = "HLS/DASH"
        mime_type = "application/x-mpegURL" if ".m3u8" in url.lower() else "application/dash+xml"
    elif is_audio:
        category = "AUDIO"
        fmt = ext.upper()
        mime_type = f"audio/{ext}"
    elif is_video:
        category = "VIDEOS"
        fmt = ext.upper()
        mime_type = f"video/{ext}"
    else:
        category = "IMAGES"
        fmt = ext.upper()
        mime_type = f"image/{'jpeg' if ext == 'jpg' else ext}"

    item = {
        "id": f"rdl_{index}_{url_hash}",
        "url": url,
        "title": item_title,
        "category": category,
        "format": fmt,
        "mimeType": mime_type,
        "isVideo": bool(is_video or is_stream),
        "isAudio": bool(is_audio),
        "isStream": bool(is_stream),
        "thumbnailUrl": thumbnail_url or (None if (is_video or is_audio or is_stream) else url),
        "width": w,
        "height": h,
        "resolutionHint": res_hint,
        "sizeBytes": None,
        "source": source,
        "pageUrl": page_url,
    }
    if metadata:
        item["metadata"] = metadata
    if download_headers:
        item["downloadHeaders"] = download_headers
    return item


def _extract_preview_thumb(post):
    preview = post.get("preview")
    if isinstance(preview, dict):
        images = preview.get("images") or []
        if images and isinstance(images[0], dict):
            src = (images[0].get("source") or {}).get("url")
            if src:
                return _unescape_url(src)
    thumb = post.get("thumbnail") or ""
    if isinstance(thumb, str) and thumb.startswith("http"):
        return _unescape_url(thumb)
    return None


def _extract_post_media(post, client, seen_urls, start_index, extra_metadata=None):
    """
    Extracts all media items from a single Reddit post dict (t3 data):
      - Unwraps crossposts (crosspost_parent_list)
      - Reddit galleries (gallery_data + media_metadata)
      - Reddit-hosted videos (v.redd.it + DASH MPD high-res resolution & companion audio)
      - RedGIFs videos (via RedGIFs v2 API + reddit_video_preview fallback)
      - Reddit-hosted images & external direct media (Imgur .gifv -> .mp4, etc.)
    """
    if not isinstance(post, dict):
        return []

    # Unwrap crosspost if the outer post delegates its media to the parent post
    source_post = post
    crossposts = post.get("crosspost_parent_list")
    if isinstance(crossposts, list) and crossposts and isinstance(crossposts[0], dict):
        parent = crossposts[0]
        if (
            parent.get("is_gallery")
            or parent.get("is_video")
            or parent.get("media_metadata")
            or parent.get("url_overridden_by_dest")
        ):
            source_post = parent

    post_id = str(post.get("id") or source_post.get("id") or "")
    subreddit = str(post.get("subreddit") or source_post.get("subreddit") or "")
    author = str(post.get("author") or source_post.get("author") or "")
    post_title = str(post.get("title") or source_post.get("title") or "")
    selftext = str(post.get("selftext") or "")
    permalink = post.get("permalink") or source_post.get("permalink") or ""
    page_url = f"https://www.reddit.com{permalink}" if permalink.startswith("/") else (permalink or f"https://www.reddit.com/comments/{post_id}/")
    thumb_fallback = _extract_preview_thumb(source_post) or _extract_preview_thumb(post)

    base_meta = {
        "source_url": page_url,
        "post_id": post_id,
        "subreddit": subreddit,
        "author": author,
        "post_title": post_title[:240],
        "created_utc": str(post.get("created_utc") or ""),
        "score": str(post.get("score") if post.get("score") is not None else ""),
        "over_18": str(bool(post.get("over_18", False))).lower(),
    }
    if selftext:
        base_meta["selftext"] = selftext[:1000]
    if extra_metadata:
        for k, v in extra_metadata.items():
            if v is not None:
                base_meta[str(k)] = str(v)

    title_prefix = f"r_{subreddit}_{post_id}" if subreddit and post_id else (post_id or "reddit")
    if post_title:
        title_prefix = f"{title_prefix}_{_sanitize_slug(post_title, 32)}"

    extracted = []
    idx = start_index

    def _add(url, is_video, suffix="", thumb=None, w=None, h=None, mime_hint=None, is_stream=False, is_audio=False, meta_extra=None, dl_headers=None):
        nonlocal idx
        clean_u = _unescape_url(url)
        if not clean_u or clean_u in seen_urls:
            return
        seen_urls.add(clean_u)
        meta = dict(base_meta)
        if meta_extra:
            meta.update(meta_extra)
        t_base = f"{title_prefix}_{suffix}" if suffix else title_prefix
        extracted.append(
            _make_item(
                url=clean_u,
                index=idx,
                is_video=is_video,
                title_base=t_base,
                thumbnail_url=thumb or thumb_fallback,
                width=w,
                height=h,
                page_url=page_url,
                mime_hint=mime_hint,
                is_stream=is_stream,
                is_audio=is_audio,
                metadata=meta,
                download_headers=dl_headers,
            )
        )
        idx += 1

    # 1. Reddit Gallery (gallery_data + media_metadata)
    media_metadata = source_post.get("media_metadata")
    if isinstance(media_metadata, dict) and media_metadata:
        gallery_data = source_post.get("gallery_data") or {}
        ordered_ids = []
        if isinstance(gallery_data, dict) and isinstance(gallery_data.get("items"), list):
            for g_item in gallery_data["items"]:
                if isinstance(g_item, dict) and g_item.get("media_id"):
                    ordered_ids.append(str(g_item["media_id"]))
        if not ordered_ids:
            ordered_ids = list(media_metadata.keys())

        for g_idx, mid in enumerate(ordered_ids, start=1):
            m_info = media_metadata.get(mid)
            if not isinstance(m_info, dict) or m_info.get("status") == "failed":
                continue
            s_obj = m_info.get("s") or {}
            mime_hint = m_info.get("m") or ""
            w = s_obj.get("x")
            h = s_obj.get("y")
            media_url = s_obj.get("u") or s_obj.get("gif") or s_obj.get("mp4")
            is_vid = bool(s_obj.get("mp4") and not s_obj.get("u") and not s_obj.get("gif"))
            if not media_url:
                # Fallback to highest-res preview in 'p'
                p_list = m_info.get("p") or []
                if isinstance(p_list, list) and p_list:
                    best_p = max(p_list, key=lambda x: int(x.get("x") or 0) * int(x.get("y") or 0))
                    media_url = best_p.get("u")
                    w = best_p.get("x") or w
                    h = best_p.get("y") or h
            if media_url:
                _add(
                    media_url,
                    is_video=is_vid,
                    suffix=f"gallery_{g_idx}",
                    thumb=_unescape_url(s_obj.get("u") or media_url) if not is_vid else thumb_fallback,
                    w=w,
                    h=h,
                    mime_hint=mime_hint,
                )
        if extracted:
            return extracted

    # 2. Reddit-hosted Video (v.redd.it) with DASH MPD high-res resolution & audio companion
    secure_media = source_post.get("secure_media") or source_post.get("media") or {}
    reddit_video = secure_media.get("reddit_video") if isinstance(secure_media, dict) else None
    if isinstance(reddit_video, dict) and (reddit_video.get("fallback_url") or reddit_video.get("dash_url") or reddit_video.get("hls_url")):
        video_url = _unescape_url(reddit_video.get("fallback_url") or "")
        w = reddit_video.get("width")
        h = reddit_video.get("height")
        audio_url = None
        dash_url = _unescape_url(reddit_video.get("dash_url") or "")
        hls_url = _unescape_url(reddit_video.get("hls_url") or "")

        # Fetch DASHPlaylist.mpd to obtain highest available resolution (>720p when cookies present) and exact audio track
        if dash_url:
            mpd_text = client.fetch_text(dash_url)
            if mpd_text:
                parsed_dash = _parse_dash_mpd(mpd_text, dash_url)
                if parsed_dash.get("video_url"):
                    video_url = parsed_dash["video_url"]
                    w = parsed_dash.get("width") or w
                    h = parsed_dash.get("height") or h
                if parsed_dash.get("audio_url"):
                    audio_url = parsed_dash["audio_url"]

        if video_url:
            meta_extra = {}
            if audio_url:
                meta_extra["audio_url"] = audio_url
            if hls_url:
                meta_extra["hls_url"] = hls_url
            if dash_url:
                meta_extra["dash_url"] = dash_url
            _add(
                video_url,
                is_video=True,
                suffix="video",
                thumb=thumb_fallback,
                w=w,
                h=h,
                mime_hint="video/mp4",
                meta_extra=meta_extra,
            )
            return extracted
        elif hls_url:
            _add(hls_url, is_video=True, suffix="stream", thumb=thumb_fallback, w=w, h=h, is_stream=True)
            return extracted

    # 3. RedGIFs videos (post URL or oembed)
    dest_url = _unescape_url(source_post.get("url_overridden_by_dest") or source_post.get("url") or "")
    if dest_url and is_redgifs_url(dest_url):
        rg_match = _REDGIFS_ID_RE.search(urlparse(dest_url).path or "")
        if rg_match:
            rg_data = client.fetch_redgifs_video(rg_match.group(1))
            if rg_data and rg_data.get("url"):
                _add(
                    rg_data["url"],
                    is_video=True,
                    suffix="redgifs",
                    thumb=rg_data.get("thumbnail") or thumb_fallback,
                    w=rg_data.get("width"),
                    h=rg_data.get("height"),
                    mime_hint="video/mp4",
                    dl_headers={
                        "User-Agent": client.browser_ua,
                        "Referer": "https://www.redgifs.com/",
                    },
                )
                return extracted

    # 4. Reddit video preview (often populated for RedGIFs / Gfycat / Imgur GIFVs)
    preview = source_post.get("preview")
    if isinstance(preview, dict):
        rvp = preview.get("reddit_video_preview")
        if isinstance(rvp, dict) and rvp.get("fallback_url"):
            _add(
                rvp["fallback_url"],
                is_video=True,
                suffix="preview_video",
                thumb=thumb_fallback,
                w=rvp.get("width"),
                h=rvp.get("height"),
                mime_hint="video/mp4",
            )
            return extracted

    # 5. Direct image / video / Imgur .gifv link in dest_url
    if dest_url:
        low_dest = dest_url.split("?")[0].split("#")[0].lower()
        if low_dest.endswith(".gifv") and "imgur.com" in low_dest:
            mp4_url = dest_url.split("?")[0][:-5] + ".mp4"
            _add(mp4_url, is_video=True, suffix="imgur", thumb=thumb_fallback, mime_hint="video/mp4")
            return extracted
        if any(low_dest.endswith("." + ext) for ext in _VIDEO_EXTS):
            _add(dest_url, is_video=True, suffix="video", thumb=thumb_fallback)
            return extracted
        if any(low_dest.endswith("." + ext) for ext in _IMAGE_EXTS) or "i.redd.it/" in low_dest:
            w = None
            h = None
            if isinstance(preview, dict) and preview.get("images"):
                src_obj = (preview["images"][0] or {}).get("source") or {}
                w = src_obj.get("width")
                h = src_obj.get("height")
            _add(dest_url, is_video=False, suffix="image", thumb=thumb_fallback or dest_url, w=w, h=h)
            return extracted

    # 6. Fallback to high-res preview image if post has preview.images (e.g. external image host)
    if isinstance(preview, dict):
        images = preview.get("images") or []
        if images and isinstance(images[0], dict):
            variants = images[0].get("variants") or {}
            mp4_var = (variants.get("mp4") or {}).get("source")
            if isinstance(mp4_var, dict) and mp4_var.get("url"):
                _add(
                    mp4_var["url"],
                    is_video=True,
                    suffix="mp4",
                    thumb=thumb_fallback,
                    w=mp4_var.get("width"),
                    h=mp4_var.get("height"),
                    mime_hint="video/mp4",
                )
                return extracted
            src_obj = images[0].get("source") or {}
            if src_obj.get("url"):
                _add(
                    src_obj["url"],
                    is_video=False,
                    suffix="preview",
                    thumb=src_obj["url"],
                    w=src_obj.get("width"),
                    h=src_obj.get("height"),
                )
                return extracted

    return extracted


def _summarize_comments(comments_listing, max_comments=20):
    """Extracts a compact list of top comments from a Reddit comments listing (t1 children)."""
    if not isinstance(comments_listing, dict):
        return []
    children = (comments_listing.get("data") or {}).get("children") or []
    out = []
    for child in children:
        if len(out) >= max_comments:
            break
        if not isinstance(child, dict) or child.get("kind") != "t1":
            continue
        d = child.get("data") or {}
        body = (d.get("body") or "").strip()
        if not body:
            continue
        out.append({
            "id": d.get("id", ""),
            "author": d.get("author", "[deleted]"),
            "score": d.get("score", 0),
            "body": body[:300],
        })
    return out


def _fetch_post_author_details(username, client, cache):
    if not username or username in ("[deleted]", "AutoModerator"):
        return None
    key = username.lower()
    if key in cache:
        return cache[key]
    payload, _ = client.get_reddit_json(f"/user/{username}/about")
    if isinstance(payload, dict) and isinstance(payload.get("data"), dict):
        d = payload["data"]
        info = {
            "name": d.get("name", username),
            "created_utc": d.get("created_utc"),
            "link_karma": d.get("link_karma"),
            "comment_karma": d.get("comment_karma"),
        }
        cache[key] = info
        return info
    cache[key] = None
    return None


def _collect_listing_posts(endpoint_path, client, limit, after_ts=None, before_ts=None, seen_post_ids=None, base_params=None):
    """
    Paginates a Reddit listing endpoint respecting:
      - limit: maximum number of matching posts to collect
      - after_ts: inclusive lower bound on created_utc
      - before_ts: exclusive upper bound on created_utc
      - seen_post_ids: set of previously downloaded post IDs (--continue); ignores stickied posts
        so pinned posts do not prematurely terminate pagination (matching reddit-dl v1.1.1).
    """
    posts = []
    after_cursor = None
    seen_ids = set(seen_post_ids or [])
    max_pages = max(1, min(20, (limit // 25) + 3))

    for _ in range(max_pages):
        if len(posts) >= limit:
            break
        params = dict(base_params or {})
        params["limit"] = str(min(100, max(25, limit - len(posts))))
        if after_cursor:
            params["after"] = after_cursor

        payload, err = client.get_reddit_json(endpoint_path, params=params)
        if err or not isinstance(payload, dict):
            if not posts:
                return [], err
            break

        data = payload.get("data") or {}
        children = data.get("children") or []
        if not children:
            break

        stop_pagination = False
        for child in children:
            if len(posts) >= limit:
                stop_pagination = True
                break
            if not isinstance(child, dict):
                continue
            kind = child.get("kind")
            if kind != "t3":
                continue
            post = child.get("data") or {}
            pid = str(post.get("id") or "")
            is_stickied = bool(post.get("stickied") or post.get("pinned"))
            created = post.get("created_utc")

            # --continue: stop on encountering a previously downloaded non-stickied post
            if pid and pid in seen_ids:
                if not is_stickied:
                    stop_pagination = True
                    break
                continue

            # Date range filtering (--after inclusive, --before exclusive)
            if isinstance(created, (int, float)):
                if before_ts is not None and created >= before_ts:
                    continue
                if after_ts is not None and created < after_ts:
                    # For chronological 'new' listings, non-stickied posts older than after_ts end pagination
                    if not is_stickied and endpoint_path.endswith("/new"):
                        stop_pagination = True
                        break
                    continue

            posts.append(post)

        after_cursor = data.get("after")
        if stop_pagination or not after_cursor:
            break

    return posts, None


def _get_authenticated_username(client):
    """Fetches the authenticated Reddit account's username via /api/v1/me."""
    if client.username:
        return client.username
    payload, err = client.get_reddit_json("/api/v1/me")
    if not err and isinstance(payload, dict) and payload.get("name"):
        return str(payload["name"])
    return None


def extract_reddit(target, options_json="{}"):
    """
    Main entry point called by gallery_dl_runner.py / GalleryDlEngine.kt.
    Accepts any Reddit URL, RedGIFs URL, or patrickkfkan/reddit-dl shorthand target.
    """
    clean_target = (target or "").strip()
    if not clean_target:
        return json.dumps({"success": False, "engine": "reddit-dl", "error": "Target URL cannot be empty", "items": []})

    try:
        opts = json.loads(options_json) if options_json else {}
    except Exception:
        opts = {}

    limit = max(1, min(500, int(opts.get("limit") or 25)))
    after_ts = parse_date_filter(opts.get("after") or opts.get("after_date"))
    before_ts = parse_date_filter(opts.get("before") or opts.get("before_date"))
    include_comments = bool(opts.get("comments") or opts.get("include_comments"))
    include_post_authors = bool(opts.get("post_authors") or opts.get("include_post_authors"))
    seen_post_ids = opts.get("seen_post_ids") or opts.get("continue_from_ids") or []

    client = RedditHttpClient(opts)
    parsed = parse_reddit_target(clean_target)

    # Resolve mobile /s/ share links first
    if parsed.get("kind") == "share":
        resolved = client.resolve_redirect(parsed["url"])
        parsed = parse_reddit_target(resolved)

    kind = parsed.get("kind", "unknown")
    items = []
    seen_urls = set()
    page_title = ""
    page_description = ""
    author_cache = {}

    # 1. Direct RedGIFs target
    if kind == "redgifs":
        rg_id = parsed["redgifs_id"]
        rg_data = client.fetch_redgifs_video(rg_id)
        if not rg_data or not rg_data.get("url"):
            return json.dumps({
                "success": False,
                "engine": "reddit-dl",
                "error": f"Could not resolve RedGIFs video: {rg_id}",
                "items": [],
            })
        item = _make_item(
            url=rg_data["url"],
            index=0,
            is_video=True,
            title_base=f"redgifs_{rg_id}",
            thumbnail_url=rg_data.get("thumbnail"),
            width=rg_data.get("width"),
            height=rg_data.get("height"),
            page_url=parsed.get("canonical_url") or clean_target,
            mime_hint="video/mp4",
            download_headers={"User-Agent": client.browser_ua, "Referer": "https://www.redgifs.com/"},
        )
        items.append(item)
        return json.dumps({
            "success": True,
            "engine": "reddit-dl",
            "extractor": "RedditDlRedgifs",
            "pageTitle": f"RedGIFs {rg_id}",
            "pageDescription": "",
            "previewImageUrl": rg_data.get("thumbnail") or "",
            "itemsCount": len(items),
            "items": items,
        })

    # 2. Direct Reddit media CDN URL (i.redd.it / v.redd.it / preview.redd.it)
    if kind == "direct_media":
        direct_url = parsed["url"]
        is_vid = "v.redd.it" in direct_url.lower() or any(direct_url.lower().split("?")[0].endswith("." + e) for e in _VIDEO_EXTS)
        items.append(
            _make_item(
                url=direct_url,
                index=0,
                is_video=is_vid,
                title_base="reddit_media",
                page_url=direct_url,
            )
        )
        return json.dumps({
            "success": True,
            "engine": "reddit-dl",
            "extractor": "RedditDlDirect",
            "pageTitle": "Reddit Media",
            "pageDescription": "",
            "previewImageUrl": "" if is_vid else direct_url,
            "itemsCount": 1,
            "items": items,
        })

    # 3. Single post (p/<id>, redd.it/<id>, /comments/<id>, /gallery/<id>)
    if kind == "post":
        post_id = parsed["post_id"]
        payload, err = client.get_reddit_json(f"/comments/{post_id}")
        if err or not payload:
            return json.dumps({"success": False, "engine": "reddit-dl", "error": err or "Post fetch failed", "items": []})

        post_data = None
        comments_summary = []
        if isinstance(payload, list) and payload:
            children = (payload[0].get("data") or {}).get("children") or []
            if children and isinstance(children[0], dict):
                post_data = children[0].get("data")
            if include_comments and len(payload) > 1:
                comments_summary = _summarize_comments(payload[1])
        elif isinstance(payload, dict):
            children = (payload.get("data") or {}).get("children") or []
            if children and isinstance(children[0], dict):
                post_data = children[0].get("data")

        if not post_data:
            return json.dumps({"success": False, "engine": "reddit-dl", "error": "Reddit post data missing", "items": []})

        extra_meta = {}
        if include_comments and comments_summary:
            extra_meta["comments_count"] = len(comments_summary)
            extra_meta["comments_json"] = json.dumps(comments_summary)
        if include_post_authors and post_data.get("author"):
            a_info = _fetch_post_author_details(post_data["author"], client, author_cache)
            if a_info:
                extra_meta["author_details"] = json.dumps(a_info)

        page_title = str(post_data.get("title") or f"Reddit Post {post_id}")
        page_description = str(post_data.get("selftext") or "")[:500]
        items.extend(_extract_post_media(post_data, client, seen_urls, len(items), extra_metadata=extra_meta))

    # 4. Subreddit listing (r/<subreddit>)
    elif kind == "subreddit":
        sub = parsed["subreddit"]
        sort = parsed.get("sort") or "hot"
        base_params = {}
        if parsed.get("time_filter"):
            base_params["t"] = parsed["time_filter"]
        posts, err = _collect_listing_posts(
            f"/r/{sub}/{sort}",
            client,
            limit=limit,
            after_ts=after_ts,
            before_ts=before_ts,
            seen_post_ids=seen_post_ids,
            base_params=base_params,
        )
        if err and not posts:
            return json.dumps({"success": False, "engine": "reddit-dl", "error": err, "items": []})

        page_title = f"r/{sub}"
        for post in posts:
            extra_meta = {}
            if include_comments and post.get("id"):
                c_payload, _ = client.get_reddit_json(f"/comments/{post['id']}", params={"limit": "20"})
                if isinstance(c_payload, list) and len(c_payload) > 1:
                    c_sum = _summarize_comments(c_payload[1])
                    if c_sum:
                        extra_meta["comments_count"] = len(c_sum)
                        extra_meta["comments_json"] = json.dumps(c_sum)
            if include_post_authors and post.get("author"):
                a_info = _fetch_post_author_details(post["author"], client, author_cache)
                if a_info:
                    extra_meta["author_details"] = json.dumps(a_info)
            items.extend(_extract_post_media(post, client, seen_urls, len(items), extra_metadata=extra_meta))

    # 5. User submitted posts (u/<username>)
    elif kind == "user":
        username = parsed["username"]
        section = parsed.get("section") or "submitted"
        base_params = {"sort": parsed.get("sort") or "new"}
        if parsed.get("time_filter"):
            base_params["t"] = parsed["time_filter"]
        posts, err = _collect_listing_posts(
            f"/user/{username}/{section}",
            client,
            limit=limit,
            after_ts=after_ts,
            before_ts=before_ts,
            seen_post_ids=seen_post_ids,
            base_params=base_params,
        )
        if err and not posts:
            return json.dumps({"success": False, "engine": "reddit-dl", "error": err, "items": []})

        page_title = f"u/{username}"
        author_meta = {}
        if include_post_authors:
            a_info = _fetch_post_author_details(username, client, author_cache)
            if a_info:
                author_meta["author_details"] = json.dumps(a_info)
        for post in posts:
            items.extend(_extract_post_media(post, client, seen_urls, len(items), extra_metadata=author_meta))

    # 6. Account-specific targets: my/saved, my/joined, my/following
    elif kind in ("my_saved", "my_joined", "my_following"):
        if not client.has_user_auth:
            return json.dumps({
                "success": False,
                "engine": "reddit-dl",
                "error": f"Sign in to Reddit or configure Reddit OAuth credentials to access {clean_target}.",
                "items": [],
            })

        if kind == "my_saved":
            me = _get_authenticated_username(client)
            if not me:
                return json.dumps({
                    "success": False,
                    "engine": "reddit-dl",
                    "error": "Could not identify authenticated Reddit account. Please sign in to Reddit.",
                    "items": [],
                })
            posts, err = _collect_listing_posts(
                f"/user/{me}/saved",
                client,
                limit=limit,
                after_ts=after_ts,
                before_ts=before_ts,
                seen_post_ids=seen_post_ids,
            )
            if err and not posts:
                return json.dumps({"success": False, "engine": "reddit-dl", "error": err, "items": []})
            page_title = f"Saved Posts (u/{me})"
            for post in posts:
                items.extend(_extract_post_media(post, client, seen_urls, len(items)))

        else:
            # my/joined (subreddits) or my/following (u_ profile subreddits)
            sub_payload, err = client.get_reddit_json("/subreddits/mine/subscriber", params={"limit": "100"})
            if err or not isinstance(sub_payload, dict):
                return json.dumps({
                    "success": False,
                    "engine": "reddit-dl",
                    "error": err or "Could not fetch subscribed Reddit communities. Please sign in to Reddit.",
                    "items": [],
                })
            children = (sub_payload.get("data") or {}).get("children") or []
            target_subs = []
            for ch in children:
                d = (ch or {}).get("data") or {}
                display = str(d.get("display_name") or "")
                if not display:
                    continue
                is_user_profile = display.startswith("u_")
                if kind == "my_following" and is_user_profile:
                    target_subs.append(display[2:])
                elif kind == "my_joined" and not is_user_profile:
                    target_subs.append(display)

            per_target_limit = max(3, min(25, limit // max(1, len(target_subs))))
            page_title = "Following Users (Reddit)" if kind == "my_following" else "Joined Subreddits (Reddit)"
            for name in target_subs:
                if len(items) >= limit:
                    break
                ep = f"/user/{name}/submitted" if kind == "my_following" else f"/r/{name}/new"
                posts, _ = _collect_listing_posts(
                    ep,
                    client,
                    limit=per_target_limit,
                    after_ts=after_ts,
                    before_ts=before_ts,
                    seen_post_ids=seen_post_ids,
                )
                for post in posts:
                    if len(items) >= limit:
                        break
                    items.extend(_extract_post_media(post, client, seen_urls, len(items)))

    else:
        return json.dumps({
            "success": False,
            "engine": "reddit-dl",
            "error": f"Unsupported Reddit target: {clean_target}",
            "items": [],
        })

    preview_url = ""
    if items:
        preview_url = items[0].get("thumbnailUrl") or (items[0]["url"] if not items[0].get("isVideo") else "")

    return json.dumps({
        "success": len(items) > 0,
        "engine": "reddit-dl",
        "extractor": f"RedditDl({kind})",
        "pageTitle": page_title,
        "pageDescription": page_description,
        "previewImageUrl": preview_url,
        "itemsCount": len(items),
        "items": items,
        "error": None if items else "No downloadable media found in Reddit target",
    })
