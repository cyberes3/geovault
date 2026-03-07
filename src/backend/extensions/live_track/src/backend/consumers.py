"""WebSocket consumer for track-only updates (ws/extensions/live-track/trackers-live/)."""

import json
import logging

from channels.generic.websocket import AsyncWebsocketConsumer
from django.contrib.auth.models import AnonymousUser

from geo_lib.logging.console import get_tagged_logger
from geo_lib.utils.ip_utils import get_client_ip, get_user_identifier

logger = get_tagged_logger(__name__)


class LiveTrackOnlyConsumer(AsyncWebsocketConsumer):
    """
    WebSocket consumer that only receives live_track track_updated events.
    Joins live_track_{user_id} so it does not receive import_queue, process_job, etc.
    """

    async def connect(self):
        path = self.scope.get("path", "unknown")
        self.room_group_name = None
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
            await self.channel_layer.group_add(self.room_group_name, self.channel_name)
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
            try:
                await self.close(code=1011)
            except Exception:
                pass

    async def disconnect(self, close_code):
        if hasattr(self, "room_group_name") and self.room_group_name:
            await self.channel_layer.group_discard(
                self.room_group_name, self.channel_name
            )
        logger.info(
            "WebSocket trackers-live disconnected: %s - %s - code %s",
            self.scope.get("path", "unknown"),
            get_user_identifier(self.scope),
            close_code,
        )

    async def live_track_track_updated(self, event):
        """Forward track_updated to the client (same JSON shape as full realtime)."""
        data = event.get("data") or {}
        await self.send(text_data=json.dumps({
            "module": "live_track",
            "type": "track_updated",
            "data": data,
        }))
