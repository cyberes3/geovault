"""
Live track group CRUD and sharing views (sharing-only; no membership).
"""

import json
import uuid

from django.contrib.auth import get_user_model
from django.db.models import Q
from django.http import Http404, JsonResponse
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_http_methods

from api.utils.responses import error_response, handle_404
from geo_lib.website.auth import api_or_login_required_401

from .models import (
    LiveTrack,
    LiveTrackGroup,
    LiveTrackGroupMember,
    LiveTrackGroupShare,
    LiveTrackGroupWorldShare,
    LiveTrackSubscription,
    VISIBILITY_PRIVATE,
    VISIBILITY_PUBLIC,
    VISIBILITY_SHARED,
)
from .world_share_views import build_live_track_group_share_url

User = get_user_model()


def can_user_see_group(user, group):
    """True if user is owner or group is visible (public or shared with user)."""
    if group.user_id == user.id:
        return True
    if group.visibility == VISIBILITY_PUBLIC:
        return True
    if group.visibility == VISIBILITY_SHARED:
        return LiveTrackGroupShare.objects.filter(group=group, shared_with=user).exists()
    return False


def _get_json_body(request):
    try:
        data = json.loads(request.body) if request.body else {}
        return data, None
    except json.JSONDecodeError:
        return None, error_response("Invalid JSON", 400)


def _get_group_for_user_or_404(user, group_id):
    """Return LiveTrackGroup if user can see it (owner or visibility public/shared with user). Raises Http404 otherwise."""
    try:
        group = LiveTrackGroup.objects.get(id=group_id)
    except (LiveTrackGroup.DoesNotExist, ValueError):
        raise Http404
    if can_user_see_group(user, group):
        return group
    raise Http404


def _group_can_edit(group, user):
    return group.user_id == user.id


def _group_payload(group, request_user, include_track_ids=True):
    is_owner = group.user_id == request_user.id
    out = {
        "id": str(group.id),
        "name": group.name,
        "hidden_in_list": getattr(group, "hidden_in_list", False),
        "visibility": getattr(group, "visibility", "private"),
        "created_at": int(group.created_at.timestamp()) if group.created_at else None,
        "updated_at": int(group.updated_at.timestamp()) if group.updated_at else None,
        "is_owner": is_owner,
    }
    if not is_owner and group.user_id:
        out["owner_email"] = (group.user.email or "") if group.user_id else ""
    if is_owner:
        emails = list(
            LiveTrackGroupShare.objects.filter(group=group)
            .values_list("shared_with__email", flat=True)
        )
        out["shared_with_emails"] = [e for e in emails if e]
        world_share = LiveTrackGroupWorldShare.objects.filter(group=group).first()
        if world_share:
            out["world_share_id"] = world_share.share_id
            out["world_share_url"] = build_live_track_group_share_url(world_share.share_id)
    if include_track_ids:
        track_ids = list(
            LiveTrackGroupMember.objects.filter(group=group).values_list("track_id", flat=True)
        )
        out["track_ids"] = [str(tid) for tid in track_ids]
    return out


@api_or_login_required_401()
@require_http_methods(["GET", "POST"])
@csrf_exempt
def group_list_create(request):
    if request.method == "GET":
        owned = LiveTrackGroup.objects.filter(user=request.user).order_by("name")
        seen_ids = set()
        items = []
        for g in owned:
            seen_ids.add(g.id)
            items.append(_group_payload(g, request.user))
        public_groups = (
            LiveTrackGroup.objects.filter(visibility=VISIBILITY_PUBLIC)
            .exclude(user=request.user)
            .exclude(id__in=seen_ids)
            .select_related("user")
            .order_by("name")
        )
        for g in public_groups:
            seen_ids.add(g.id)
            items.append(_group_payload(g, request.user))
        shared_with_me = (
            LiveTrackGroup.objects.filter(
                visibility=VISIBILITY_SHARED,
                share_entries__shared_with=request.user,
            )
            .exclude(id__in=seen_ids)
            .select_related("user")
            .distinct()
            .order_by("name")
        )
        for g in shared_with_me:
            items.append(_group_payload(g, request.user))
        items.sort(key=lambda x: (x.get("name") or "").lower())
        return JsonResponse(items, safe=False)
    if request.method == "POST":
        data, err = _get_json_body(request)
        if err is not None:
            return err
        name = (data.get("name") or "").strip()
        if not name:
            return error_response("name is required", 400)
        if LiveTrackGroup.objects.filter(user=request.user, name=name).exists():
            return error_response("A group with this name already exists", 409)
        group = LiveTrackGroup.objects.create(user=request.user, name=name)
        return JsonResponse(_group_payload(group, request.user), status=201)
    return error_response("Method not allowed", 405)


