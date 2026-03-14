"""
Tracker CRUD and KML download views.
"""

import json
import re
import secrets
import uuid
from xml.etree import ElementTree as ET

from django.http import HttpResponse, JsonResponse
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_http_methods

from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response, handle_404
from geo_lib.website.auth import api_or_login_required_401

from pydantic import ValidationError as PydanticValidationError

from django.contrib.auth import get_user_model
from django.db.models import Q

from .helpers import (
    DEFAULT_TRACK_COLOR,
    _color_from_settings,
    _filter_coords_by_recent_window,
    _strip_ser_from_params,
    can_user_see_track,
    can_user_see_track_via_group,
    track_to_response,
    track_to_response_metadata_only,
)
from .models import (
    LiveTrack,
    LiveTrackGroupMember,
    LiveTrackWorldShare,
    LiveTrackShare,
    LiveTrackSubscription,
    VISIBILITY_PUBLIC,
    VISIBILITY_SHARED,
)
from .world_share_views import build_live_track_share_url
from .validation import (
    PARAM_PRETTY_NAMES,
    TrackerCheckRequest,
    TrackerCheckResponse,
    TrackSettingsRequest,
    get_ingress_body_template,
)


User = get_user_model()


def _get_json_body(request):
    """Parse request body as JSON. Returns (data, None) or (None, error_response)."""
    try:
        data = json.loads(request.body) if request.body else {}
        return data, None
    except json.JSONDecodeError:
        return None, error_response("Invalid JSON", 400)


def _get_track_for_user_or_404(user, tracker_id):
    """Return LiveTrack if user owns it, can see it (subscribed + visible), or can see it via group membership. Raises Http404 otherwise."""
    from django.http import Http404

    try:
        track = LiveTrack.objects.get(id=tracker_id)
    except (LiveTrack.DoesNotExist, ValueError):
        raise Http404
    if track.user_id == user.id:
        return track
    if can_user_see_track(user, track) and LiveTrackSubscription.objects.filter(user=user, track=track).exists():
        return track
    if can_user_see_track_via_group(user, track):
        return track
    raise Http404


@api_or_login_required_401()
@require_http_methods(["POST"])
@csrf_exempt
def tracker_check(request):
    """POST: Check a single tracker ID (and optionally password). Supports session, OAuth, and API auth."""
    data, err = _get_json_body(request)
    if err is not None:
        return err
    try:
        body = TrackerCheckRequest.model_validate(data or {})
    except PydanticValidationError as e:
        errs = e.errors()
        msg = errs[0].get("msg", "Invalid body") if errs else "Invalid body"
        return error_response(msg, 400)
    try:
        tracker_uuid = uuid.UUID(body.tracker_id)
    except (ValueError, TypeError):
        return error_response("Invalid tracker_id", 400)
    track = (
        LiveTrack.objects.filter(id=tracker_uuid, user=request.user)
        .only("id", "tracker_secret", "name")
        .first()
    )
    if not track:
        return JsonResponse(TrackerCheckResponse(valid=False).model_dump())
    if body.password is not None and track.tracker_secret != body.password:
        return JsonResponse(TrackerCheckResponse(valid=False).model_dump())
    return JsonResponse(
        TrackerCheckResponse(valid=True, name=track.name).model_dump()
    )


