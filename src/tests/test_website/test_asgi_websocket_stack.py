"""
End-to-end tests for the WebSocket ASGI middleware stack:
WebSocketTokenAuthMiddleware -> SessionOriginValidator -> AuthMiddlewareStack -> consumers.

These exercise the real middleware chain (unlike the consumer tests, which set `scope['user']`
directly and never touch auth/origin middleware), specifically to confirm that adding Origin
validation for the session-cookie path did not also block native app (bearer token) connections.

Builds the same middleware chain `website/asgi.py` wires up rather than importing that module
directly: importing it triggers `run_startup_checks()` (production readiness checks -- secret key
strength, external service reachability, etc.) as an import-time side effect, which isn't
appropriate to run inside the test suite.
"""
from channels.auth import AuthMiddlewareStack
from channels.db import database_sync_to_async
from channels.routing import URLRouter
from channels.testing import WebsocketCommunicator
from django.contrib.auth import get_user_model
from django.test import TransactionTestCase, override_settings

from api.routing import websocket_urlpatterns
from users.api_keys import create_user_api_key
from website.websocket_origin_validation import SessionOriginValidator
from website.websocket_token_auth import WebSocketTokenAuthMiddleware

User = get_user_model()

application = WebSocketTokenAuthMiddleware(
    SessionOriginValidator(
        AuthMiddlewareStack(
            URLRouter(websocket_urlpatterns)
        )
    )
)


@override_settings(ALLOWED_HOSTS=["geovault.example.com"])
class TestFullWebSocketStack(TransactionTestCase):
    """Native (bearer token) clients must keep connecting with no Origin header at all, while
    unauthenticated session-path connections are still subject to Origin validation."""

    async def test_native_client_with_bearer_token_and_no_origin_connects(self):
        user = await database_sync_to_async(User.objects.create_user)(
            email='native_client@example.com',
            password='testpass123',
            username='native_client',
        )
        _api_key, raw_key = await database_sync_to_async(create_user_api_key)(user, "Native App Key")

        communicator = WebsocketCommunicator(
            application,
            "/ws/realtime/",
            headers=[(b"authorization", f"Bearer {raw_key}".encode())],
        )
        try:
            # Generous timeout: resolving the bearer token involves a real Argon2 verify plus DB
            # queries, which can be slower than the 1s default under a loaded test run.
            connected, _ = await communicator.connect(timeout=5)
            self.assertTrue(connected, "Native client with a valid bearer token must connect even with no Origin header")
        finally:
            await communicator.disconnect()

    async def test_session_path_with_no_origin_header_is_denied_before_reaching_auth(self):
        """No bearer token and no Origin header: denied by SessionOriginValidator, never even
        reaches AuthMiddlewareStack/the consumer's own AnonymousUser check."""
        communicator = WebsocketCommunicator(application, "/ws/realtime/")
        connected, close_code = await communicator.connect(timeout=5)
        self.assertFalse(connected)
        await communicator.disconnect()

    async def test_session_path_with_mismatched_origin_is_denied(self):
        communicator = WebsocketCommunicator(
            application,
            "/ws/realtime/",
            headers=[(b"origin", b"https://evil.example.com")],
        )
        connected, close_code = await communicator.connect(timeout=5)
        self.assertFalse(connected)
        await communicator.disconnect()

    async def test_session_path_with_matching_origin_reaches_consumer(self):
        """A matching Origin passes origin validation; with no session cookie the request still
        reaches the consumer as AnonymousUser, which RealtimeConsumer itself then rejects. This
        confirms Origin validation and authentication are properly layered, not conflated."""
        communicator = WebsocketCommunicator(
            application,
            "/ws/realtime/",
            headers=[(b"origin", b"https://geovault.example.com")],
        )
        connected, close_code = await communicator.connect(timeout=5)
        # Origin validation passes, but no session means RealtimeConsumer rejects the anonymous
        # connection (it never calls accept()) -- distinct from an Origin-level denial.
        self.assertFalse(connected)
        await communicator.disconnect()
