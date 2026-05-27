"""
Tracker CRUD and KML download views.
"""

import json
import re
import secrets
import uuid
from xml.etree import ElementTree as ET

from django.core.serializers.json import DjangoJSONEncoder
from django.http import HttpResponse, JsonResponse
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_http_methods

from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response, handle_404
from geo_lib.website.auth import api_or_login_required_401
from website.config_loader import get_config_loader
from website.public_url import build_public_url

from pydantic import ValidationError as PydanticValidationError

from django.contrib.auth import get_user_model
from django.db.models import Count, F, Q
from django.utils import timezone

from .helpers import (
    DEFAULT_TRACK_COLOR,
    _color_from_settings,
    normalize_track_settings_for_api,
    _filter_coords_by_recent_window,
    _strip_ser_from_params,
    accepted_group_track_ids_for_user,
    can_user_see_track,
    can_user_see_track_via_accepted_group_share,
    can_user_see_track_via_owned_group_membership,
    can_user_see_track_via_group_share,
    generate_hauk_password,
    get_json_body,
    track_to_response,
    track_to_response_metadata_only,
)
from .models import (
    LiveTrack,
    LiveTrackGroup,
    LiveTrackGroupMember,
    LiveTrackMapVisibilityPrefs,
    LiveTrackWorldShare,
    LiveTrackShare,
    LiveTrackSubscription,
    VISIBILITY_PRIVATE,
    VISIBILITY_PUBLIC,
    VISIBILITY_SHARED,
)
from .internal_share_links import (
    build_live_track_internal_share_url,
    sync_track_internal_share,
    visible_track_internal_share_for_user,
)
from .world_share_views import build_live_track_share_url
from .validation import (
    PARAM_PRETTY_NAMES,
    AvailableToAddGroupResponse,
    AvailableToAddItemResponse,
    AvailableToAddResponse,
    HiddenItemsClearRequest,
    MapVisibilityPrefsRequest,
    RegenerateTrackerTokensResponse,
    TrackerListItemResponse,
    TrackerBulkGeometryRequest,
    TrackerCheckRequest,
    TrackerCheckResponse,
    TrackSettingsRequest,
    get_ingress_body_template,
)


User = get_user_model()


@api_or_login_required_401()
@require_http_methods(["POST"])
@csrf_exempt
def hidden_items_clear(request):
    """
    POST hidden-items/clear/ — clear hidden tracker/group flags for the requesting owner.
    Contract:
    - omitted target_types => clear both trackers and groups
    - scoped clear supports trackers-only or groups-only
    - idempotent and owner-only; unrelated fields are not modified
    """
    data, err = get_json_body(request)
    if err is not None:
        return err
    try:
        body = HiddenItemsClearRequest.model_validate(data or {})
    except PydanticValidationError as e:
        errs = e.errors()
        msg = errs[0].get("msg", "Invalid body") if errs else "Invalid body"
        return error_response(msg, 400)

    target_types = set(body.target_types or ["trackers", "groups"])

    if "trackers" in target_types:
        tracks = LiveTrack.objects.filter(user=request.user).only("id", "settings")
        for track in tracks:
            settings = normalize_track_settings_for_api({**(track.settings or {})})
            if "hidden" not in settings:
                continue
            settings.pop("hidden", None)
            track.settings = normalize_track_settings_for_api(settings)
            track.save(update_fields=["settings"])

    if "groups" in target_types:
        LiveTrackGroup.objects.filter(user=request.user, hidden=True).update(
            hidden=False,
            updated_at=timezone.now(),
        )

    return HttpResponse(status=204)


def _json_size_bytes(payload: dict) -> int:
    encoded = json.dumps(
        payload,
        cls=DjangoJSONEncoder,
        separators=(",", ":"),
        ensure_ascii=True,
    ).encode("utf-8")
    return len(encoded)


