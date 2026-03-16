"""
Hauk-compatible API views (create.php, post.php, stop.php + stubs).
All URLs are mounted under the live_track extension; use host hauk.geovault.example.com with nginx
proxying root to the extension base so clients see /api/create.php etc.
"""

import secrets
import uuid

from django.core.cache import cache
from django.http import HttpResponse
from django.utils import timezone
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_http_methods

from django.contrib.auth import get_user_model

from .helpers import parse_ingress_body
from .ingress_views import append_point_to_track
from .models import LiveTrack, LiveTrackWorldShare
from .world_share_views import build_live_track_share_url

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
    """Resolve user by email and track by Hauk password (hauk_password field). Returns (user, track) or (None, None)."""
    if not (usr and pwd):
        return None, None
    user = User.objects.filter(email__iexact=usr.strip()).first()
    if not user:
        return None, None
    track = LiveTrack.objects.filter(user=user, hauk_password=pwd).first()
    if not track:
        return None, None
    return user, track


def _get_hauk_session(sid: str) -> dict | None:
    data = cache.get(HAUK_SESSION_CACHE_PREFIX + sid)
    return data if isinstance(data, dict) else None


def _set_hauk_session(sid: str, track_id: str, user_id: int, share_id: str, duration_seconds: int) -> None:
    timeout = min(max(1, duration_seconds), HAUK_SESSION_CACHE_TIMEOUT_MAX)
    cache.set(
        HAUK_SESSION_CACHE_PREFIX + sid,
        {"track_id": str(track_id), "user_id": user_id, "share_id": str(share_id)},
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
    Returns OK\\nsid\\nview_url\\nview_id (solo mode). Unsupported options (group, E2E) are ignored; we behave as solo.
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

    world_share, _ = LiveTrackWorldShare.objects.get_or_create(
        track=track,
        defaults={"share_id": str(uuid.uuid4())},
    )
    share_id = world_share.share_id

    sid = secrets.token_urlsafe(24)
    _set_hauk_session(sid, track.id, track.user_id, share_id, dur)

    host = request.get_host().split(",")[0].strip()
    scheme = request.META.get("HTTP_X_FORWARDED_PROTO") or request.scheme or "https"
    base = f"{scheme}://{host}"
    view_url = base + build_live_track_share_url(share_id)

    # Hauk Android uses line 4 as "view_id" shown in the active share list.
    # Return the tracker name for friendly UX instead of a UUID-like share_id.
    view_id = (track.name or "").replace("\r", " ").replace("\n", " ").strip() or share_id
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

    # Hauk >= 1.2 expects post.php to return active-share metadata:
    # line 2 = link format (String.format-style), line 3 = comma-separated share IDs.
    share_id = str(session.get("share_id") or "").strip()
    host = request.get_host().split(",")[0].strip()
    scheme = request.META.get("HTTP_X_FORWARDED_PROTO") or request.scheme or "https"
    base = f"{scheme}://{host}"
    link_format = base + build_live_track_share_url("%s")
    share_csv = share_id if share_id else ""
    return _hauk_text_response(["OK", link_format, share_csv])


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
