"""
Tests for WebSocket force-disconnect on auth revocation (logout, API key delete, OAuth revoke).
"""
import asyncio

from channels.db import database_sync_to_async
from channels.testing import WebsocketCommunicator
from django.contrib.auth import get_user_model
from django.test import Client, TransactionTestCase

from api.ws_consumers.process_status_consumer import ProcessStatusConsumer
from api.ws_consumers.realtime_consumer import RealtimeConsumer
from geo_lib.websocket.force_disconnect import WebSocketForceDisconnector, user_sockets_group_name

User = get_user_model()

# Generous but bounded: a real regression (close frame never arrives) must fail with a clear
# assertion instead of hanging, since an uncaught exception mid-test would skip
# `communicator.disconnect()` and leave the consumer's DB connection open, deadlocking the next
# test's table truncation.
_MAX_DRAIN_MESSAGES = 50


async def _settle_after_connect(communicator: WebsocketCommunicator, wait_timeout: float = 5.0) -> None:
    """Wait for the consumer's post-accept setup to finish before proceeding.

    `communicator.connect()` only guarantees that `self.accept()` ran; the consumer's subsequent
    `group_add()` call (joining the shared force-disconnect group) happens in the same background
    task but isn't awaited by `connect()`. Without this, a force-disconnect sent immediately after
    `connect()` returns can race the consumer's own group join and be delivered to no one. Waiting
    for the consumer's first message (sent only after group_add completes, as part of
    send_initial_state()) closes that window; the trailing quiet-drain loop then consumes any
    further initial_state messages so they aren't mistaken for the close frame later.

    Deliberately uses `receive_nothing()` to poll rather than letting `receive_output()` time out:
    a `receive_output()` timeout has the side effect of cancelling the communicator's underlying
    application task (see asgiref's `ApplicationCommunicator.receive_output`), which would kill the
    very consumer we're about to force-disconnect before the real assertions even run.
    """
    if await communicator.receive_nothing(timeout=wait_timeout, interval=0.02):
        return
    while not await communicator.receive_nothing(timeout=0.3, interval=0.02):
        await communicator.receive_output(timeout=0.3)


async def _receive_close_frame(communicator: WebsocketCommunicator) -> dict:
    """Drain queued ASGI messages until the 'websocket.close' frame, or fail with a clear error."""
    for _ in range(_MAX_DRAIN_MESSAGES):
        message = await communicator.receive_output(timeout=5)
        if message.get('type') == 'websocket.close':
            return message
    raise AssertionError(f"Did not receive a websocket.close frame within {_MAX_DRAIN_MESSAGES} messages")


async def _safe_disconnect(communicator: WebsocketCommunicator) -> None:
    """Best-effort teardown: a prior failure may have already cancelled the communicator's
    underlying application task (e.g. a `receive_output()` timeout), which makes a plain
    `disconnect()` raise and mask the real assertion error. Swallow that here so failures are
    reported clearly."""
    try:
        await communicator.disconnect()
    except asyncio.CancelledError:
        pass


class TestWebSocketForceDisconnectorDirect(TransactionTestCase):
    """WebSocketForceDisconnector.disconnect_user() must close every open socket for that user,
    regardless of which consumer it's connected to."""

    async def test_disconnects_open_realtime_consumer_socket(self):
        user = await database_sync_to_async(User.objects.create_user)(
            email='force_disconnect_realtime@example.com',
            password='testpass123',
            username='force_disconnect_realtime',
        )
        communicator = WebsocketCommunicator(RealtimeConsumer.as_asgi(), "/ws/realtime/")
        communicator.scope['user'] = user
        try:
            connected, _ = await communicator.connect()
            self.assertTrue(connected)
            await _settle_after_connect(communicator)

            await WebSocketForceDisconnector.disconnect_user_async(user.id, reason="test")

            closed = await _receive_close_frame(communicator)
            self.assertEqual(closed.get('code'), 4001)
        finally:
            await _safe_disconnect(communicator)

    async def test_disconnects_open_process_status_consumer_socket(self):
        from api.models import ImportQueue

        user = await database_sync_to_async(User.objects.create_user)(
            email='force_disconnect_process@example.com',
            password='testpass123',
            username='force_disconnect_process',
        )
        import_item = await database_sync_to_async(ImportQueue.objects.create)(
            user=user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
        )
        communicator = WebsocketCommunicator(
            ProcessStatusConsumer.as_asgi(),
            f"/ws/upload/status/{import_item.id}/",
        )
        communicator.scope['user'] = user
        communicator.scope['url_route'] = {'kwargs': {'item_id': str(import_item.id)}}
        try:
            connected, _ = await communicator.connect()
            self.assertTrue(connected)
            await _settle_after_connect(communicator)

            await WebSocketForceDisconnector.disconnect_user_async(user.id, reason="test")

            closed = await _receive_close_frame(communicator)
            self.assertEqual(closed.get('code'), 4001)
        finally:
            await _safe_disconnect(communicator)

    async def test_only_targets_the_specified_user(self):
        """A force-disconnect for user A must not affect user B's open socket."""
        user_a = await database_sync_to_async(User.objects.create_user)(
            email='force_disconnect_a@example.com',
            password='testpass123',
            username='force_disconnect_a',
        )
        user_b = await database_sync_to_async(User.objects.create_user)(
            email='force_disconnect_b@example.com',
            password='testpass123',
            username='force_disconnect_b',
        )
        communicator_b = WebsocketCommunicator(RealtimeConsumer.as_asgi(), "/ws/realtime/")
        communicator_b.scope['user'] = user_b
        try:
            connected, _ = await communicator_b.connect()
            self.assertTrue(connected)
            await _settle_after_connect(communicator_b)

            await WebSocketForceDisconnector.disconnect_user_async(user_a.id, reason="test")

            # user_b's socket should remain open (no close frame within a short window).
            self.assertTrue(await communicator_b.receive_nothing(timeout=1))
        finally:
            await _safe_disconnect(communicator_b)

    def test_group_name_is_per_user(self):
        self.assertEqual(user_sockets_group_name(42), "user_sockets_42")
        self.assertNotEqual(user_sockets_group_name(1), user_sockets_group_name(2))

    async def test_noop_when_no_channel_layer_configured(self):
        """Should not raise even if the channel layer is unavailable (defensive fallback)."""
        from unittest.mock import patch

        with patch("geo_lib.websocket.force_disconnect.get_channel_layer", return_value=None):
            WebSocketForceDisconnector.disconnect_user(1, "test")
            await WebSocketForceDisconnector.disconnect_user_async(1, "test")


