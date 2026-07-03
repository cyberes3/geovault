"""
Hauk-compatible API views (create.php, post.php, stop.php + stubs).
All URLs are mounted under the live_track extension; use host hauk.geovault.example.com with nginx
proxying root to the extension base so clients see /api/create.php etc.
"""

import secrets

from django.core.cache import cache
from django.http import HttpResponse
from django.utils import timezone
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_http_methods

from django.contrib.auth import get_user_model
from website.public_url import public_base_url

from .helpers import parse_ingress_body
from .ingress_views import append_point_to_track
from .models import LiveTrack

User = get_user_model()

HAUK_VERSION_HEADER = "1.2"
HAUK_SESSION_CACHE_PREFIX = "hauk_sid:"
HAUK_SESSION_CACHE_TIMEOUT_MAX = 86400 * 7  # 7 days max


def _hauk_text_response(lines: list[str], status: int = 200) -> HttpResponse:
    body = "\n".join(lines) + "\n"
    resp = HttpResponse(body, content_type="text/plain; charset=utf-8", status=status)
    resp["X-Hauk-Version"] = HAUK_VERSION_HEADER
    return resp


def _resolve_hauk_user_track(usr: str, pwd: str) -> tuple[User | None, LiveTrack | None]:
    """
    Resolve user by email and track by Hauk password (hauk_password field).
    Compares hauk_password with secrets.compare_digest (constant-time) rather than a
    DB equality filter, since the password is a credential, not just a lookup key.
    Returns (user, track) or (None, None).
    """
    if not (usr and pwd):
        return None, None
    user = User.objects.filter(email__iexact=usr.strip()).first()
    if not user:
        return None, None
    for track in LiveTrack.objects.filter(user=user).only('id', 'user', 'hauk_password'):
        if secrets.compare_digest(track.hauk_password, pwd):
            return user, track
    return None, None


def _get_hauk_session(sid: str) -> dict | None:
    data = cache.get(HAUK_SESSION_CACHE_PREFIX + sid)
    return data if isinstance(data, dict) else None


def _set_hauk_session(sid: str, track_id: str, duration_seconds: int) -> None:
    timeout = min(max(1, duration_seconds), HAUK_SESSION_CACHE_TIMEOUT_MAX)
    cache.set(
        HAUK_SESSION_CACHE_PREFIX + sid,
        {"track_id": str(track_id)},
        timeout=timeout,
    )


def _delete_hauk_session(sid: str) -> None:
    cache.delete(HAUK_SESSION_CACHE_PREFIX + sid)


@require_http_methods(["POST"])
@csrf_exempt
def hauk_create(request):
    """
    POST api/create.php — Hauk session initiation.
    Body: usr (email), pwd (Hauk password), dur (seconds), int (interval). Optional: mod, ado, etc.
    Returns OK\\nsid\\nview_url\\nview_id (solo). view_url is scheme://host/ (trailing slash; required for Hauk iOS
    SharingManager URL parsing and StartSharingIntent). Not a world-share path. Unsupported options (group, E2E) ignored.
    """
    raw = parse_ingress_body(request)
    usr = (raw.get("usr") or "").strip() if isinstance(raw.get("usr"), str) else ""
    pwd = raw.get("pwd") or ""
    if isinstance(pwd, list):
        pwd = pwd[0] if pwd else ""
    user, track = _resolve_hauk_user_track(usr, pwd)
    if not track:
        return _hauk_text_response(["Incorrect password"], status=401)

    try:
        dur = int(raw.get("dur", 0))
        interval = int(raw.get("int", 1))
    except (TypeError, ValueError):
        dur = 60
        interval = 1
    dur = max(1, min(dur, HAUK_SESSION_CACHE_TIMEOUT_MAX))

    sid = secrets.token_urlsafe(24)
    _set_hauk_session(sid, track.id, dur)

    # Hauk clients (see external sources/Hauk, external sources/hauk-ios) treat line 3 as a public URL.
    # It must still be an absolute URL: iOS SharingManager uses URL(string: parts[2]) and fails if nil.
    # Hauk iOS StartSharingIntent waits until shareUrl != baseUrl; users often enter the server URL without
    # a trailing slash, so returning scheme://host/ usually keeps inequality without issuing a world share link.
    view_url = public_base_url().rstrip("/") + "/"

    # Hauk Android uses line 4 as "view_id" shown in the active share list.
    view_id = (track.name or "").replace("\r", " ").replace("\n", " ").strip() or str(track.id)
    return _hauk_text_response(["OK", sid, view_url, view_id])


@require_http_methods(["POST"])
@csrf_exempt
def hauk_post(request):
    """
    POST api/post.php — Hauk location update.
    Body: sid, lat, lon, time (Unix seconds), optional spd, acc, prv.
    """
    raw = parse_ingress_body(request)
    sid = (raw.get("sid") or "").strip() if isinstance(raw.get("sid"), str) else ""
    if not sid:
        return _hauk_text_response(["Session ID missing"], status=400)

    session = _get_hauk_session(sid)
    if not session:
        return _hauk_text_response(["Session expired"], status=400)

    track_id = session.get("track_id")
    track = LiveTrack.objects.filter(id=track_id).first()
    if not track:
        _delete_hauk_session(sid)
        return _hauk_text_response(["Session expired"], status=400)

    try:
        lat = float(raw.get("lat", 0))
        lon = float(raw.get("lon", 0))
    except (TypeError, ValueError):
        return _hauk_text_response(["Invalid coordinates"], status=400)

    time_val = raw.get("time")
    if time_val is not None:
        try:
            ts_sec = float(time_val)
            timestamp_ms = int(ts_sec * 1000)
        except (TypeError, ValueError):
            timestamp_ms = int(timezone.now().timestamp() * 1000)
    else:
        timestamp_ms = int(timezone.now().timestamp() * 1000)

    extra = {}
    if raw.get("acc") is not None:
        try:
            extra["acc"] = float(raw.get("acc"))
        except (TypeError, ValueError):
            pass
    if raw.get("spd") is not None:
        try:
            extra["spd_kph"] = float(raw.get("spd")) * 3.6
        except (TypeError, ValueError):
            pass
    if raw.get("prv") is not None:
        extra["prov"] = "fine" if str(raw.get("prv")) == "0" else "coarse"

    append_point_to_track(track, lat, lon, timestamp_ms, extra)

    # Hauk >= 1.2: lines 2–3 are link format and share-id CSV; we do not expose world-share links here.
    return _hauk_text_response(["OK", "", ""])


@require_http_methods(["POST"])
@csrf_exempt
def hauk_stop(request):
    """POST api/stop.php — End Hauk session."""
    raw = parse_ingress_body(request)
    sid = (raw.get("sid") or "").strip() if isinstance(raw.get("sid"), str) else ""
    if sid:
        _delete_hauk_session(sid)
    return _hauk_text_response(["OK"])


@require_http_methods(["POST"])
@csrf_exempt
def hauk_adopt_stub(request):
    """Stub: return OK so the app does not show an error."""
    return _hauk_text_response(["OK"])


@require_http_methods(["POST"])
@csrf_exempt
def hauk_new_link_stub(request):
    """Stub: return OK so the app does not show an error."""
    return _hauk_text_response(["OK"])


@require_http_methods(["GET"])
@csrf_exempt
def hauk_fetch_stub(request):
    """Stub: return minimal OK-style response for fetch.php (GET)."""
    return _hauk_text_response(["OK"])
