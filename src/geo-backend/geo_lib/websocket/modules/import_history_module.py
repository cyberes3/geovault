"""
Import history WebSocket module.
"""

import json

from channels.db import database_sync_to_async
from django.core.serializers.json import DjangoJSONEncoder

from api.models import ImportQueue
from geo_lib.websocket.base_module import BaseWebSocketModule
from geo_lib.logging.console import get_websocket_logger

logger = get_websocket_logger()


class ImportHistoryModule(BaseWebSocketModule):
    """WebSocket module for import history functionality."""

    @property
    def module_name(self) -> str:
        return "import_history"

    async def handle_message(self, message_type: str, data: dict) -> None:
        """Handle incoming messages for import history module."""
        if message_type == 'refresh':
            await self.send_initial_state()
        else:
            logger.warning(f"Unknown message type for import_history module: {message_type}")

    async def send_initial_state(self) -> None:
        """Send the current import history state to the client."""
        try:
            # Get current import history data
            history_data = await self.get_import_history_data()

            # Send initial state
            await self.send_to_client('initial_state', history_data)
        except Exception as e:
            logger.error(f"Error sending initial state to user {self.user.id}: {str(e)}")
            await self.send_to_client('error', {'message': 'Failed to load import history data'})

    @database_sync_to_async
    def get_import_history_data(self):
        """Get current import history data for the user."""
        # Get user's imported items from database
        user_items = ImportQueue.objects.filter(
            user=self.user,
            imported=True
        ).order_by('-timestamp').values(
            'id', 'original_filename', 'timestamp'
        )

        data = json.loads(json.dumps(list(user_items), cls=DjangoJSONEncoder))
        return data

    # WebSocket event handlers for channel layer events
    async def item_added(self, event):
        """Handle item_added event."""
        await self.send_to_client('item_added', event['data'])