def _fit_tail_count_to_max_bytes(
    base_payload: dict,
    coords: list,
    point_params: list,
    max_bytes: int,
    params_align_with_coords: bool,
) -> int:
    """Return largest tail length that fits within max_bytes using O(n) prep + O(log n) checks."""
    n_coords = len(coords)
    if max_bytes <= 0 or n_coords == 0:
        return n_coords

    # Compact base payload with empty arrays and null bbox.
    empty_payload = dict(base_payload)
    empty_payload["geometry"] = {"type": "LineString", "coordinates": []}
    if params_align_with_coords:
        empty_payload["point_params"] = []
    empty_payload["bbox"] = None
    base_size = _json_size_bytes(empty_payload)

    coord_sizes = [
        len(json.dumps(c, separators=(",", ":"), ensure_ascii=True))
        for c in coords
    ]
    suffix_coord_sum = [0] * (n_coords + 1)
    for i in range(n_coords - 1, -1, -1):
        suffix_coord_sum[i] = suffix_coord_sum[i + 1] + coord_sizes[i]

    suffix_param_sum = None
    if params_align_with_coords:
        param_sizes = [
            len(json.dumps(p, separators=(",", ":"), ensure_ascii=True))
            for p in point_params
        ]
        suffix_param_sum = [0] * (n_coords + 1)
        for i in range(n_coords - 1, -1, -1):
            suffix_param_sum[i] = suffix_param_sum[i + 1] + param_sizes[i]

    suffix_min_lon = [0.0] * n_coords
    suffix_max_lon = [0.0] * n_coords
    suffix_min_lat = [0.0] * n_coords
    suffix_max_lat = [0.0] * n_coords
    for i in range(n_coords - 1, -1, -1):
        lon = coords[i][0]
        lat = coords[i][1]
        if i == n_coords - 1:
            suffix_min_lon[i] = lon
            suffix_max_lon[i] = lon
            suffix_min_lat[i] = lat
            suffix_max_lat[i] = lat
            continue
        suffix_min_lon[i] = min(lon, suffix_min_lon[i + 1])
        suffix_max_lon[i] = max(lon, suffix_max_lon[i + 1])
        suffix_min_lat[i] = min(lat, suffix_min_lat[i + 1])
        suffix_max_lat[i] = max(lat, suffix_max_lat[i + 1])

    def total_size_for_k(k: int) -> int:
        if k <= 0:
            return base_size
        start = n_coords - k
        # [] already counted in base payload as 2 bytes.
        coords_array_size = 2 + suffix_coord_sum[start] + (k - 1)
        total = base_size + (coords_array_size - 2)

        if params_align_with_coords and suffix_param_sum is not None:
            params_array_size = 2 + suffix_param_sum[start] + (k - 1)
            total += (params_array_size - 2)

        bbox = [
            round(suffix_min_lon[start], 5),
            round(suffix_min_lat[start], 5),
            round(suffix_max_lon[start], 5),
            round(suffix_max_lat[start], 5),
        ]
        bbox_size = len(json.dumps(bbox, separators=(",", ":"), ensure_ascii=True))
        # null (4 bytes) already counted in base payload
        total += (bbox_size - 4)
        return total

    lo = 0
    hi = n_coords
    best = 0
    while lo <= hi:
        mid = (lo + hi) // 2
        if total_size_for_k(mid) <= max_bytes:
            best = mid
            lo = mid + 1
        else:
            hi = mid - 1
    return best


def _normalize_coords_for_response(coords: list) -> list:
    rounded_coords = []
    for c in coords:
        if len(c) >= 2:
            pt = [round(c[0], 5), round(c[1], 5)]
            if len(c) >= 3 and isinstance(c[2], (int, float)):
                pt.append(int(round(c[2])))
            elif len(c) >= 3:
                pt.append(c[2])
            if len(c) > 3:
                pt.extend(c[3:])
            rounded_coords.append(pt)
        else:
            rounded_coords.append(c)
    return rounded_coords


def _normalize_point_params_for_response(point_params: list) -> list:
    normalized = []
    for p in point_params:
        entry = dict(p) if isinstance(p, dict) else {}
        if "acc" in entry and isinstance(entry["acc"], (int, float)):
            entry["acc"] = round(float(entry["acc"]), 1)
        if "alt" in entry and isinstance(entry["alt"], (int, float)):
            entry["alt"] = int(round(float(entry["alt"])))
        for k, v in list(entry.items()):
            if not isinstance(v, (int, float)):
                continue
            key = k.lower()
            if key == "starttimestamp":
                entry[k] = int(round(v))
            elif "timestamp" in key:
                if v > 1e11:
                    entry[k] = int(round(v / 1000.0))
                else:
                    entry[k] = int(round(v))
        normalized.append(entry)
    return normalized


def _bbox_from_normalized_coords(coords: list) -> list | None:
    if not coords:
        return None
    lons = [c[0] for c in coords if isinstance(c, list) and len(c) >= 2]
    lats = [c[1] for c in coords if isinstance(c, list) and len(c) >= 2]
    if not lons or not lats:
        return None
    return [round(min(lons), 5), round(min(lats), 5), round(max(lons), 5), round(max(lats), 5)]


