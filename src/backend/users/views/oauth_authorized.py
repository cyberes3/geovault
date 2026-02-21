"""
API endpoints for the current user to list and revoke their authorized OAuth applications (access tokens).
Session-only; API keys and OAuth tokens cannot manage these.
"""
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

    AccessToken = get_access_token_model()
    tokens = (
        AccessToken.objects.filter(user=request.user)
        .select_related("application")
        .order_by("-created")
    )
    items = []
    for token in tokens:
        app_name = token.application.name if token.application else "Unknown application"
        items.append({
            "id": token.id,
            "application_name": app_name,
            "created": token.created.isoformat(),
            "expires": token.expires.isoformat(),
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
