"""
WebSocket consumers for real-time updates.
"""

import json
import logging
from channels.generic.websocket import AsyncWebsocketConsumer
from django.contrib.auth.models import AnonymousUser
from geo_lib.websocket.modules.import_queue_module import ImportQueueModule

logger = logging.getLogger(__name__)


class RealtimeConsumer(AsyncWebsocketConsumer):
    """Global WebSocket consumer for realtime updates."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.modules = {}

    def _load_modules(self):
        """Load all available WebSocket modules."""
        # Import queue module
        self.modules['import_queue'] = ImportQueueModule(self)
        # Add more modules here as they are created

    async def connect(self):
        """Handle WebSocket connection."""
        # Get user from scope
        self.user = self.scope["user"]
        
        # Reject connection if user is not authenticated
        if isinstance(self.user, AnonymousUser):
            await self.close()
            return

        # Create user-specific room group
        self.room_group_name = f"realtime_{self.user.id}"
        
        # Join room group
        await self.channel_layer.group_add(
            self.room_group_name,
            self.channel_name
        )
        
        await self.accept()
        
        # Load modules now that user is available
        self._load_modules()
        
        # Send initial state for all modules
        for module in self.modules.values():
            await module.send_initial_state()

    async def disconnect(self, close_code):
        """Handle WebSocket disconnection."""
        # Leave room group
        await self.channel_layer.group_discard(
            self.room_group_name,
            self.channel_name
        )

    async def receive(self, text_data=None, bytes_data=None):
        """Handle messages received from WebSocket."""
        if text_data:
            try:
                data = json.loads(text_data)
                module_name = data.get('module')
                message_type = data.get('type')
                message_data = data.get('data', {})
                
                if message_type == 'ping':
                    await self.send(text_data=json.dumps({'type': 'pong'}))
                elif module_name in self.modules:
                    await self.modules[module_name].handle_message(message_type, message_data)
                else:
                    logger.warning(f"Unknown module: {module_name}")
            except json.JSONDecodeError:
                logger.warning(f"Invalid JSON received from user {self.user.id}")
        elif bytes_data:
            logger.warning("Binary data received but not supported")

    def encode_json(self, data):
        """Encode data as JSON."""
        return json.dumps(data)

    # Channel layer event handlers - route to appropriate modules
    async def import_queue_item_added(self, event):
        """Route import queue item_added event to module."""
        await self.modules['import_queue'].item_added(event)

    async def import_queue_item_deleted(self, event):
        """Route import queue item_deleted event to module."""
        await self.modules['import_queue'].item_deleted(event)

    async def import_queue_items_deleted(self, event):
        """Route import queue items_deleted event to module."""
        await self.modules['import_queue'].items_deleted(event)

    async def import_queue_status_updated(self, event):
        """Route import queue status_updated event to module."""
        await self.modules['import_queue'].status_updated(event)

    async def import_queue_item_imported(self, event):
        """Route import queue item_imported event to module."""
        await self.modules['import_queue'].item_imported(event)
