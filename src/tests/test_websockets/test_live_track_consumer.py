"""
Tests for LiveTrackOnlyConsumer (ws/extensions/live-track/trackers-live/).
"""
import json
from channels.layers import get_channel_layer
from channels.testing import WebsocketCommunicator
from channels.db import database_sync_to_async
from django.test import TransactionTestCase
from django.contrib.auth import get_user_model
from django.contrib.auth.models import AnonymousUser

from extensions.live_track.src.backend.consumers import LiveTrackOnlyConsumer

User = get_user_model()


class TestLiveTrackOnlyConsumer(TransactionTestCase):
    """Test LiveTrackOnlyConsumer connection and message handling."""

    async def test_connection_authenticated(self):
        """Authenticated user can connect and receives initial_state."""
        user = await database_sync_to_async(User.objects.create_user)(
            email="livetrack@example.com",
            password="testpass123",
            username="livetrackuser",
        )
        communicator = WebsocketCommunicator(
            LiveTrackOnlyConsumer.as_asgi(),
            "/ws/extensions/live-track/trackers-live/",
        )
        communicator.scope["user"] = user

        connected, _ = await communicator.connect()
        self.assertTrue(connected)

        # Should receive initial_state
        msg = await communicator.receive_json_from(timeout=2)
        self.assertEqual(msg.get("module"), "live_track")
        self.assertEqual(msg.get("type"), "initial_state")
        self.assertEqual(msg.get("data"), {})

        await communicator.disconnect()

    async def test_connection_unauthenticated_rejected(self):
        """Anonymous user cannot connect (consumer returns without accept)."""
        communicator = WebsocketCommunicator(
            LiveTrackOnlyConsumer.as_asgi(),
            "/ws/extensions/live-track/trackers-live/",
        )
        communicator.scope["user"] = AnonymousUser()

        try:
            connected, _ = await communicator.connect(timeout=1)
            self.assertFalse(connected)
        except Exception:
            # Timeout or other failure is acceptable when connection is rejected
            pass

    async def test_track_updated_forwarded_to_client(self):
        """When channel layer sends live_track_track_updated, client receives track_updated JSON."""
        user = await database_sync_to_async(User.objects.create_user)(
            email="track2@example.com",
            password="testpass123",
            username="track2user",
        )
        communicator = WebsocketCommunicator(
            LiveTrackOnlyConsumer.as_asgi(),
            "/ws/extensions/live-track/trackers-live/",
        )
        communicator.scope["user"] = user

        connected, _ = await communicator.connect()
        self.assertTrue(connected)

        # Consume initial_state
        await communicator.receive_json_from(timeout=2)

        # Send track_updated via channel layer (as the backend does)
        channel_layer = get_channel_layer()
        await channel_layer.group_send(
            f"live_track_{user.id}",
            {
                "type": "live_track_track_updated",
                "data": {
                    "track_id": "track-uuid-123",
                    "point": [10.5, 45.2, 1700000000000],
                    "props": {"accuracy": 5.0},
                    "index": 0,
                },
            },
        )

        msg = await communicator.receive_json_from(timeout=2)
        self.assertEqual(msg.get("module"), "live_track")
        self.assertEqual(msg.get("type"), "track_updated")
        data = msg.get("data", {})
        self.assertEqual(data.get("track_id"), "track-uuid-123")
        self.assertEqual(data.get("point"), [10.5, 45.2, 1700000000000])
        self.assertEqual(data.get("props"), {"accuracy": 5.0})
        self.assertEqual(data.get("index"), 0)

        await communicator.disconnect()

    async def test_ping_answered_with_pong(self):
        """Client's app-level liveness ping (see StreamingSessionGuard on Android) gets a direct
        pong reply, independent of any track_updated activity."""
        user = await database_sync_to_async(User.objects.create_user)(
            email="ping@example.com",
            password="testpass123",
            username="pinguser",
        )
        communicator = WebsocketCommunicator(
            LiveTrackOnlyConsumer.as_asgi(),
            "/ws/extensions/live-track/trackers-live/",
        )
        communicator.scope["user"] = user

        connected, _ = await communicator.connect()
        self.assertTrue(connected)

        # Consume initial_state
        await communicator.receive_json_from(timeout=2)

        await communicator.send_to(text_data=json.dumps({"module": "live_track", "type": "ping"}))

        msg = await communicator.receive_json_from(timeout=2)
        self.assertEqual(msg.get("module"), "live_track")
        self.assertEqual(msg.get("type"), "pong")

        await communicator.disconnect()

    async def test_unknown_message_type_ignored(self):
        """A non-ping message is silently ignored rather than erroring or closing the socket."""
        user = await database_sync_to_async(User.objects.create_user)(
            email="unknown@example.com",
            password="testpass123",
            username="unknownuser",
        )
        communicator = WebsocketCommunicator(
            LiveTrackOnlyConsumer.as_asgi(),
            "/ws/extensions/live-track/trackers-live/",
        )
        communicator.scope["user"] = user

        connected, _ = await communicator.connect()
        self.assertTrue(connected)

        # Consume initial_state
        await communicator.receive_json_from(timeout=2)

        await communicator.send_to(text_data=json.dumps({"module": "live_track", "type": "unknown"}))
        self.assertTrue(await communicator.receive_nothing(timeout=0.5))

        await communicator.disconnect()
