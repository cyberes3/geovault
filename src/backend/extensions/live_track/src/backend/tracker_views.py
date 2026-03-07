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

from .helpers import track_to_response, track_to_response_metadata_only
from .models import LiveTrack
from .validation import (
    PARAM_PRETTY_NAMES,
    TrackerCheckRequest,
    TrackerCheckResponse,
    get_ingress_body_template,
)


def _get_json_body(request):
    """Parse request body as JSON. Returns (data, None) or (None, error_response)."""
    try:
        data = json.loads(request.body) if request.body else {}
        return data, None
    except json.JSONDecodeError:
        return None, error_response("Invalid JSON", 400)


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
        tracks = LiveTrack.objects.filter(user=request.user).order_by("name")
        return JsonResponse([track_to_response_metadata_only(t, include_secret=False) for t in tracks], safe=False)

    data, err = _get_json_body(request)
    if err is not None:
        return err
    name = (data.get("name") or "").strip()
    if not name:
        return error_response("name is required", 400)
    color = (data.get("color") or "").strip() or "#3388ff"
    if LiveTrack.objects.filter(user=request.user, name=name).exists():
        return error_response("A track with this name already exists", 409)
    tracker_secret = secrets.token_urlsafe(32)
    track_id = uuid.uuid4()
    track = LiveTrack.objects.create(
        id=track_id,
        tracker_secret=tracker_secret,
        name=name,
        user=request.user,
        color=color,
    )
    return JsonResponse(track_to_response(track, include_secret=True), status=201)


@api_or_login_required_401()
@require_http_methods(["GET", "PATCH", "DELETE"])
@handle_404
@csrf_exempt
def tracker_get_patch_delete(request, tracker_id):
    track = get_object_or_404_for_user(LiveTrack, request.user, id=tracker_id)
    if request.method == "GET":
        return JsonResponse(track_to_response_metadata_only(track, include_secret=True))
    if request.method == "DELETE":
        track.delete()
        return JsonResponse({"message": "Deleted"}, status=204)
    data, err = _get_json_body(request)
    if err is not None:
        return err
    if "name" in data:
        name = (data["name"] or "").strip()
        if not name:
            return error_response("name cannot be empty", 400)
        if LiveTrack.objects.filter(user=request.user, name=name).exclude(id=track.id).exists():
            return error_response("A track with this name already exists", 409)
        track.name = name
    if "color" in data and data["color"]:
        track.color = data["color"].strip()
    track.save()
    return JsonResponse(track_to_response_metadata_only(track, include_secret=True))


@api_or_login_required_401()
@require_http_methods(["GET"])
@handle_404
@csrf_exempt
def tracker_get_geometry(request, tracker_id):
    """GET trackers/<id>/geometry/ — full geometry + all point_params (for map, params table, etc.)."""
    track = get_object_or_404_for_user(LiveTrack, request.user, id=tracker_id)
    return JsonResponse(track_to_response(track, include_secret=False))


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
@csrf_exempt
def tracker_clear_history(request, tracker_id):
    """POST trackers/<id>/clear-history/ — keep only the latest point (or none if empty)."""
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
    """GET trackers/<id>/coordinates/ — latest 100 coordinates + corresponding point_params."""
    track = get_object_or_404_for_user(LiveTrack, request.user, id=tracker_id)
    geom = track.geometry or {"type": "LineString", "coordinates": []}
    coords = geom.get("coordinates") or []
    point_params = track.point_params or []
    # Take last N; point_params[i] corresponds to coords[i]
    take = min(LATEST_COORDINATES_LIMIT, len(coords))
    latest_coords = coords[-take:] if take else []
    latest_params = point_params[-take:] if take else []
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
    track = get_object_or_404_for_user(LiveTrack, request.user, id=tracker_id)
    geom = track.geometry or {"type": "LineString", "coordinates": []}
    coords = geom.get("coordinates") or []
    ns = "http://www.opengis.net/kml/2.2"
    ET.register_namespace("", ns)
    kml = ET.Element(ET.QName(ns, "kml"))
    doc = ET.SubElement(kml, ET.QName(ns, "Document"))
    name_el = ET.SubElement(doc, ET.QName(ns, "name"))
    name_el.text = track.name or "Track"
    pm = ET.SubElement(doc, ET.QName(ns, "Placemark"))
    pm_name = ET.SubElement(pm, ET.QName(ns, "name"))
    pm_name.text = track.name or "Track"
    ls = ET.SubElement(pm, ET.QName(ns, "LineString"))
    coord_el = ET.SubElement(ls, ET.QName(ns, "coordinates"))
    coord_el.text = " ".join(f"{c[0]},{c[1]},0" for c in coords)
    xml_bytes = ET.tostring(kml, encoding="utf-8", xml_declaration=True)
    resp = HttpResponse(xml_bytes, content_type="application/vnd.google-earth.kml+xml")
    safe_name = "".join(c for c in (track.name or "track") if c.isalnum() or c in " -_")[:50]
    resp["Content-Disposition"] = f'attachment; filename="{safe_name}.kml"'
    return resp