@api_or_login_required_401()
@require_http_methods(["GET", "POST"])
@csrf_exempt
def tracker_list_create(request):
    if request.method == "GET":
        owned = list(LiveTrack.objects.filter(user=request.user).order_by("name"))
        subs = (
            LiveTrackSubscription.objects.filter(user=request.user)
            .select_related("track", "track__user")
            .exclude(track__user=request.user)
        )
        visible_subscribed = []
        for sub in subs:
            t = sub.track
            if t.visibility == VISIBILITY_PUBLIC:
                visible_subscribed.append((t, False))
            elif t.visibility == VISIBILITY_SHARED and LiveTrackShare.objects.filter(
                track=t, shared_with=request.user
            ).exists():
                visible_subscribed.append((t, False))
        out = []
        for t in owned:
            payload = track_to_response_metadata_only(t, include_secret=False, is_owner=True)
            payload["is_owner"] = True
            world_share = LiveTrackWorldShare.objects.filter(track=t).first()
            if world_share:
                payload["world_share_id"] = world_share.share_id
                payload["world_share_url"] = build_live_track_share_url(world_share.share_id)
            out.append(payload)
        for t, _ in visible_subscribed:
            payload = track_to_response_metadata_only(t, include_secret=False, is_owner=False)
            payload["is_owner"] = False
            payload["owner_email"] = (t.user.email or "") if t.user_id else ""
            payload["visibility"] = t.visibility
            out.append(payload)
        existing_ids = {str(p["id"]) for p in out}
        group_track_ids = set(
            LiveTrackGroupMember.objects.filter(
                group__user_members__user=request.user
            ).values_list("track_id", flat=True)
        )
        for track in LiveTrack.objects.filter(id__in=group_track_ids).select_related("user"):
            if str(track.id) in existing_ids:
                continue
            if not can_user_see_track_via_group(request.user, track):
                continue
            payload = track_to_response_metadata_only(track, include_secret=False, is_owner=False)
            payload["is_owner"] = False
            payload["owner_email"] = (track.user.email or "") if track.user_id else ""
            payload["visibility"] = track.visibility
            out.append(payload)
            existing_ids.add(str(track.id))
        out.sort(key=lambda x: (x.get("name") or "").lower())
        return JsonResponse(out, safe=False)

    data, err = _get_json_body(request)
    if err is not None:
        return err
    name = (data.get("name") or "").strip()
    if not name:
        return error_response("name is required", 400)
    color = (data.get("color") or "").strip() or DEFAULT_TRACK_COLOR
    if LiveTrack.objects.filter(user=request.user, name=name).exists():
        return error_response("A track with this name already exists", 409)
    tracker_secret = secrets.token_urlsafe(32)
    track_id = uuid.uuid4()
    track = LiveTrack.objects.create(
        id=track_id,
        tracker_secret=tracker_secret,
        name=name,
        user=request.user,
        settings={"color": color},
    )
    return JsonResponse(track_to_response(track, include_secret=True), status=201)


@api_or_login_required_401()
@require_http_methods(["GET", "DELETE"])
@handle_404
@csrf_exempt
def tracker_get_patch_delete(request, tracker_id):
    track = _get_track_for_user_or_404(request.user, tracker_id)
    is_owner = track.user_id == request.user.id
    if request.method == "GET":
        resp = track_to_response_metadata_only(track, include_secret=is_owner, is_owner=is_owner)
        coords = list((track.geometry or {}).get("coordinates") or [])
        take = min(LATEST_COORDINATES_LIMIT, len(coords))
        latest = coords[-take:] if take else []
        resp["geometry"] = {"type": "LineString", "coordinates": latest}
        resp["is_owner"] = is_owner
        if not is_owner:
            resp["owner_email"] = (track.user.email or "") if track.user_id else ""
        if is_owner:
            world_share = LiveTrackWorldShare.objects.filter(track=track).first()
            if world_share:
                resp["world_share_id"] = world_share.share_id
                resp["world_share_url"] = build_live_track_share_url(world_share.share_id)
        return JsonResponse(resp)
    if request.method == "DELETE":
        if not is_owner:
            return error_response("Only the owner can delete this tracker", 403)
        track.delete()
        return JsonResponse({"message": "Deleted"}, status=204)
    return error_response("Method not allowed", 405)


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
@csrf_exempt
def tracker_post_settings(request, tracker_id):
    """POST trackers/<id>/settings/ — update name, color, recent_data_window, visibility, share_params, shared_with_emails. Owner only."""
    track = get_object_or_404_for_user(LiveTrack, request.user, id=tracker_id)
    data, err = _get_json_body(request)
    if err is not None:
        return err
    try:
        body = TrackSettingsRequest.model_validate(data or {})
    except PydanticValidationError as e:
        errs = e.errors()
        msg = errs[0].get("msg", "Invalid body") if errs else "Invalid body"
        return error_response(msg, 400)
    update_fields = []
    if body.name is not None:
        name = body.name.strip()
        if not name:
            return error_response("name cannot be empty", 400)
        if LiveTrack.objects.filter(user=request.user, name=name).exclude(id=track.id).exists():
            return error_response("A track with this name already exists", 409)
        track.name = name
        update_fields.append("name")
    # Settings JSON: color, recent_data_window only (visibility etc. are model fields)
    settings_keys = {"color", "recent_data_window"}
    settings_dump = {k: v for k, v in body.model_dump(exclude_unset=True).items() if k in settings_keys}
    if settings_dump:
        new_settings = {**(track.settings or {})}
        for k, v in settings_dump.items():
            if v is None:
                new_settings.pop(k, None)
            else:
                new_settings[k] = v
        track.settings = new_settings
        update_fields.append("settings")
    if body.visibility is not None:
        track.visibility = body.visibility
        update_fields.append("visibility")
    if body.share_params_with_recipients is not None:
        track.share_params_with_recipients = body.share_params_with_recipients
        update_fields.append("share_params_with_recipients")
    if body.share_params_with_world is not None:
        track.share_params_with_world = body.share_params_with_world
        update_fields.append("share_params_with_world")
    if body.shared_with_emails is not None:
        if track.visibility != VISIBILITY_SHARED:
            return error_response("shared_with_emails only applies when visibility is shared", 400)
        emails = [e.strip().lower() for e in body.shared_with_emails if (e or "").strip()]
        q = Q()
        for e in emails:
            q |= Q(email__iexact=e)
        users = User.objects.filter(q)
        users_by_email = {u.email.lower(): u for u in users if u.email}
        invalid = [e for e in emails if e not in users_by_email]
        if invalid:
            return JsonResponse({"error": "Invalid emails", "invalid_emails": invalid}, status=400)
        target_users = set(users_by_email[e] for e in emails)
        current = set(LiveTrackShare.objects.filter(track=track).values_list("shared_with_id", flat=True))
        to_add = target_users - {u for u in target_users if u.id in current}
        to_remove = current - {u.id for u in target_users}
        for u in to_add:
            LiveTrackShare.objects.get_or_create(track=track, shared_with=u)
        LiveTrackShare.objects.filter(track=track, shared_with_id__in=to_remove).delete()
    if body.world_share_enabled is not None:
        if body.world_share_enabled:
            share, _ = LiveTrackWorldShare.objects.get_or_create(
                track=track,
                defaults={"share_id": str(uuid.uuid4())},
            )
        else:
            LiveTrackWorldShare.objects.filter(track=track).delete()
    if update_fields:
        update_fields.append("updated_at")
        track.save(update_fields=update_fields)
    resp = track_to_response_metadata_only(track, include_secret=True, is_owner=True)
    world_share = LiveTrackWorldShare.objects.filter(track=track).first()
    if world_share:
        resp["world_share_id"] = world_share.share_id
        resp["world_share_url"] = build_live_track_share_url(world_share.share_id)
    return JsonResponse(resp)


