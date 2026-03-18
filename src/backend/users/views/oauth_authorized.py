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


def _revoke_access_token_grant(token):
    """
    Revoke access token and linked refresh token/grant state.

    django-oauth-toolkit provides revoke() on token models; prefer that when available.
    """
    from oauth2_provider.models import get_access_token_model, get_refresh_token_model

    AccessToken = get_access_token_model()
    RefreshToken = get_refresh_token_model()

    source_refresh = getattr(token, "source_refresh_token", None)
    if source_refresh is None:
        source_refresh = getattr(token, "refresh_token", None)

    if source_refresh is not None:
        refresh_qs = RefreshToken.objects.filter(
            user=token.user,
            application=token.application,
        )
        if getattr(source_refresh, "token_family", None):
            refresh_qs = refresh_qs.filter(token_family=source_refresh.token_family)
        else:
            refresh_qs = refresh_qs.filter(pk=source_refresh.pk)

        refresh_ids = list(refresh_qs.values_list("id", flat=True))
        if refresh_ids:
            AccessToken.objects.filter(source_refresh_token_id__in=refresh_ids).delete()
            for refresh in refresh_qs:
                refresh_revoke = getattr(refresh, "revoke", None)
                if callable(refresh_revoke):
                    refresh_revoke()
                else:
                    refresh.delete()

    revoke_method = getattr(token, "revoke", None)
    if callable(revoke_method):
        revoke_method()
    else:
        token.delete()


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

    _revoke_access_token_grant(token)
    return JsonResponse({"message": "Authorization revoked"})
