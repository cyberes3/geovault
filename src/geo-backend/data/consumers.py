"""
WebSocket consumers for real-time updates.
"""

import json
import logging

from channels.generic.websocket import AsyncWebsocketConsumer
from django.contrib.auth.models import AnonymousUser

from geo_lib.websocket.modules.delete_job_module import DeleteJobModule
from geo_lib.websocket.modules.import_history_module import ImportHistoryModule
from geo_lib.websocket.modules.import_queue_module import ImportQueueModule
from geo_lib.websocket.modules.upload_job_module import UploadJobModule

logger = logging.getLogger(__name__)


class RealtimeConsumer(AsyncWebsocketConsumer):
    """Global WebSocket consumer for realtime updates."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.modules = {}

    def _load_modules(self):
        """Load all available WebSocket modules."""
        self.modules['import_queue'] = ImportQueueModule(self)
        self.modules['import_history'] = ImportHistoryModule(self)
        self.modules['upload_job'] = UploadJobModule(self)
        self.modules['delete_job'] = DeleteJobModule(self)
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

    # Dynamic event routing - automatically route events to modules
    def __getattr__(self, name):
        """Dynamically route events to appropriate modules."""
        # Check if modules are loaded (safety check for timing issues)
        if not hasattr(self, 'modules') or not self.modules:
            raise AttributeError(f"'{self.__class__.__name__}' object has no attribute '{name}'")

        # Find the module that matches the beginning of the event name
        for module_name in self.modules.keys():
            if name.startswith(f"{module_name}_"):
                # Extract the event name after the module prefix
                event_name = name[len(module_name) + 1:]  # +1 for the underscore
                module = self.modules[module_name]

                if hasattr(module, event_name):
                    # Return a wrapper that calls the module method
                    async def event_handler(event):
                        return await getattr(module, event_name)(event)

                    return event_handler

        # If not a module event, raise AttributeError
        raise AttributeError(f"'{self.__class__.__name__}' object has no attribute '{name}'")
