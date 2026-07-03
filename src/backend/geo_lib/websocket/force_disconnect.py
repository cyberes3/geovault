"""
Force-disconnect a user's open WebSocket connections.

When a user's session/credential is invalidated out from under an already-open socket (logout,
API key deletion, OAuth token revocation), the socket itself doesn't know that: WebSocket auth
only runs once, at connect() time. Without this, a revoked credential's realtime connections
would keep working until the client happens to reconnect.

Every consumer joins a shared `user_sockets_{user_id}` group at connect time (in addition to
whatever per-feature group it also joins), specifically so a single call here reaches every open
socket for that user regardless of which consumer/route it belongs to.
"""
from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer

from geo_lib.logging.console import get_tagged_logger

_logger = get_tagged_logger('websocket')

# Channels maps "." to "_" when dispatching an event to a consumer method, so this event is
# delivered to a `force_disconnect(self, event)` handler on the receiving consumer.
FORCE_DISCONNECT_EVENT_TYPE = "force.disconnect"


def user_sockets_group_name(user_id) -> str:
    """Channel layer group every socket belonging to `user_id` joins, across all consumers."""
    return f"user_sockets_{user_id}"


class WebSocketForceDisconnector:
    """Sends a force-disconnect event to every WebSocket connection a user currently has open."""

    @staticmethod
    def disconnect_user(user_id, reason: str = "") -> None:
        """
        Synchronous entry point, safe to call from ordinary Django views and signal handlers
        (the common case for auth-revocation call sites).
        """
        channel_layer = get_channel_layer()
        if channel_layer is None:
            _logger.warning("No channel layer configured; cannot force-disconnect user %s", user_id)
            return
        async_to_sync(channel_layer.group_send)(
            user_sockets_group_name(user_id),
            {"type": FORCE_DISCONNECT_EVENT_TYPE, "reason": reason},
        )

    @staticmethod
    async def disconnect_user_async(user_id, reason: str = "") -> None:
        """Async entry point for use from within consumers/async code."""
        channel_layer = get_channel_layer()
        if channel_layer is None:
            _logger.warning("No channel layer configured; cannot force-disconnect user %s", user_id)
            return
        await channel_layer.group_send(
            user_sockets_group_name(user_id),
            {"type": FORCE_DISCONNECT_EVENT_TYPE, "reason": reason},
        )
