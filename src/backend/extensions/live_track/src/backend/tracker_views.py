"""
Tracker CRUD and KML download views.
"""

import json
import secrets
import uuid
from xml.etree import ElementTree as ET

from django.http import HttpResponse, JsonResponse
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_http_methods

from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response, handle_404
from geo_lib.website.auth import api_or_login_required_401

from .helpers import track_to_response
from .models import LiveTrack


@api_or_login_required_401()
@require_http_methods(["GET", "POST"])
@csrf_exempt
def tracker_list_create(request):
    if request.method == "GET":
        tracks = LiveTrack.objects.filter(user=request.user).order_by("name")
        return JsonResponse([track_to_response(t) for t in tracks], safe=False)

    try:
        data = json.loads(request.body) if request.body else {}
    except json.JSONDecodeError:
        return error_response("Invalid JSON", 400)
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
    return JsonResponse(track_to_response(track), status=201)


@api_or_login_required_401()
@require_http_methods(["GET", "PATCH", "DELETE"])
@handle_404
@csrf_exempt
def tracker_get_patch_delete(request, tracker_id):
    track = get_object_or_404_for_user(LiveTrack, request.user, id=tracker_id)
    if request.method == "GET":
        return JsonResponse(track_to_response(track))
    if request.method == "DELETE":
        track.delete()
        return JsonResponse({"message": "Deleted"}, status=204)
    try:
        data = json.loads(request.body) if request.body else {}
    except json.JSONDecodeError:
        return error_response("Invalid JSON", 400)
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
    return JsonResponse(track_to_response(track))


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
