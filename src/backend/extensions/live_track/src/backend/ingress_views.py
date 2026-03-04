"""
Ingress endpoints: POST-only, Basic Auth, rate limit, append point.
"""

import base64

from django.conf import settings
from django.core.cache import cache
from django.db import transaction
from django.http import JsonResponse
from django.utils import timezone
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_http_methods
from pydantic import ValidationError as PydanticValidationError

from api.utils.responses import error_response
from website.config_loader import get_config_loader

from .helpers import broadcast_track_updated, parse_ingress_body, parse_time_to_ms
from .models import LiveTrack
from .validation import LiveTrackIngressBody


def _decode_basic_auth(request):
    auth = request.META.get("HTTP_AUTHORIZATION") or ""
    if not auth.startswith("Basic "):
        return None, None
    try:
        decoded = base64.b64decode(auth[6:].strip()).decode("utf-8")
        if ":" not in decoded:
            return None, None
        username, password = decoded.split(":", 1)
        return username.strip(), password
    except Exception:
        return None, None


def _resolve_user_and_track(email: str, password: str):
    """Resolve user by email only (user-facing identifier); internal username is not used."""
    from django.contrib.auth import get_user_model

    User = get_user_model()
    user = User.objects.filter(email=email).first()
    if not user:
        return None, None
    track = LiveTrack.objects.filter(user=user, tracker_secret=password).first()
    return user, track


@require_http_methods(["POST"])
@csrf_exempt
def ingress(request):
    if request.method != "POST":
        return error_response("Method Not Allowed", 405)
    username, password = _decode_basic_auth(request)
    if not username or not password:
        return error_response("Missing or invalid Basic Auth", 401)
    user, track = _resolve_user_and_track(username, password)
    if not track:
        return error_response("Invalid credentials", 401)

    cache_backend = getattr(settings, "CACHES", {}).get("default", {}).get("BACKEND", "")
    if "redis" in cache_backend.lower():
        rate_key = f"live_track_ingress:{track.id}"
        now_ts = timezone.now().timestamp()
        last = cache.get(rate_key)
        if last is not None and (now_ts - last) < 1.0:
            return error_response("Rate limit exceeded", 429)
        cache.set(rate_key, now_ts, timeout=2)

    raw = parse_ingress_body(request)
    try:
        body = LiveTrackIngressBody.model_validate(raw)
    except PydanticValidationError as e:
        errs = e.errors()
        msg = errs[0].get("msg", "Invalid body") if errs else "Invalid body"
        return error_response(msg, 400)

    timestamp_ms = parse_time_to_ms(raw)
    if timestamp_ms is None:
        timestamp_ms = int(timezone.now().timestamp() * 1000)

    max_points = get_config_loader().get_int("extensions.live_track.max_points", 1000)

    with transaction.atomic():
        track_locked = LiveTrack.objects.select_for_update().get(pk=track.id)
        geom = track_locked.geometry or {"type": "LineString", "coordinates": []}
        coords = list(geom.get("coordinates") or [])
        point_params = list(track_locked.point_params or [])

        if coords:
            last_ts = coords[-1][2] if len(coords[-1]) >= 3 else 0
            if timestamp_ms <= last_ts:
                return error_response("Point time must be after the last point", 400)

        new_point = [body.lon, body.lat, timestamp_ms]
        coords.append(new_point)
        extra = {k: v for k, v in body.model_dump().items() if k not in ("lat", "lon", "time") and v is not None}
        point_params.append(extra)

        if len(coords) > max_points:
            n = len(coords) - max_points
            coords = coords[n:]
            point_params = point_params[n:]

        track_locked.geometry = {"type": "LineString", "coordinates": coords}
        track_locked.point_params = point_params
        track_locked.save(update_fields=["geometry", "point_params", "updated_at"])

    broadcast_track_updated(user.id, str(track.id), new_point, extra)
    return JsonResponse({"ok": True}, status=200)


@require_http_methods(["POST"])
@csrf_exempt
def app_ingress(request):
    return error_response("Not implemented", 501)
