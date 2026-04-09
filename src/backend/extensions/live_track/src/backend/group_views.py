"""
Live track group CRUD and sharing views (sharing-only; no membership).
"""

import uuid

from django.contrib.auth import get_user_model
from django.db.models import Q
from django.http import Http404, HttpResponse, JsonResponse
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_http_methods

from api.utils.responses import error_response, handle_404
from geo_lib.website.auth import api_or_login_required_401

from .models import (
    LiveTrack,
    LiveTrackGroup,
    LiveTrackGroupMember,
    LiveTrackGroupSubscription,
    LiveTrackGroupShare,
    LiveTrackGroupWorldShare,
    LiveTrackSubscription,
    VISIBILITY_PRIVATE,
    VISIBILITY_PUBLIC,
    VISIBILITY_SHARED,
)
from .helpers import (
    accepted_group_ids_for_user,
    can_user_see_track,
    can_user_see_track_via_accepted_group_share,
    get_json_body,
    visible_group_track_ids_for_user,
)
from .validation import GroupResponse
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


def _group_payload(group, request_user, include_track_ids=True, accepted_group_ids=None, request=None):
    is_owner = group.user_id == request_user.id
    is_accepted = True
    if not is_owner and group.visibility == VISIBILITY_SHARED:
        if accepted_group_ids is not None:
            is_accepted = group.id in accepted_group_ids
        else:
            is_accepted = LiveTrackGroupSubscription.objects.filter(
                user=request_user,
                group=group,
            ).exists()
    out = {
        "id": str(group.id),
        "name": group.name,
        "hidden": getattr(group, "hidden", False),
        "visibility": getattr(group, "visibility", "private"),
        "created_at": int(group.created_at.timestamp()) if group.created_at else None,
        "updated_at": int(group.updated_at.timestamp()) if group.updated_at else None,
        "is_owner": is_owner,
        "is_accepted": is_accepted,
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
            if request:
                out["world_share_url"] = build_live_track_group_share_url(request, world_share.share_id)
    if include_track_ids:
        out["track_ids"] = visible_group_track_ids_for_user(
            group=group,
            user=request_user,
            is_owner=is_owner,
            is_accepted=is_accepted,
        )
    return GroupResponse.model_validate(out).model_dump(exclude_none=True)


@api_or_login_required_401()
@require_http_methods(["GET", "POST"])
@csrf_exempt
def group_list_create(request):
    """GET: owned, public, and shared-with-me groups (shared groups include is_accepted; accept via POST groups/<id>/accept-share/). POST: create group."""
    if request.method == "GET":
        accepted_group_ids = accepted_group_ids_for_user(request.user)
        owned = LiveTrackGroup.objects.filter(user=request.user).order_by("name")
        seen_ids = set()
        items = []
        for g in owned:
            seen_ids.add(g.id)
            items.append(_group_payload(g, request.user, accepted_group_ids=accepted_group_ids, request=request))
        public_groups = (
            LiveTrackGroup.objects.filter(visibility=VISIBILITY_PUBLIC)
            .exclude(user=request.user)
            .exclude(id__in=seen_ids)
            .select_related("user")
            .order_by("name")
        )
        for g in public_groups:
            seen_ids.add(g.id)
            items.append(_group_payload(g, request.user, accepted_group_ids=accepted_group_ids, request=request))
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
            items.append(_group_payload(g, request.user, accepted_group_ids=accepted_group_ids, request=request))
        items.sort(key=lambda x: (x.get("name") or "").lower())
        return JsonResponse(items, safe=False)
    if request.method == "POST":
        data, err = get_json_body(request)
        if err is not None:
            return err
        name = (data.get("name") or "").strip()
        if not name:
            return error_response("name is required", 400)
        if LiveTrackGroup.objects.filter(user=request.user, name=name).exists():
            return error_response("A group with this name already exists", 409)
        group = LiveTrackGroup.objects.create(user=request.user, name=name)
        return JsonResponse(_group_payload(group, request.user, request=request), status=201)
    return error_response("Method not allowed", 405)


@api_or_login_required_401()
@require_http_methods(["GET", "PATCH", "DELETE"])
@handle_404
@csrf_exempt
def group_get_patch_delete(request, group_id):
    group = _get_group_for_user_or_404(request.user, group_id)
    if request.method == "GET":
        return JsonResponse(_group_payload(group, request.user, request=request))
    if request.method == "PATCH":
        if not _group_can_edit(group, request.user):
            return error_response("Only the owner can update this group", 403)
        data, err = get_json_body(request)
        if err is not None:
            return err
        # Patch contract:
        # - omitted keys: no change
        # - explicit null: clear/reset when field semantics allow it
        #   (e.g. hidden -> False via bool(None), world_share_enabled -> disable)
        update_fields = ["updated_at"]
        if "name" in data:
            name = (data.get("name") or "").strip()
            if not name:
                return error_response("name cannot be empty", 400)
            if LiveTrackGroup.objects.filter(user=request.user, name=name).exclude(id=group.id).exists():
                return error_response("A group with this name already exists", 409)
            group.name = name
            update_fields.append("name")
        if "hidden" in data:
            group.hidden = bool(data["hidden"])
            update_fields.append("hidden")
        if "visibility" in data:
            v = data.get("visibility")
            if v not in ("private", "shared", "public"):
                return error_response("visibility must be private, shared, or public", 400)
            group.visibility = v
            update_fields.append("visibility")
            if v == VISIBILITY_PRIVATE:
                # No longer shared: clear group share entries so they don't become stale
                LiveTrackGroupShare.objects.filter(group=group).delete()
                LiveTrackGroupSubscription.objects.filter(group=group).delete()
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
            LiveTrackGroupSubscription.objects.filter(group=group, user_id__in=to_remove).delete()
        # World share is allowed for shared/public groups, but never for private groups.
        if group.visibility == VISIBILITY_PRIVATE:
            LiveTrackGroupWorldShare.objects.filter(group=group).delete()
        elif "world_share_enabled" in data:
            if data["world_share_enabled"]:
                LiveTrackGroupWorldShare.objects.get_or_create(
                    group=group,
                    defaults={"share_id": str(uuid.uuid4())},
                )
            else:
                LiveTrackGroupWorldShare.objects.filter(group=group).delete()
        if "add_track_ids" in data or "remove_track_ids" in data:
            raw_add_track_ids = data.get("add_track_ids", [])
            raw_remove_track_ids = data.get("remove_track_ids", [])
            if not isinstance(raw_add_track_ids, list):
                return error_response("add_track_ids must be a list", 400)
            if not isinstance(raw_remove_track_ids, list):
                return error_response("remove_track_ids must be a list", 400)
            if any(not isinstance(track_id, str) for track_id in raw_add_track_ids):
                return error_response("add_track_ids must be a list of strings", 400)
            if any(not isinstance(track_id, str) for track_id in raw_remove_track_ids):
                return error_response("remove_track_ids must be a list of strings", 400)

            add_track_ids = set(raw_add_track_ids)
            remove_track_ids = set(raw_remove_track_ids)
            add_track_ids -= remove_track_ids
            remove_track_ids -= set(raw_add_track_ids)

            tracks = list(
                LiveTrack.objects.filter(id__in=add_track_ids).select_related("user")
            )
            tracks_by_id = {str(track.id): track for track in tracks}
            missing_track_ids = [track_id for track_id in add_track_ids if track_id not in tracks_by_id]
            if missing_track_ids:
                return JsonResponse(
                    {"error": "Trackers not found", "missing_track_ids": missing_track_ids},
                    status=404,
                )

            for track_id in add_track_ids:
                track = tracks_by_id[track_id]
                has_access = (
                    can_user_see_track(request.user, track)
                    or can_user_see_track_via_accepted_group_share(request.user, track)
                )
                if not has_access:
                    return error_response("You do not have access to this tracker", 403)
                if track.visibility == VISIBILITY_PUBLIC and track.user_id != request.user.id:
                    LiveTrackSubscription.objects.get_or_create(user=request.user, track=track)
                if track.user_id != request.user.id:
                    if not (track.settings or {}).get("allow_group_reshare"):
                        return error_response(
                            "The tracker owner has not allowed adding this tracker to groups",
                            403,
                        )

            if remove_track_ids:
                LiveTrackGroupMember.objects.filter(
                    group=group,
                    track_id__in=remove_track_ids,
                ).delete()
            for track_id in add_track_ids:
                LiveTrackGroupMember.objects.get_or_create(
                    group=group,
                    track_id=track_id,
                )
        if len(update_fields) > 1:
            group.save(update_fields=update_fields)
        return JsonResponse(_group_payload(group, request.user, request=request))
    if request.method == "DELETE":
        if not _group_can_edit(group, request.user):
            return error_response("Only the owner can delete this group", 403)
        group.delete()
        return HttpResponse(status=204)
    return error_response("Method not allowed", 405)


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
@csrf_exempt
def group_add_track(request, group_id):
    """Add a track to the group. If track is public and user not subscribed to the track, subscribe to track first. Owner only."""
    group = _get_group_for_user_or_404(request.user, group_id)
    if not _group_can_edit(group, request.user):
        return error_response("Only the owner can add trackers", 403)
    data, err = get_json_body(request)
    if err is not None:
        return err
    track_id = data.get("track_id")
    if not track_id:
        return error_response("track_id is required", 400)
    try:
        track = LiveTrack.objects.get(id=track_id)
    except (LiveTrack.DoesNotExist, ValueError):
        return error_response("Tracker not found", 404)
    # Canonical access: owner, directly visible/shared, or available via accepted shared group.
    has_access = (
        can_user_see_track(request.user, track)
        or can_user_see_track_via_accepted_group_share(request.user, track)
    )
    if not has_access:
        return error_response("You do not have access to this tracker", 403)
    if track.visibility == VISIBILITY_PUBLIC and track.user_id != request.user.id:
        LiveTrackSubscription.objects.get_or_create(user=request.user, track=track)
    # If requester is not owner, adding to group requires tracker owner to allow re-share
    if track.user_id != request.user.id:
        if not (track.settings or {}).get("allow_group_reshare"):
            return error_response(
                "The tracker owner has not allowed adding this tracker to groups",
                403,
            )
    LiveTrackGroupMember.objects.get_or_create(group=group, track=track)
    return JsonResponse(_group_payload(group, request.user, request=request))


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
    # 204 responses must not include a body; empty HttpResponse avoids client protocol errors.
    return HttpResponse(status=204)


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
    LiveTrackGroupSubscription.objects.filter(group=group, user=request.user).delete()
    share.delete()
    return HttpResponse(status=204)


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
@csrf_exempt
def group_accept_share(request, group_id):
    """POST groups/<id>/accept-share/ — accept a shared group invitation."""
    group = _get_group_for_user_or_404(request.user, group_id)
    if group.user_id == request.user.id:
        return error_response("You already own this group", 400)
    if group.visibility != VISIBILITY_SHARED:
        return error_response("Only shared groups can be accepted", 400)
    share = LiveTrackGroupShare.objects.filter(
        group=group,
        shared_with=request.user,
    ).first()
    if not share:
        return error_response("This group is not shared with you", 404)
    LiveTrackGroupSubscription.objects.get_or_create(
        user=request.user,
        group=group,
    )
    return JsonResponse(_group_payload(group, request.user, request=request), status=201)