@api_or_login_required_401()
@require_http_methods(["GET"])
@handle_404
@csrf_exempt
def tracker_get_geometry(request, tracker_id):
    """GET trackers/<id>/geometry/ — full geometry + all point_params (for map, params table, etc.). ?all=true bypasses recent_data_window filter."""
    track = _get_track_for_user_or_404(request.user, tracker_id)
    is_owner = track.user_id == request.user.id
    all_data = request.GET.get("all", "").lower() == "true"
    return JsonResponse(track_to_response(track, include_secret=False, is_owner=is_owner, all_data=all_data))


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
@csrf_exempt
def tracker_clear_history(request, tracker_id):
    """POST trackers/<id>/clear-history/ — keep only the latest point (or none if empty). Owner only."""
    track = get_object_or_404_for_user(LiveTrack, request.user, id=tracker_id)
    geom = track.geometry or {"type": "LineString", "coordinates": []}
    coords = geom.get("coordinates") or []
    point_params = track.point_params or []
    new_coords = [coords[-1]] if coords else []
    new_params = [point_params[-1]] if point_params else []
    track.geometry = {"type": "LineString", "coordinates": new_coords}
    track.point_params = new_params
    track.save(update_fields=["geometry", "point_params", "updated_at"])
    return JsonResponse(track_to_response_metadata_only(track, include_secret=False), status=200)


LATEST_COORDINATES_LIMIT = 100


@api_or_login_required_401()
@require_http_methods(["GET"])
@handle_404
@csrf_exempt
def tracker_get_latest_coordinates(request, tracker_id):
    """GET trackers/<id>/coordinates/ — latest 100 coordinates + corresponding point_params. ?all=true bypasses recent_data_window filter."""
    track = _get_track_for_user_or_404(request.user, tracker_id)
    is_owner = track.user_id == request.user.id
    geom = track.geometry or {"type": "LineString", "coordinates": []}
    coords = list(geom.get("coordinates") or [])
    point_params = list(track.point_params or [])
    all_data = request.GET.get("all", "").lower() == "true"
    window_key = None if all_data else (track.settings or {}).get("recent_data_window")
    if window_key:
        coords, point_params = _filter_coords_by_recent_window(coords, point_params, window_key)
    take = min(LATEST_COORDINATES_LIMIT, len(coords))
    latest_coords = coords[-take:] if take else []
    latest_params = point_params[-take:] if take else []
    if not is_owner:
        if not getattr(track, "share_params_with_recipients", False):
            latest_params = []
        else:
            latest_params = [dict(p) for p in latest_params]
            _strip_ser_from_params(latest_params)
    return JsonResponse({
        "coordinates": latest_coords,
        "point_params": latest_params,
    })


