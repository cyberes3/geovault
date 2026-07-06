"""WebSocket consumer for track-only updates (ws/extensions/live-track/trackers-live/)."""

import json
import logging

from channels.generic.websocket import AsyncWebsocketConsumer
from django.contrib.auth.models import AnonymousUser

from geo_lib.logging.console import get_tagged_logger
from geo_lib.security.rate_limit import RedisRateLimiter
from geo_lib.utils.ip_utils import get_client_ip, get_user_identifier
from geo_lib.websocket.force_disconnect import user_sockets_group_name
from geo_lib.websocket.ping_pong import is_ping_message, pong_payload

logger = get_tagged_logger(__name__)

# Client pings every ~20s (see StreamingConfig.livenessWatchdogIntervalMs on the Android side);
# generous headroom above that for a client that reconnects/backs off rapidly, while still
# capping a client stuck in a ping-spam loop.
_receive_rate_limiter = RedisRateLimiter(name='live_track_ws_receive', limit=30, window_seconds=10.0)


class LiveTrackOnlyConsumer(AsyncWebsocketConsumer):
    """
    WebSocket consumer that only receives live_track track_updated events.
    Joins live_track_{user_id} so it does not receive import_queue, process_job, etc.
    """

    async def connect(self):
        path = self.scope.get("path", "unknown")
        self.room_group_name = None
        self.user_sockets_group_name = None
        try:
            self.user = self.scope.get("user")
            if self.user is None:
                self.user = AnonymousUser()
            if isinstance(self.user, AnonymousUser):
                logger.warning(
                    "WebSocket trackers-live rejected: %s - Anonymous - %s",
                    path,
                    get_client_ip(self.scope),
                )
                return
            self.room_group_name = f"live_track_{self.user.id}"
            self.user_sockets_group_name = user_sockets_group_name(self.user.id)
            await self.channel_layer.group_add(self.room_group_name, self.channel_name)
            # Also join the cross-consumer group used to force-disconnect this user's sockets
            # on auth revocation (logout, API key delete, OAuth revoke). Without this, a
            # revoked credential's live-tracking stream would keep working until the client
            # happened to reconnect on its own.
            await self.channel_layer.group_add(
                self.user_sockets_group_name, self.channel_name
            )
            await self.accept()
            logger.info(
                "WebSocket trackers-live connected: %s - %s - %s",
                path,
                get_user_identifier(self.scope),
                get_client_ip(self.scope),
            )
            await self.send(text_data=json.dumps({
                "module": "live_track",
                "type": "initial_state",
                "data": {},
            }))
        except Exception:
            logger.exception("WebSocket trackers-live connect error: %s", path)
            if self.room_group_name:
                await self.channel_layer.group_discard(
                    self.room_group_name, self.channel_name
                )
            if self.user_sockets_group_name:
                await self.channel_layer.group_discard(
                    self.user_sockets_group_name, self.channel_name
                )
            try:
                await self.close(code=1011)
            except Exception:
                pass

    async def disconnect(self, close_code):
        if hasattr(self, "room_group_name") and self.room_group_name:
            await self.channel_layer.group_discard(
                self.room_group_name, self.channel_name
            )
        if hasattr(self, "user_sockets_group_name") and self.user_sockets_group_name:
            await self.channel_layer.group_discard(
                self.user_sockets_group_name, self.channel_name
            )
        logger.info(
            "WebSocket trackers-live disconnected: %s - %s - code %s",
            self.scope.get("path", "unknown"),
            get_user_identifier(self.scope),
            close_code,
        )

    @_receive_rate_limiter.for_consumer()
    async def receive(self, text_data=None, bytes_data=None):
        """
        Answer an app-level ping from the client with a direct pong.

        This is deliberately a direct `self.send()` reply from `receive()`, not routed through
        `channel_layer.group_send` -- the same pattern `RealtimeConsumer.receive` already uses for
        its own ping/pong. It exists so the Android client's liveness watchdog
        (`StreamingSessionGuard`) can distinguish "the connection itself is alive and this
        consumer's event loop is actually processing messages" from "the tracker being watched
        simply hasn't reported a new point in a while" -- the two were previously conflated by
        keying staleness off `track_updated` recency, which misfired for any quiet/sparse tracker.
        """
        if not text_data:
            return
        try:
            payload = json.loads(text_data)
        except (ValueError, TypeError):
            return
        if is_ping_message(payload):
            await self.send(text_data=pong_payload(module="live_track"))

    async def live_track_track_updated(self, event):
        """Forward track_updated to the client (module/type/data JSON shape)."""
        data = event.get("data") or {}
        await self.send(text_data=json.dumps({
            "module": "live_track",
            "type": "track_updated",
            "data": data,
        }))

    async def force_disconnect(self, event):
        """Close this socket in response to auth revocation (logout, API key delete, OAuth revoke)."""
        logger.info(
            "WebSocket trackers-live force-disconnected: user %s - reason: %s",
            getattr(self.user, "id", "unknown"),
            event.get("reason", ""),
        )
        await self.close(code=4001)
