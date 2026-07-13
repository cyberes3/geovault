"""Authenticated internal share endpoints for live trackers and groups."""

from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from website.auth_decorators import api_or_login_required_401

from .internal_share_links import INVALID_INTERNAL_SHARE_RESPONSE, resolve_internal_share_data, resolve_internal_share_info
from .validation import InternalShareInfoResponse


@api_or_login_required_401()
@require_http_methods(["GET"])
def internal_share_info(request, share_id):
    """
    GET internal/share/<share_id>/info/ — authenticated internal share resolver.

    Returns target metadata only when the requesting user already has tracker/group access.
    """
    payload = resolve_internal_share_info(share_id, request.user)
    if payload is None:
        return JsonResponse(INVALID_INTERNAL_SHARE_RESPONSE, status=404)
    response = InternalShareInfoResponse.model_validate(payload)
    return JsonResponse(response.model_dump(exclude_none=True))


@api_or_login_required_401()
@require_http_methods(["GET"])
def internal_share_data(request, share_id):
    """
    GET internal/share/<share_id>/ — authenticated standalone internal share data.

    Returns the same shape as world share data, but authorizes through tracker/group sharing
    and follows recipient parameter visibility instead of world-link parameter visibility.
    """
    payload = resolve_internal_share_data(share_id, request.user)
    if payload is None:
        return JsonResponse(INVALID_INTERNAL_SHARE_RESPONSE, status=404)
    return JsonResponse(payload)