@api_or_login_required_401()
@require_http_methods(["GET", "PATCH", "DELETE"])
@handle_404
@csrf_exempt
def group_get_patch_delete(request, group_id):
    group = _get_group_for_user_or_404(request.user, group_id)
    if request.method == "GET":
        return JsonResponse(_group_payload(group, request.user))
    if request.method == "PATCH":
        if not _group_can_edit(group, request.user):
            return error_response("Only the owner can update this group", 403)
        data, err = _get_json_body(request)
        if err is not None:
            return err
        update_fields = ["updated_at"]
        if "name" in data:
            name = (data.get("name") or "").strip()
            if not name:
                return error_response("name cannot be empty", 400)
            if LiveTrackGroup.objects.filter(user=request.user, name=name).exclude(id=group.id).exists():
                return error_response("A group with this name already exists", 409)
            group.name = name
            update_fields.append("name")
        if "hidden_in_list" in data:
            group.hidden_in_list = bool(data["hidden_in_list"])
            update_fields.append("hidden_in_list")
        if "visibility" in data:
            v = data.get("visibility")
            if v not in ("private", "shared", "public"):
                return error_response("visibility must be private, shared, or public", 400)
            group.visibility = v
            update_fields.append("visibility")
            if v == VISIBILITY_PRIVATE:
                # No longer shared: clear group share entries so they don't become stale
                LiveTrackGroupShare.objects.filter(group=group).delete()
        if "shared_with_emails" in data:
            if getattr(group, "visibility", "private") != VISIBILITY_SHARED:
                return error_response("shared_with_emails only applies when visibility is shared", 400)
            raw = data.get("shared_with_emails")
            if not isinstance(raw, list):
                return error_response("shared_with_emails must be a list", 400)
            if any(not isinstance(e, str) for e in raw):
                return error_response("shared_with_emails must be a list of strings", 400)
            emails = [e.strip().lower() for e in raw if (e or "").strip()]
            q = Q()
            for e in emails:
                q |= Q(email__iexact=e)
            users = User.objects.filter(q)
            users_by_email = {u.email.lower(): u for u in users if u.email}
            invalid = [e for e in emails if e not in users_by_email]
            if invalid:
                return JsonResponse({"error": "Invalid emails", "invalid_emails": invalid}, status=400)
            target_users = set(users_by_email[e] for e in emails)
            current = set(LiveTrackGroupShare.objects.filter(group=group).values_list("shared_with_id", flat=True))
            to_add = target_users - {u for u in target_users if u.id in current}
            to_remove = current - {u.id for u in target_users}
            for u in to_add:
                LiveTrackGroupShare.objects.get_or_create(group=group, shared_with=u)
            LiveTrackGroupShare.objects.filter(group=group, shared_with_id__in=to_remove).delete()
        if "world_share_enabled" in data:
            if data["world_share_enabled"]:
                LiveTrackGroupWorldShare.objects.get_or_create(
                    group=group,
                    defaults={"share_id": str(uuid.uuid4())},
                )
            else:
                LiveTrackGroupWorldShare.objects.filter(group=group).delete()
        if len(update_fields) > 1:
            group.save(update_fields=update_fields)
        return JsonResponse(_group_payload(group, request.user))
    if request.method == "DELETE":
        if not _group_can_edit(group, request.user):
            return error_response("Only the owner can delete this group", 403)
        group.delete()
        return JsonResponse({}, status=204)
    return error_response("Method not allowed", 405)


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
@csrf_exempt
def group_add_track(request, group_id):
    """Add a track to the group. If track is public and user not subscribed, subscribe first. Owner only."""
    group = _get_group_for_user_or_404(request.user, group_id)
    if not _group_can_edit(group, request.user):
        return error_response("Only the owner can add trackers", 403)
    data, err = _get_json_body(request)
    if err is not None:
        return err
    track_id = data.get("track_id")
    if not track_id:
        return error_response("track_id is required", 400)
    try:
        track = LiveTrack.objects.get(id=track_id)
    except (LiveTrack.DoesNotExist, ValueError):
        return error_response("Tracker not found", 404)
    # Allowed: (a) owned, (b) already subscribed, (c) public visibility (then subscribe)
    if track.user_id == request.user.id:
        pass
    elif LiveTrackSubscription.objects.filter(user=request.user, track=track).exists():
        pass
    elif track.visibility == VISIBILITY_PUBLIC:
        LiveTrackSubscription.objects.get_or_create(user=request.user, track=track)
    else:
        return error_response("You do not have access to this tracker", 403)
    # If requester is not owner, adding to group requires tracker owner to allow re-share
    if track.user_id != request.user.id:
        if not (track.settings or {}).get("allow_group_reshare"):
            return error_response(
                "The tracker owner has not allowed adding this tracker to groups",
                403,
            )
    LiveTrackGroupMember.objects.get_or_create(group=group, track=track)
    return JsonResponse(_group_payload(group, request.user))


@api_or_login_required_401()
@require_http_methods(["DELETE"])
@handle_404
@csrf_exempt
def group_remove_track(request, group_id, track_id):
    group = _get_group_for_user_or_404(request.user, group_id)
    if not _group_can_edit(group, request.user):
        return error_response("Only the owner can remove trackers", 403)
    member = LiveTrackGroupMember.objects.filter(group=group, track_id=track_id).first()
    if not member:
        return error_response("Tracker not in group", 404)
    member.delete()
    return JsonResponse({}, status=204)


@api_or_login_required_401()
@require_http_methods(["DELETE"])
@handle_404
@csrf_exempt
def group_leave(request, group_id):
    """Current user removes their own share (self-unshare). Owner cannot leave."""
    group = _get_group_for_user_or_404(request.user, group_id)
    if group.user_id == request.user.id:
        return error_response("Owner cannot leave; delete the group to remove it", 400)
    share = LiveTrackGroupShare.objects.filter(
        group=group, shared_with=request.user
    ).first()
    if not share:
        return error_response("You are not shared with this group", 404)
    share.delete()
    track_ids = LiveTrackGroupMember.objects.filter(group=group).values_list("track_id", flat=True)
    LiveTrackSubscription.objects.filter(user=request.user, track_id__in=track_ids).delete()
    return JsonResponse({}, status=204)