@require_http_methods(["GET"])
@csrf_exempt
def tracker_profile_properties(request, tracker_id, profile_basename=None):
    """GET profile.properties: session owner or ?secret=tracker_secret. Returns GPSLogger .properties file.
    If profile_basename is in the URL (e.g. GeoVault%20My%20Track.properties), that name is used so
    GPSLogger's getBaseName(url) shows the correct profile name after import."""
    track = LiveTrack.objects.filter(id=tracker_id).first()
    if not track:
        return error_response("Not found", 404)
    allowed = (
        (request.user.is_authenticated and request.user == track.user)
        or (request.GET.get("secret") == track.tracker_secret)
    )
    if not allowed:
        return error_response("Not found", 404)
    # Use Host / X-Forwarded-Host so profile URLs match what the client sees (e.g. 192.168.1.235:5173 not 127.0.0.1:8000)
    forwarded_host = request.META.get("HTTP_X_FORWARDED_HOST")
    host = (forwarded_host or request.META.get("HTTP_HOST") or request.get_host()).split(",")[0].strip()
    scheme = request.META.get("HTTP_X_FORWARDED_PROTO") or request.scheme or "http"
    # Ingress URL: replace .../trackers/<id>/<anything>.properties with .../ingress/
    ingress_path = re.sub(r"/trackers/[^/]+/[^/]+\.properties$", "/ingress/", request.path)
    ingress_url = f"{scheme}://{host}{ingress_path}"
    # Body template uses our param names (e.g. bearing=%BEARING); GPSLogger substitutes placeholders.
    body_template = get_ingress_body_template()
    username = (track.user.email or "").strip()
    if profile_basename and profile_basename.strip():
        profile_display_name = profile_basename.strip()[:50]
    else:
        raw_name = "".join(c for c in (track.name or "track") if c.isalnum() or c in " -_")[:41]
        profile_display_name = f"GeoVault {raw_name}".strip() or "GeoVault"
    lines = [
        f"current_profile_name={profile_display_name}",
        "log_customurl_enabled=true",
        f"log_customurl_url={ingress_url}",
        f"log_customurl_body={body_template}",
        "log_customurl_method=POST",
        f"log_customurl_basicauth_username={username}",
        f"log_customurl_basicauth_password={track.tracker_secret}",
        "log_customurl_discard_offline_locations_enabled=true",
        "autocustomurl_enabled=true",
        "hide_notification_from_lock_screen=true",
        "log_satellite_locations=true",
        "log_network_locations=true",
        "new_file_creation=everystart",
        "time_before_logging=15",
        "distance_before_logging=10",
        "accuracy_before_logging=50",
    ]
    body = "\n".join(lines) + "\n"
    resp = HttpResponse(body, content_type="application/x-gpslogger-properties")
    resp["Content-Disposition"] = f'inline; filename="{profile_display_name}.properties"'
    return resp


@api_or_login_required_401()
@require_http_methods(["GET"])
@csrf_exempt
def ingress_body_template(request):
    """Return the form body template and param pretty names (for GPSLogger config and params table)."""
    return JsonResponse({
        "body_template": get_ingress_body_template(),
        "param_labels": PARAM_PRETTY_NAMES,
    })


@api_or_login_required_401()
@require_http_methods(["GET"])
@handle_404
@csrf_exempt
def tracker_kml(request, tracker_id):
    """GET trackers/<id>/kml/. ?all=true bypasses recent_data_window filter. Owner or subscriber."""
    track = _get_track_for_user_or_404(request.user, tracker_id)
    geom = track.geometry or {"type": "LineString", "coordinates": []}
    coords = list(geom.get("coordinates") or [])
    point_params = list(track.point_params or [])
    all_data = request.GET.get("all", "").lower() == "true"
    window_key = None if all_data else (track.settings or {}).get("recent_data_window")
    if window_key:
        coords, _ = _filter_coords_by_recent_window(coords, point_params, window_key)
    ns = "http://www.opengis.net/kml/2.2"
    ET.register_namespace("", ns)
    kml = ET.Element(ET.QName(ns, "kml"))
    doc = ET.SubElement(kml, ET.QName(ns, "Document"))
    name_el = ET.SubElement(doc, ET.QName(ns, "name"))
    name_el.text = track.name or "Tracker"
    pm = ET.SubElement(doc, ET.QName(ns, "Placemark"))
    pm_name = ET.SubElement(pm, ET.QName(ns, "name"))
    pm_name.text = track.name or "Tracker"
    ls = ET.SubElement(pm, ET.QName(ns, "LineString"))
    coord_el = ET.SubElement(ls, ET.QName(ns, "coordinates"))
    coord_el.text = " ".join(f"{c[0]},{c[1]},0" for c in coords)
    xml_bytes = ET.tostring(kml, encoding="utf-8", xml_declaration=True)
    resp = HttpResponse(xml_bytes, content_type="application/vnd.google-earth.kml+xml")
    safe_name = "".join(c for c in (track.name or "track") if c.isalnum() or c in " -_")[:50]
    resp["Content-Disposition"] = f'attachment; filename="{safe_name}.kml"'
    return resp


