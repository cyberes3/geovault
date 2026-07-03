"""
WebSocket Origin validation, applied only to session-cookie-authenticated connections.

Cross-site WebSocket hijacking (CSWSH) works because a browser automatically attaches ambient
credentials (session cookies) to a WebSocket handshake initiated by *any* page, including a
malicious cross-origin one, and browsers' `WebSocket` API doesn't let JS override the request's
`Origin` header. Validating `Origin` against `ALLOWED_HOSTS` closes that hole for the
session-cookie auth path (used by the web frontend).

Native app clients (Android) instead authenticate via an explicit `Authorization: Bearer <token>`
header (see `WebSocketTokenAuthMiddleware`). That's not an ambient credential a malicious webpage
can attach automatically -- browsers' `WebSocket` API doesn't allow custom headers at all, so any
connection carrying one was made outside a browser and can't be a CSWSH attempt. These clients also
often omit the `Origin` header entirely, which `AllowedHostsOriginValidator` would otherwise reject
outright. So origin validation is skipped whenever a bearer token is present, letting native
clients connect while still protecting the session-cookie path.
"""
from channels.security.websocket import AllowedHostsOriginValidator

from website.websocket_token_auth import get_bearer_token_from_scope


class SessionOriginValidator:
    """Enforces `AllowedHostsOriginValidator` for WebSocket connections that could be authenticated
    via session cookies, and skips it for connections presenting a bearer token."""

    def __init__(self, application):
        self.application = application
        self._origin_validated_application = AllowedHostsOriginValidator(application)

    async def __call__(self, scope, receive, send):
        if scope.get("type") != "websocket" or get_bearer_token_from_scope(scope):
            return await self.application(scope, receive, send)
        return await self._origin_validated_application(scope, receive, send)
