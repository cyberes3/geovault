"""
API endpoints for the current user to list and revoke their authorized OAuth applications (access tokens).
Session-only; API keys and OAuth tokens cannot manage these.
"""
from datetime import timedelta

from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from geo_lib.logging.console import get_tagged_logger
from geo_lib.website.auth import api_or_login_required_401

_logger = get_tagged_logger(__name__)


@api_or_login_required_401(allow_api_keys=False)
@require_http_methods(["GET"])
def list_authorized_oauth_tokens(request):
    """List OAuth access tokens (authorized applications) for the current user."""
    from oauth2_provider.models import get_access_token_model
    from oauth2_provider.settings import oauth2_settings

    AccessToken = get_access_token_model()
    tokens = (
        AccessToken.objects.filter(user=request.user)
        .select_related("application", "refresh_token")
        .order_by("-created")
    )
    refresh_expire_seconds = oauth2_settings.REFRESH_TOKEN_EXPIRE_SECONDS or 0
    items = []
    for token in tokens:
        app_name = token.application.name if token.application else "Unknown application"
        # Show refresh token expiry when available; omit when this grant has no refresh token
        if getattr(token, "refresh_token", None) and refresh_expire_seconds:
            rt = token.refresh_token
            expires_dt = rt.created + timedelta(seconds=refresh_expire_seconds)
            expires_iso = expires_dt.isoformat()
        else:
            expires_iso = None
        # Last used: token.updated is touched on each use in middleware; treat as "never used" if still at creation time
        last_used_at = None
        if token.updated and (token.updated - token.created).total_seconds() > 1:
            last_used_at = token.updated.isoformat()
        items.append({
            "id": token.id,
            "application_name": app_name,
            "created": token.created.isoformat(),
            "expires": expires_iso,
            "last_used_at": last_used_at,
        })
    return JsonResponse({"authorized_tokens": items})


@api_or_login_required_401(allow_api_keys=False)
@require_http_methods(["DELETE"])
def revoke_oauth_token(request, token_id):
    """Revoke (delete) an OAuth access token for the current user."""
    from oauth2_provider.models import get_access_token_model

    try:
        token_id = int(token_id)
    except (ValueError, TypeError):
        return JsonResponse({"error": "Invalid token ID"}, status=400)

    AccessToken = get_access_token_model()
    try:
        token = AccessToken.objects.get(id=token_id, user=request.user)
    except AccessToken.DoesNotExist:
        return JsonResponse({"error": "Token not found"}, status=404)

    token.delete()
    return JsonResponse({"message": "Authorization revoked"})