@api_or_login_required_401()
@require_http_methods(["POST", "DELETE"])
@handle_404
@csrf_exempt
def tracker_subscribe_delete(request, tracker_id):
    """POST: subscribe (add track to list). DELETE: unsubscribe and remove from all groups the user owns."""
    from django.http import Http404

    try:
        track = LiveTrack.objects.get(id=tracker_id)
    except (LiveTrack.DoesNotExist, ValueError):
        raise Http404
    if request.method == "POST":
        if track.user_id == request.user.id:
            return JsonResponse({"error": "You already own this tracker"}, status=400)
        if not can_user_see_track(request.user, track):
            return error_response("You do not have access to this tracker", 403)
        LiveTrackSubscription.objects.get_or_create(user=request.user, track=track)
        return JsonResponse(
            track_to_response_metadata_only(track, include_secret=False, is_owner=False),
            status=201,
        )
    if request.method == "DELETE":
        if track.user_id == request.user.id:
            return error_response("Cannot unsubscribe from your own tracker", 400)
        sub = LiveTrackSubscription.objects.filter(user=request.user, track=track).first()
        if not sub:
            return error_response("Not subscribed", 404)
        sub.delete()
        LiveTrackGroupMember.objects.filter(group__user=request.user, track=track).delete()
        return JsonResponse({}, status=204)
    return error_response("Method not allowed", 405)


@api_or_login_required_401()
@require_http_methods(["DELETE"])
@handle_404
@csrf_exempt
def tracker_leave_share(request, tracker_id):
    """DELETE trackers/<id>/share-with-me/ — Remove yourself from a share. Deletes the share entry (owner no longer has you as recipient) and your subscription. Only for tracks that are shared with you (visibility=shared and you are in shared_with)."""
    try:
        track = LiveTrack.objects.get(id=tracker_id)
    except (LiveTrack.DoesNotExist, ValueError):
        from django.http import Http404
        raise Http404
    if track.user_id == request.user.id:
        return error_response("You cannot leave a share on your own tracker", 400)
    share_entry = LiveTrackShare.objects.filter(track=track, shared_with=request.user).first()
    if not share_entry:
        return error_response("This tracker is not shared with you", 404)
    share_entry.delete()
    LiveTrackSubscription.objects.filter(user=request.user, track=track).delete()
    LiveTrackGroupMember.objects.filter(group__user=request.user, track=track).delete()
    return JsonResponse({}, status=204)


@api_or_login_required_401()
@require_http_methods(["GET"])
@csrf_exempt
def tracker_available_to_add(request):
    """GET trackers/available-to-add/ — trackers the user can add (public = all auth users, or shared with me) and does not yet have."""
    owned_ids = set(LiveTrack.objects.filter(user=request.user).values_list("id", flat=True))
    subscribed_ids = set(
        LiveTrackSubscription.objects.filter(user=request.user).values_list("track_id", flat=True)
    )
    have_ids = owned_ids | subscribed_ids
    public = list(
        LiveTrack.objects.filter(visibility=VISIBILITY_PUBLIC)
        .exclude(user=request.user)
        .exclude(id__in=have_ids)
        .select_related("user")
        .order_by("name")
    )
    shared_with_me = list(
        LiveTrack.objects.filter(
            visibility=VISIBILITY_SHARED,
            share_entries__shared_with=request.user,
        )
        .exclude(id__in=have_ids)
        .select_related("user")
        .distinct()
        .order_by("name")
    )
    def item(t):
        return {
            "id": str(t.id),
            "name": t.name,
            "color": _color_from_settings(t),
            "owner_email": (t.user.email or "") if t.user_id else "",
        }

    return JsonResponse({
        "public": [item(t) for t in public],
        "shared_with_me": [item(t) for t in shared_with_me],
    })