def _bounded_track_geometry_payload(track: LiveTrack, is_owner: bool, max_bytes: int) -> dict:
    """Build tracker geometry response trimmed to max_bytes (newest points retained)."""
    geom = track.geometry or {"type": "LineString", "coordinates": []}
    coords = list(geom.get("coordinates") or [])
    point_params = list(track.point_params or [])

    window_key = (track.settings or {}).get("recent_data_window")
    if window_key:
        coords, point_params = _filter_coords_by_recent_window(coords, point_params, window_key)

    if not is_owner:
        if not getattr(track, "share_params_with_recipients", False):
            point_params = []
        else:
            point_params = [dict(p) if isinstance(p, dict) else {} for p in point_params]
            _strip_ser_from_params(point_params)

    response_payload = {
        "id": str(track.id),
        "name": track.name,
        "color": _color_from_settings(track),
        "settings": normalize_track_settings_for_api(track.settings),
        "visibility": getattr(track, "visibility", "private"),
        "share_params_with_recipients": getattr(track, "share_params_with_recipients", False),
        "is_owner": is_owner,
        "created_at": int(track.created_at.timestamp()) if track.created_at else None,
        "updated_at": int(track.updated_at.timestamp()) if track.updated_at else None,
    }
    if not is_owner:
        owner_email = (
            (getattr(track.user, "email", "") or "")
            if getattr(track, "user_id", None)
            else ""
        )
        response_payload["owner_email"] = owner_email.strip()
    if is_owner and getattr(track, "hauk_password", None):
        response_payload["hauk_password"] = track.hauk_password
        emails = list(
            LiveTrackShare.objects.filter(track=track).values_list("shared_with__email", flat=True)
        )
        response_payload["shared_with_emails"] = [e for e in emails if e]

    params_align_with_coords = len(point_params) == len(coords)
    take_last = _fit_tail_count_to_max_bytes(
        response_payload,
        coords,
        point_params,
        max_bytes,
        params_align_with_coords,
    )
    selected_coords = coords[-take_last:] if take_last > 0 else []
    if params_align_with_coords:
        selected_params = point_params[-take_last:] if take_last > 0 else []
    else:
        selected_params = point_params

    normalized_coords = _normalize_coords_for_response(selected_coords)
    normalized_params = _normalize_point_params_for_response(selected_params)
    response_payload["geometry"] = {"type": "LineString", "coordinates": normalized_coords}
    response_payload["point_params"] = normalized_params
    response_payload["bbox"] = _bbox_from_normalized_coords(normalized_coords)

    while (
        normalized_coords
        and _json_size_bytes(response_payload) > max_bytes
    ):
        normalized_coords.pop(0)
        if params_align_with_coords and normalized_params:
            normalized_params.pop(0)
        response_payload["geometry"]["coordinates"] = normalized_coords
        response_payload["point_params"] = normalized_params
        response_payload["bbox"] = _bbox_from_normalized_coords(normalized_coords)

    return response_payload


def _get_track_for_user_or_404(user, tracker_id):
    """Return LiveTrack for owners, track subscribers (direct/public), or accepted group shares; raise Http404 otherwise."""
    from django.http import Http404

    try:
        track = LiveTrack.objects.get(id=tracker_id)
    except (LiveTrack.DoesNotExist, ValueError):
        raise Http404
    if track.user_id == user.id:
        return track
    if can_user_see_track(user, track):
        return track
    has_track_subscription = LiveTrackSubscription.objects.filter(user=user, track=track).exists()
    if has_track_subscription and can_user_see_track(user, track):
        return track
    if can_user_see_track_via_accepted_group_share(user, track):
        return track
    if can_user_see_track_via_owned_group_membership(user, track):
        return track
    raise Http404