class TestForceDisconnectOnAPIKeyDelete(TransactionTestCase):
    """Deleting an API key must force-disconnect the user's open WebSocket connections, since a
    socket authenticated via that key's Authorization header would otherwise keep working."""

    async def test_delete_api_key_disconnects_open_socket(self):
        from users.api_keys import create_user_api_key

        user = await database_sync_to_async(User.objects.create_user)(
            email='apikey_ws@example.com',
            password='testpass123',
            username='apikey_ws',
        )
        api_key, _raw_key = await database_sync_to_async(create_user_api_key)(user, "Test Key")

        communicator = WebsocketCommunicator(RealtimeConsumer.as_asgi(), "/ws/realtime/")
        communicator.scope['user'] = user
        try:
            connected, _ = await communicator.connect()
            self.assertTrue(connected)
            await _settle_after_connect(communicator)

            @database_sync_to_async
            def delete_key():
                client = Client()
                client.force_login(user)
                return client.delete(f"/api/user/api-keys/{api_key.id}/")

            response = await delete_key()
            self.assertEqual(response.status_code, 200)

            closed = await _receive_close_frame(communicator)
            self.assertEqual(closed.get('code'), 4001)
        finally:
            await _safe_disconnect(communicator)


class TestForceDisconnectOnOAuthTokenRevoke(TransactionTestCase):
    """Revoking an OAuth token must force-disconnect the user's open WebSocket connections."""

    async def test_revoke_oauth_token_disconnects_open_socket(self):
        from oauth2_provider.models import Application, AccessToken
        from django.utils import timezone
        from datetime import timedelta

        user = await database_sync_to_async(User.objects.create_user)(
            email='oauth_ws@example.com',
            password='testpass123',
            username='oauth_ws',
        )

        @database_sync_to_async
        def setup_app_and_token():
            app = Application.objects.create(
                name="WS Test App",
                user=user,
                client_id="ws-test-client",
                client_type=Application.CLIENT_PUBLIC,
                authorization_grant_type=Application.GRANT_AUTHORIZATION_CODE,
                redirect_uris="https://app.example/cb",
            )
            token = AccessToken.objects.create(
                token="ws_force_disconnect_test_token",
                user=user,
                application=app,
                expires=timezone.now() + timedelta(hours=1),
                scope="api",
            )
            return token

        token = await setup_app_and_token()

        communicator = WebsocketCommunicator(RealtimeConsumer.as_asgi(), "/ws/realtime/")
        communicator.scope['user'] = user
        try:
            connected, _ = await communicator.connect()
            self.assertTrue(connected)
            await _settle_after_connect(communicator)

            @database_sync_to_async
            def revoke_token():
                client = Client()
                client.force_login(user)
                return client.delete(f"/api/user/oauth-authorized-tokens/{token.id}/")

            response = await revoke_token()
            self.assertEqual(response.status_code, 200)

            closed = await _receive_close_frame(communicator)
            self.assertEqual(closed.get('code'), 4001)
        finally:
            await _safe_disconnect(communicator)


class TestForceDisconnectOnLogout(TransactionTestCase):
    """Logging out must force-disconnect the user's open WebSocket connections via the
    user_logged_out signal receiver (covers allauth's LogoutView and any other logout path)."""

    async def test_logout_disconnects_open_socket(self):
        user = await database_sync_to_async(User.objects.create_user)(
            email='logout_ws@example.com',
            password='testpass123',
            username='logout_ws',
        )

        communicator = WebsocketCommunicator(RealtimeConsumer.as_asgi(), "/ws/realtime/")
        communicator.scope['user'] = user
        try:
            connected, _ = await communicator.connect()
            self.assertTrue(connected)
            await _settle_after_connect(communicator)

            @database_sync_to_async
            def do_logout():
                client = Client()
                client.force_login(user)
                return client.post("/accounts/logout/")

            response = await do_logout()
            self.assertIn(response.status_code, [200, 302])

            closed = await _receive_close_frame(communicator)
            self.assertEqual(closed.get('code'), 4001)
        finally:
            await _safe_disconnect(communicator)
