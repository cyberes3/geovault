"""
Live track group CRUD and membership views.
"""

import json

from django.contrib.auth import get_user_model
from django.http import Http404, JsonResponse
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_http_methods

from api.utils.responses import error_response, handle_404
from geo_lib.website.auth import api_or_login_required_401

from .models import (
    LiveTrack,
    LiveTrackGroup,
    LiveTrackGroupMember,
    LiveTrackGroupMembership,
    LiveTrackSubscription,
    VISIBILITY_PUBLIC,
)

User = get_user_model()


def _get_json_body(request):
    try:
        data = json.loads(request.body) if request.body else {}
        return data, None
    except json.JSONDecodeError:
        return None, error_response("Invalid JSON", 400)


def _get_group_for_user_or_404(user, group_id):
    """Return LiveTrackGroup if user is owner or has membership. Raises Http404 otherwise."""
    try:
        group = LiveTrackGroup.objects.get(id=group_id)
    except (LiveTrackGroup.DoesNotExist, ValueError):
        raise Http404
    if group.user_id == user.id:
        return group
    if LiveTrackGroupMembership.objects.filter(group=group, user=user).exists():
        return group
    raise Http404


def _group_can_edit(group, user):
    return group.user_id == user.id


def _group_payload(group, request_user, include_track_ids=True):
    is_owner = group.user_id == request_user.id
    out = {
        "id": str(group.id),
        "name": group.name,
        "created_at": int(group.created_at.timestamp()) if group.created_at else None,
        "updated_at": int(group.updated_at.timestamp()) if group.updated_at else None,
        "is_owner": is_owner,
    }
    if not is_owner and group.user_id:
        out["owner_email"] = (group.user.email or "") if group.user_id else ""
    if include_track_ids:
        track_ids = list(
            LiveTrackGroupMember.objects.filter(group=group).values_list("track_id", flat=True)
        )
        out["track_ids"] = [str(tid) for tid in track_ids]
    memberships = (
        LiveTrackGroupMembership.objects.filter(group=group)
        .select_related("user")
        .order_by("user__email")
    )
    out["member_ids"] = [str(m.user_id) for m in memberships]
    out["member_emails"] = [(m.user.email or "").strip() for m in memberships]
    return out


@api_or_login_required_401()
@require_http_methods(["GET", "POST"])
@csrf_exempt
def group_list_create(request):
    if request.method == "GET":
        owned = LiveTrackGroup.objects.filter(user=request.user).order_by("name")
        member_of = LiveTrackGroup.objects.filter(
            user_members__user=request.user
        ).exclude(user=request.user).distinct().order_by("name")
        items = []
        for g in owned:
            items.append(_group_payload(g, request.user))
        for g in member_of:
            items.append(_group_payload(g, request.user))
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
        name = (data.get("name") or "").strip()
        if not name:
            return error_response("name cannot be empty", 400)
        if LiveTrackGroup.objects.filter(user=request.user, name=name).exclude(id=group.id).exists():
            return error_response("A group with this name already exists", 409)
        group.name = name
        group.save(update_fields=["name", "updated_at"])
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
@require_http_methods(["POST"])
@handle_404
@csrf_exempt
def group_add_member(request, group_id):
    """Add a user to the group by email. Owner only."""
    group = _get_group_for_user_or_404(request.user, group_id)
    if not _group_can_edit(group, request.user):
        return error_response("Only the owner can add members", 403)
    data, err = _get_json_body(request)
    if err is not None:
        return err
    email = (data.get("email") or "").strip()
    if not email:
        return error_response("email is required", 400)
    other = User.objects.filter(email__iexact=email).first()
    if not other or not other.email:
        return error_response("User not found", 404)
    if other.id == request.user.id:
        return error_response("You are already the owner", 400)
    LiveTrackGroupMembership.objects.get_or_create(group=group, user=other)
    return JsonResponse(_group_payload(group, request.user))


@api_or_login_required_401()
@require_http_methods(["DELETE"])
@handle_404
@csrf_exempt
def group_remove_member(request, group_id, user_id):
    """Owner removes a member from the group."""
    group = _get_group_for_user_or_404(request.user, group_id)
    if not _group_can_edit(group, request.user):
        return error_response("Only the owner can remove members", 403)
    membership = LiveTrackGroupMembership.objects.filter(
        group=group, user_id=user_id
    ).first()
    if not membership:
        return error_response("User is not a member of this group", 404)
    membership.delete()
    return JsonResponse({}, status=204)


@api_or_login_required_401()
@require_http_methods(["DELETE"])
@handle_404
@csrf_exempt
def group_leave(request, group_id):
    """Current user removes their own membership (non-owners only)."""
    group = _get_group_for_user_or_404(request.user, group_id)
    if group.user_id == request.user.id:
        return error_response("Owner cannot leave; delete the group to remove it", 400)
    membership = LiveTrackGroupMembership.objects.filter(
        group=group, user=request.user
    ).first()
    if not membership:
        return error_response("You are not a member of this group", 404)
    membership.delete()
    return JsonResponse({}, status=204)