@api_or_login_required_401()
@require_http_methods(["POST"])
@csrf_exempt
def tracker_check(request):
    """POST: Check a single tracker ID (and optionally password). Supports session, OAuth, and API auth."""
    data, err = get_json_body(request)
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
        accepted_group_track_ids = accepted_group_track_ids_for_user(request.user)
        subs = (
            LiveTrackSubscription.objects.filter(user=request.user)
            .select_related("track", "track__user")
            .exclude(track__user=request.user)
        )
        subscribed_at_by_track_id = {}
        visible_non_owned_by_id = {}
        for sub in subs:
            t = sub.track
            if can_user_see_track(request.user, t):
                visible_non_owned_by_id[t.id] = t
                subscribed_at_by_track_id[t.id] = (
                    int(sub.created_at.timestamp()) if getattr(sub, "created_at", None) else None
                )
        if accepted_group_track_ids:
            for t in (
                LiveTrack.objects.filter(id__in=accepted_group_track_ids)
                .exclude(user=request.user)
                .select_related("user")
            ):
                visible_non_owned_by_id[t.id] = t
        owned_ids = [t.id for t in owned]
        subscriber_counts = (
            LiveTrackSubscription.objects.filter(track_id__in=owned_ids)
            .exclude(user_id=F("track__user_id"))
            .values("track_id")
            .annotate(count=Count("id"))
        )
        count_by_track = {s["track_id"]: s["count"] for s in subscriber_counts}

        out = []
        for t in owned:
            payload = track_to_response_metadata_only(t, include_secret=False, is_owner=True)
            payload["is_owner"] = True
            payload["subscriber_count"] = count_by_track.get(t.id, 0)
            internal_share = visible_track_internal_share_for_user(t, request.user)
            if internal_share:
                payload["internal_share_id"] = internal_share.share_id
                payload["internal_share_url"] = build_live_track_internal_share_url(request, internal_share.share_id)
            world_share = LiveTrackWorldShare.objects.filter(track=t).first()
            if world_share:
                payload["world_share_id"] = world_share.share_id
                payload["world_share_url"] = build_live_track_share_url(request, world_share.share_id)
            out.append(TrackerListItemResponse.model_validate(payload).model_dump(exclude_none=True))
        non_owned_out = []
        for t in visible_non_owned_by_id.values():
            payload = track_to_response_metadata_only(t, include_secret=False, is_owner=False)
            payload["is_owner"] = False
            payload["owner_email"] = (t.user.email or "") if t.user_id else ""
            payload["visibility"] = t.visibility
            payload["subscribed_at"] = subscribed_at_by_track_id.get(t.id)
            internal_share = visible_track_internal_share_for_user(t, request.user)
            if internal_share:
                payload["internal_share_id"] = internal_share.share_id
                payload["internal_share_url"] = build_live_track_internal_share_url(request, internal_share.share_id)
            non_owned_out.append(TrackerListItemResponse.model_validate(payload).model_dump(exclude_none=True))
        non_owned_out.sort(key=lambda x: (x.get("subscribed_at") is None, x.get("subscribed_at") or 0, (x.get("name") or "").lower()))
        out.extend(non_owned_out)
        return JsonResponse(out, safe=False)

    data, err = get_json_body(request)
    if err is not None:
        return err
    name = (data.get("name") or "").strip()
    if not name:
        return error_response("name is required", 400)
    color = (data.get("color") or "").strip() or DEFAULT_TRACK_COLOR
    if LiveTrack.objects.filter(user=request.user, name=name).exists():
        return error_response("A track with this name already exists", 409)
    tracker_secret = secrets.token_urlsafe(32)
    hauk_password = generate_hauk_password()
    track_id = uuid.uuid4()
    track = LiveTrack.objects.create(
        id=track_id,
        tracker_secret=tracker_secret,
        hauk_password=hauk_password,
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
        internal_share = visible_track_internal_share_for_user(track, request.user)
        if internal_share:
            resp["internal_share_id"] = internal_share.share_id
            resp["internal_share_url"] = build_live_track_internal_share_url(request, internal_share.share_id)
        if is_owner:
            world_share = LiveTrackWorldShare.objects.filter(track=track).first()
            if world_share:
                resp["world_share_id"] = world_share.share_id
                resp["world_share_url"] = build_live_track_share_url(request, world_share.share_id)
        return JsonResponse(resp)
    if request.method == "DELETE":
        if not is_owner:
            return error_response("Only the owner can delete this tracker", 403)
        track.delete()
        return HttpResponse(status=204)
    return error_response("Method not allowed", 405)


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
@csrf_exempt
def tracker_post_settings(request, tracker_id):
    """POST trackers/<id>/settings/ — update name, color, recent_data_window, visibility, share_params, shared_with_emails. Owner only."""
    track = get_object_or_404_for_user(LiveTrack, request.user, id=tracker_id)
    data, err = get_json_body(request)
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
    # Patch contract:
    # - omitted keys: no change
    # - explicit null: clear/reset setting key
    # Settings JSON keys: color, recent_data_window, hidden, allow_group_reshare.
    # (visibility/share fields are model fields handled below)
    provided_fields = body.model_dump(exclude_unset=True)
    settings_keys = {"color", "recent_data_window", "hidden", "allow_group_reshare"}
    settings_dump = {k: v for k, v in provided_fields.items() if k in settings_keys}
    if settings_dump:
        new_settings = normalize_track_settings_for_api({**(track.settings or {})})
        for k, v in settings_dump.items():
            if k == "recent_data_window" and v == "all":
                new_settings.pop(k, None)
                continue
            if v is None:
                new_settings.pop(k, None)
            else:
                new_settings[k] = v
        track.settings = normalize_track_settings_for_api(new_settings)
        update_fields.append("settings")
    if body.visibility is not None:
        track.visibility = body.visibility
        update_fields.append("visibility")
        if body.visibility == VISIBILITY_PRIVATE:
            LiveTrackShare.objects.filter(track=track).delete()
            LiveTrackGroupMember.objects.filter(track=track).exclude(group__user=track.user).delete()
            LiveTrackSubscription.objects.filter(track=track).exclude(user=track.user).delete()
        elif body.visibility == VISIBILITY_PUBLIC:
            LiveTrackShare.objects.filter(track=track).delete()
    if body.share_params_with_recipients is not None:
        track.share_params_with_recipients = body.share_params_with_recipients
        update_fields.append("share_params_with_recipients")
    if body.share_params_with_world is not None:
        track.share_params_with_world = body.share_params_with_world
        update_fields.append("share_params_with_world")
    if "shared_with_emails" in provided_fields:
        raw_shared_with_emails = body.shared_with_emails
        if track.visibility != VISIBILITY_SHARED:
            # Accept null/[] as a safe clear/no-op for non-shared visibility.
            if raw_shared_with_emails is None or raw_shared_with_emails == []:
                LiveTrackShare.objects.filter(track=track).delete()
                LiveTrackGroupMember.objects.filter(track=track).exclude(group__user=track.user).delete()
                LiveTrackSubscription.objects.filter(track=track).exclude(user=track.user).delete()
                raw_shared_with_emails = []
            else:
                return error_response("shared_with_emails only applies when visibility is shared", 400)
        if raw_shared_with_emails is None:
            raw_shared_with_emails = []
        emails = [e.strip().lower() for e in raw_shared_with_emails if (e or "").strip()]
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
        # Remove track from any groups owned by the unshared users and drop their subscriptions
        if to_remove:
            LiveTrackGroupMember.objects.filter(
                track=track, group__user_id__in=to_remove
            ).delete()
            LiveTrackSubscription.objects.filter(
                track=track, user_id__in=to_remove
            ).delete()
    if body.visibility == VISIBILITY_SHARED:
        recipient_ids = set(
            LiveTrackShare.objects.filter(track=track).values_list("shared_with_id", flat=True)
        )
        keep_ids = recipient_ids | {track.user_id}
        LiveTrackGroupMember.objects.filter(track=track).exclude(group__user_id__in=keep_ids).delete()
        LiveTrackSubscription.objects.filter(track=track).exclude(user_id__in=keep_ids).delete()
    # World share is allowed for shared/public tracks, but never for private tracks.
    if track.visibility == VISIBILITY_PRIVATE:
        LiveTrackWorldShare.objects.filter(track=track).delete()
    elif "world_share_enabled" in provided_fields:
        if body.world_share_enabled:
            share, _ = LiveTrackWorldShare.objects.get_or_create(
                track=track,
                defaults={"share_id": str(uuid.uuid4())},
            )
        else:
            LiveTrackWorldShare.objects.filter(track=track).delete()
    if update_fields:
        track.save(update_fields=update_fields)
    internal_share = sync_track_internal_share(track)
    resp = track_to_response_metadata_only(track, include_secret=True, is_owner=True)
    resp["subscriber_count"] = LiveTrackSubscription.objects.filter(track=track).exclude(
        user_id=track.user_id
    ).count()
    if internal_share:
        resp["internal_share_id"] = internal_share.share_id
        resp["internal_share_url"] = build_live_track_internal_share_url(request, internal_share.share_id)
    world_share = LiveTrackWorldShare.objects.filter(track=track).first()
    if world_share:
        resp["world_share_id"] = world_share.share_id
        resp["world_share_url"] = build_live_track_share_url(request, world_share.share_id)
    return JsonResponse(resp)


@api_or_login_required_401()
@require_http_methods(["GET"])
@handle_404
@csrf_exempt
def tracker_subscribers(request, tracker_id):
    """GET trackers/<id>/subscribers/ — list users who have subscribed to this track (owner only). Excludes owner."""
    track = get_object_or_404_for_user(LiveTrack, request.user, id=tracker_id)
    if track.user_id != request.user.id:
        return error_response("Only the owner can list subscribers", 403)
    subs = (
        LiveTrackSubscription.objects.filter(track=track)
        .exclude(user_id=track.user_id)
        .select_related("user")
    )
    subscribers = [{"id": str(s.user_id), "email": (s.user.email or "").strip()} for s in subs if s.user_id]
    return JsonResponse({"subscribers": subscribers})


@api_or_login_required_401()
@require_http_methods(["GET"])
@handle_404
@csrf_exempt
def tracker_get_geometry(request, tracker_id):
    """GET trackers/<id>/geometry/ — full geometry + all point_params (for map, params table, etc.). ?all=true bypasses recent_data_window filter."""
    track = _get_track_for_user_or_404(request.user, tracker_id)
    is_owner = track.user_id == request.user.id
    all_data = request.GET.get("all", "").lower() == "true"
    if all_data:
        response_payload = track_to_response(
            track,
            include_secret=False,
            is_owner=is_owner,
            all_data=True,
        )
    else:
        max_bytes = get_config_loader().get_int(
            "extensions.live_track.geometry_max_response_bytes",
            1048576,
        )
        response_payload = _bounded_track_geometry_payload(track, is_owner, max_bytes)

    return JsonResponse(
        response_payload,
        json_dumps_params={"separators": (",", ":"), "ensure_ascii": True},
    )


@api_or_login_required_401()
@require_http_methods(["POST"])
@csrf_exempt
def tracker_get_geometry_bulk(request):
    """POST trackers/geometry/ — geometry for multiple trackers. Body: {tracker_ids: [...]}."""
    from django.http import Http404

    data, err = get_json_body(request)
    if err is not None:
        return err
    try:
        body = TrackerBulkGeometryRequest.model_validate(data or {})
    except PydanticValidationError as e:
        errs = e.errors()
        msg = errs[0].get("msg", "Invalid body") if errs else "Invalid body"
        return error_response(msg, 400)

    # Keep request bounded and deterministic.
    ordered_ids = []
    seen = set()
    for raw in body.tracker_ids:
        if not isinstance(raw, str):
            continue
        tracker_id = raw.strip()
        if not tracker_id or tracker_id in seen:
            continue
        ordered_ids.append(tracker_id)
        seen.add(tracker_id)
        if len(ordered_ids) >= 200:
            break

    max_bytes = get_config_loader().get_int(
        "extensions.live_track.geometry_max_response_bytes",
        1048576,
    )
    result = []
    for tracker_id in ordered_ids:
        try:
            track = _get_track_for_user_or_404(request.user, tracker_id)
        except Http404:
            # Omit trackers that are inaccessible, invalid, or missing.
            continue
        is_owner = track.user_id == request.user.id
        result.append(_bounded_track_geometry_payload(track, is_owner, max_bytes))
    return JsonResponse(
        result,
        safe=False,
        json_dumps_params={"separators": (",", ":"), "ensure_ascii": True},
    )


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
    track.save(update_fields=["geometry", "point_params"])
    return JsonResponse(track_to_response_metadata_only(track, include_secret=False), status=200)


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
@csrf_exempt
def tracker_regenerate_hauk_password(request, tracker_id):
    """POST trackers/<id>/regenerate-hauk-password/ — generate new Hauk-only password for this tracker. Owner only."""
    track = get_object_or_404_for_user(LiveTrack, request.user, id=tracker_id)
    if track.user_id != request.user.id:
        return error_response("Only the owner can regenerate Hauk password", 403)
    track.hauk_password = generate_hauk_password()
    track.save(update_fields=["hauk_password"])
    return JsonResponse({"hauk_password": track.hauk_password}, status=200)


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
@csrf_exempt
def tracker_regenerate_tokens(request, tracker_id):
    """POST trackers/<id>/regenerate-tokens/ — regenerate tracker API + Hauk credentials. Owner only."""
    track = get_object_or_404_for_user(LiveTrack, request.user, id=tracker_id)
    if track.user_id != request.user.id:
        return error_response("Only the owner can regenerate tracker tokens", 403)
    track.tracker_secret = secrets.token_urlsafe(32)
    track.hauk_password = generate_hauk_password()
    track.save(update_fields=["tracker_secret", "hauk_password"])
    response = RegenerateTrackerTokensResponse(
        tracker_secret=track.tracker_secret,
        hauk_password=track.hauk_password,
    )
    return JsonResponse(response.model_dump(), status=200)


LATEST_COORDINATES_LIMIT = 100


@api_or_login_required_401()
@require_http_methods(["GET"])
@handle_404
@csrf_exempt
def tracker_get_latest_coordinates(request, tracker_id):
    """GET trackers/<id>/coordinates/ — latest 100 coordinates + corresponding point_params."""
    track = _get_track_for_user_or_404(request.user, tracker_id)
    is_owner = track.user_id == request.user.id
    geom = track.geometry or {"type": "LineString", "coordinates": []}
    coords = list(geom.get("coordinates") or [])
    point_params = list(track.point_params or [])
    window_key = (track.settings or {}).get("recent_data_window")
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
    # Ingress URL: replace .../trackers/<id>/<anything>.properties with .../ingress/
    ingress_path = re.sub(r"/trackers/[^/]+/[^/]+\.properties$", "/ingress/", request.path)
    ingress_url = build_public_url(ingress_path)
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


@require_http_methods(["GET"])
@csrf_exempt
def ingress_body_template(request):
    """Return public GPSLogger template metadata and param pretty names."""
    return JsonResponse({
        "body_template": get_ingress_body_template(),
        "param_labels": PARAM_PRETTY_NAMES,
    })


@api_or_login_required_401()
@require_http_methods(["GET"])
@csrf_exempt
def hauk_config(request):
    """Return Hauk-related config for the frontend (e.g. instructions modal). hauk_domain is used to build the server URL."""
    domain = get_config_loader().get_str("extensions.live_track.hauk_domain", "").strip()
    return JsonResponse({"hauk_domain": domain})


@api_or_login_required_401()
@require_http_methods(["GET"])
@handle_404
@csrf_exempt
def tracker_kml(request, tracker_id):
    """GET trackers/<id>/kml/. Owner or subscriber. Always exports full history."""
    track = _get_track_for_user_or_404(request.user, tracker_id)
    geom = track.geometry or {"type": "LineString", "coordinates": []}
    coords = list(geom.get("coordinates") or [])
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
    """POST: subscribe to track (add to list). DELETE: unsubscribe from track and remove from all groups the user owns. For group-shared tracks use groups/<id>/accept-share/ instead."""
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
        if can_user_see_track_via_accepted_group_share(request.user, track):
            return error_response(
                "Leave the shared group to remove this tracker.",
                400,
            )
        sub = LiveTrackSubscription.objects.filter(user=request.user, track=track).first()
        if not sub:
            return error_response("Not subscribed", 404)
        sub.delete()
        LiveTrackGroupMember.objects.filter(group__user=request.user, track=track).delete()
        return HttpResponse(status=204)
    return error_response("Method not allowed", 405)


@api_or_login_required_401()
@require_http_methods(["DELETE"])
@handle_404
@csrf_exempt
def tracker_leave_share(request, tracker_id):
    """DELETE trackers/<id>/share-with-me/ — Remove yourself from a direct track share. Deletes the share entry and your track subscription. Only for tracks shared with you (visibility=shared and you in shared_with)."""
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
    return HttpResponse(status=204)


@api_or_login_required_401()
@require_http_methods(["GET"])
@csrf_exempt
def tracker_available_to_add(request):
    """GET trackers/available-to-add/ — trackers and groups the user can add. Direct track shares: subscribe via trackers/<id>/subscribe/. Shared groups: accept via groups/<id>/accept-share/ (no per-track subscribe)."""
    owned_ids = set(LiveTrack.objects.filter(user=request.user).values_list("id", flat=True))
    subscribed_ids = set(
        LiveTrackSubscription.objects.filter(user=request.user).values_list("track_id", flat=True)
    )
    accepted_group_track_ids = accepted_group_track_ids_for_user(request.user)
    have_ids = owned_ids | subscribed_ids | accepted_group_track_ids
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
        raw = {
            "id": str(t.id),
            "name": t.name,
            "color": _color_from_settings(t),
            "owner_email": (t.user.email or "") if t.user_id else "",
        }
        return AvailableToAddItemResponse.model_validate(raw).model_dump(exclude_none=True)

    def addable_track_ids_for_group(group):
        group_track_ids = list(
            LiveTrackGroupMember.objects.filter(group=group).values_list("track_id", flat=True)
        )
        addable = []
        for track in LiveTrack.objects.filter(id__in=group_track_ids).select_related("user"):
            if track.id in have_ids:
                continue
            if (
                can_user_see_track(request.user, track)
                or can_user_see_track_via_group_share(request.user, track)
            ):
                addable.append(str(track.id))
        return addable

    groups_shared_with_me = list(
        LiveTrackGroup.objects.filter(
            visibility=VISIBILITY_SHARED,
            share_entries__shared_with=request.user,
        )
        .exclude(accepted_subscriptions__user=request.user)
        .exclude(user=request.user)
        .select_related("user")
        .distinct()
        .order_by("name")
    )
    seen_group_ids = set()
    shared_with_me_groups = []
    for group in groups_shared_with_me:
        seen_group_ids.add(group.id)
        # Pending shared groups are accepted at the group level. Do not expose per-track IDs
        # before acceptance to avoid leaking unaccepted group items into client lists.
        addable = []
        # Shared groups are accepted at the group level via groups/<id>/accept-share/,
        # so they must appear in Incoming even when no per-track "addable" IDs remain.
        shared_with_me_groups.append(AvailableToAddGroupResponse.model_validate({
            "id": str(group.id),
            "name": group.name,
            "owner_email": (group.user.email or "") if group.user_id else "",
            "track_ids": addable,
        }).model_dump(exclude_none=True))

    public_groups = []
    for group in (
        LiveTrackGroup.objects.filter(visibility=VISIBILITY_PUBLIC)
        .exclude(user=request.user)
        .select_related("user")
        .order_by("name")
    ):
        if group.id in seen_group_ids:
            continue
        addable = addable_track_ids_for_group(group)
        if addable:
            public_groups.append(AvailableToAddGroupResponse.model_validate({
                "id": str(group.id),
                "name": group.name,
                "owner_email": (group.user.email or "") if group.user_id else "",
                "track_ids": addable,
            }).model_dump(exclude_none=True))

    payload = AvailableToAddResponse.model_validate({
        "public": [item(t) for t in public],
        "shared_with_me": [item(t) for t in shared_with_me],
        "shared_with_me_groups": shared_with_me_groups,
        "public_groups": public_groups,
    }).model_dump(exclude_none=True)
    return JsonResponse(payload)


def _valid_uuid_strings(ids):
    """Return list of valid UUID strings from input list; invalid entries are skipped."""
    out = []
    for s in ids or []:
        if not isinstance(s, str):
            continue
        s = s.strip()
        if not s:
            continue
        try:
            uuid.UUID(s)
            out.append(s)
        except (ValueError, TypeError):
            continue
    return out


@api_or_login_required_401()
@require_http_methods(["GET", "PATCH"])
@csrf_exempt
def map_visibility_get_patch(request):
    """GET or PATCH map-visibility/ — per-user hidden-on-map track and group IDs."""
    if request.method == "GET":
        prefs, _ = LiveTrackMapVisibilityPrefs.objects.get_or_create(
            user=request.user,
            defaults={"hidden_track_ids": [], "hidden_group_ids": []},
        )
        return JsonResponse({
            "hidden_track_ids": list(prefs.hidden_track_ids or []),
            "hidden_group_ids": list(prefs.hidden_group_ids or []),
        })
    # PATCH
    data, err = get_json_body(request)
    if err is not None:
        return err
    try:
        body = MapVisibilityPrefsRequest.model_validate(data or {})
    except PydanticValidationError as e:
        errs = e.errors()
        msg = errs[0].get("msg", "Invalid body") if errs else "Invalid body"
        return error_response(msg, 400)
    prefs, _ = LiveTrackMapVisibilityPrefs.objects.get_or_create(
        user=request.user,
        defaults={"hidden_track_ids": [], "hidden_group_ids": []},
    )
    if body.hidden_track_ids is not None:
        prefs.hidden_track_ids = _valid_uuid_strings(body.hidden_track_ids)
    if body.hidden_group_ids is not None:
        prefs.hidden_group_ids = _valid_uuid_strings(body.hidden_group_ids)
    prefs.save()
    return JsonResponse({
        "hidden_track_ids": list(prefs.hidden_track_ids),
        "hidden_group_ids": list(prefs.hidden_group_ids),
    })
