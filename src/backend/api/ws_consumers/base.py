"""
Shared base class for authenticated WebSocket consumers.

Centralizes the connect/disconnect/error-handling boilerplate common to all of our WebSocket
consumers: rejecting anonymous connections, joining the per-user force-disconnect group,
tracking joined channel groups so they can be left cleanly on disconnect, and logging
connection lifecycle events consistently.
"""

import traceback

from channels.generic.websocket import AsyncWebsocketConsumer
from django.contrib.auth.models import AnonymousUser

from geo_lib.logging.console import get_tagged_logger
from geo_lib.utils.ip_utils import get_client_ip, get_user_identifier
from geo_lib.websocket.force_disconnect import user_sockets_group_name

_logger = get_tagged_logger()


class AuthenticatedJsonConsumer(AsyncWebsocketConsumer):
    """
    Base WebSocket consumer that authenticates the connection, joins the cross-consumer
    force-disconnect group, and handles the accept/close/error bookkeeping shared by every
    consumer.

    Subclasses implement `on_connect(path, client_ip)` to perform their own setup (e.g. looking
    up and verifying ownership of a resource, loading modules, sending initial state) once
    authentication has succeeded. Subclasses should call `self.join_group(name)` for any
    channel groups they join, so `disconnect()` can leave them automatically, and must call
    `self.accept()` (inherited from this class) to accept the connection.
    """

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.user = None
        self._joined_groups: list[str] = []
        self._accepted = False

    async def on_connect(self, path: str, client_ip: str) -> None:
        """Hook for subclasses: perform consumer-specific setup after authentication succeeds."""
        raise NotImplementedError

    def disconnect_log_context(self) -> str:
        """Optional extra context (e.g. a resource id) appended to the disconnect log line."""
        return ""

    async def accept(self, subprotocol=None):
        await super().accept(subprotocol=subprotocol)
        self._accepted = True

    async def join_group(self, group_name: str) -> None:
        """Join a channel group and remember it so it's left automatically on disconnect."""
        await self.channel_layer.group_add(group_name, self.channel_name)
        self._joined_groups.append(group_name)

    async def connect(self):
        """Authenticate the connection, then delegate consumer-specific setup to `on_connect()`."""
        path = self.scope.get('path', 'unknown')
        client_ip = get_client_ip(self.scope)

        self.user = self.scope.get('user') or AnonymousUser()
        if isinstance(self.user, AnonymousUser):
            _logger.warning(f"WebSocket connection rejected: {path} - Anonymous - {client_ip}")
            # Don't accept the connection - this causes it to fail gracefully.
            return

        try:
            await self.join_group(user_sockets_group_name(self.user.id))
            await self.on_connect(path, client_ip)
        except Exception:
            _logger.error(f"WebSocket connection error: {path} - {get_user_identifier(self.scope)} - {client_ip}: {traceback.format_exc()}")
            try:
                if self._accepted:
                    await self.close(code=1011)  # 1011 = Internal Server Error
            except Exception:
                _logger.error(f"WebSocket close-after-error failed: {path} - {client_ip}: {traceback.format_exc()}")

    async def disconnect(self, close_code):
        """Leave all joined channel groups and log the disconnect."""
        path = self.scope.get('path', 'unknown')
        client_ip = get_client_ip(self.scope)
        user_identifier = get_user_identifier(self.scope)
        _logger.info(
            f"WebSocket disconnected: {path} - {user_identifier} - {client_ip}"
            f"{self.disconnect_log_context()} - Close code: {close_code}"
        )

        try:
            for group_name in self._joined_groups:
                await self.channel_layer.group_discard(group_name, self.channel_name)
        except Exception:
            _logger.error(f"WebSocket disconnect error: {path} - {user_identifier} - {client_ip}: {traceback.format_exc()}")

    async def force_disconnect(self, event):
        """Close this socket in response to auth revocation (logout, API key delete, OAuth revoke)."""
        _logger.info(f"WebSocket force-disconnected: user {getattr(self.user, 'id', 'unknown')} - reason: {event.get('reason', '')}")
        await self.close(code=4001)
