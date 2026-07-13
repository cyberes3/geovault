"""Bearer-token resolution middleware: OAuth2 access tokens and API keys for /api/ requests."""
import hashlib

from django.core.cache import cache

from users.api_keys import validate_api_key

# Throttle writing AccessToken.updated (used only to show "last used" in the settings UI) to at
# most once per this many seconds per token, same pattern as ActivityTrackingMiddleware - a
# database write on every single Bearer-authenticated API request is otherwise wasted load.
ACCESS_TOKEN_TOUCH_THROTTLE_SECONDS = 30
_ACCESS_TOKEN_TOUCH_CACHE_KEY_PREFIX = "oauth_access_token_touch:"


def _resolve_oauth2_access_token(token_string):
    """
    Resolve Bearer token as an OAuth2 access token. Returns (user, access_token) if valid,
    else None. Caller must ensure token_string is non-empty.
    """
    from oauth2_provider.models import get_access_token_model

    if not token_string:
        return None
    token_checksum = hashlib.sha256(token_string.encode("utf-8")).hexdigest()
    AccessToken = get_access_token_model()
    try:
        token = AccessToken.objects.select_related("user").get(token_checksum=token_checksum)
    except AccessToken.DoesNotExist:
        return None
    if token.is_expired():
        return None
    return (token.user, token)


def _touch_access_token(access_token) -> None:
    """Update access_token.updated, throttled to once per ACCESS_TOKEN_TOUCH_THROTTLE_SECONDS."""
    cache_key = f"{_ACCESS_TOKEN_TOUCH_CACHE_KEY_PREFIX}{access_token.pk}"
    if cache.get(cache_key):
        return
    access_token.save(update_fields=["updated"])
    cache.set(cache_key, True, timeout=ACCESS_TOKEN_TOUCH_THROTTLE_SECONDS)


class APIKeyResolutionMiddleware:
    """
    Resolve Bearer token for /api/ requests when user is not yet authenticated.
    Tries OAuth2 access token first, then API key. Sets request.user,
    request.is_api_authenticated, and optionally request.api_key or request.oauth2_access_token.
    """

    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        if request.path.startswith('/api/'):
            auth_header = request.META.get('HTTP_AUTHORIZATION', '')
            if auth_header.startswith('Bearer ') and not request.user.is_authenticated:
                token = auth_header[7:].strip()
                if token:
                    oauth_result = _resolve_oauth2_access_token(token)
                    if oauth_result is not None:
                        user, access_token = oauth_result
                        request.user = user
                        request.oauth2_access_token = access_token
                        request.is_api_authenticated = True
                        # Track last use for settings UI (throttled to avoid a write per request)
                        _touch_access_token(access_token)
                    else:
                        result = validate_api_key(token)
                        if result is not None:
                            user, api_key = result
                            request.user = user
                            request.api_key = api_key
                            request.is_api_authenticated = True
        return self.get_response(request)
