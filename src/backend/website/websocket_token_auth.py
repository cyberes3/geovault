"""
ASGI middleware that authenticates WebSocket connections using the Authorization header.

When the client connects with Authorization: Bearer <token>, the token is validated
(OAuth2 access token or API key, same as REST API). If valid, scope["user"] is set so
consumers receive an authenticated user. Used for native apps (e.g. Android) that cannot
send session cookies. If no header is present or token is invalid, the request continues
so that AuthMiddlewareStack can still attach a session user.
"""
import logging

from asgiref.sync import sync_to_async
from django.contrib.auth.models import AnonymousUser

from website.middleware import _resolve_oauth2_access_token
from users.api_keys import validate_api_key

logger = logging.getLogger(__name__)


def _get_token_from_scope(scope):
    """Extract Bearer token from WebSocket scope Authorization header. Returns None if not present."""
    headers = scope.get("headers") or []
    for name, value in headers:
        if name.lower() == b"authorization":
            if isinstance(value, bytes):
                value = value.decode("utf-8", errors="replace")
            if value.strip().lower().startswith("bearer "):
                token = value[7:].strip()
                return token if token else None
            return None
    return None


def _resolve_token_to_user_sync(token):
    """Resolve token to user via OAuth2 then API key. Returns user or None."""
    if not token:
        return None
    result = _resolve_oauth2_access_token(token)
    if result is not None:
        user, _ = result
        return user
    result = validate_api_key(token)
    if result is not None:
        user, _ = result
        return user
    return None


class WebSocketTokenAuthMiddleware:
    """
    ASGI middleware that sets scope["user"] from Authorization: Bearer <token> for WebSocket.
    Run before AuthMiddlewareStack so session auth can still apply when no token is sent.
    """

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope.get("type") == "websocket":
            user = scope.get("user")
            if user is None or isinstance(user, AnonymousUser):
                token = _get_token_from_scope(scope)
                if token:
                    resolved = await sync_to_async(_resolve_token_to_user_sync)(token)
                    if resolved is not None:
                        scope = dict(scope)
                        scope["user"] = resolved
                        logger.debug("WebSocket authenticated via Authorization header")
        await self.app(scope, receive, send)
