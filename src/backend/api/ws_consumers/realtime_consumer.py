"""
Realtime WebSocket consumer for global real-time updates.
"""

import json

from channels.generic.websocket import AsyncWebsocketConsumer
from django.contrib.auth.models import AnonymousUser

from geo_lib.websocket.modules.delete_job_module import DeleteJobModule
from geo_lib.websocket.modules.import_history_module import ImportHistoryModule
from geo_lib.websocket.modules.import_queue_module import ImportQueueModule
from geo_lib.websocket.modules.process_job_module import ProcessJobModule
from geo_lib.websocket.modules.bulk_import_job_module import BulkImportJobModule
from geo_lib.websocket.modules.bulk_delete_job_module import BulkDeleteJobModule
from geo_lib.logging.console import get_websocket_logger
from geo_lib.utils.ip_utils import get_client_ip, get_user_identifier

logger = get_websocket_logger()


class RealtimeConsumer(AsyncWebsocketConsumer):
    """Global WebSocket consumer for realtime updates."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.modules = {}

    def _load_modules(self):
        """Load all available WebSocket modules."""
        self.modules['import_queue'] = ImportQueueModule(self)
        self.modules['import_history'] = ImportHistoryModule(self)
        self.modules['process_job'] = ProcessJobModule(self)
        self.modules['delete_job'] = DeleteJobModule(self)
        self.modules['bulk_import_job'] = BulkImportJobModule(self)
        self.modules['bulk_delete_job'] = BulkDeleteJobModule(self)
        # Add more modules here as they are created

    async def connect(self):
        """Handle WebSocket connection."""
        import traceback
        
        path = self.scope.get('path', 'unknown')
        client_ip = 'unknown'
        user_identifier = 'unknown'
        
        try:
            # Get user from scope (AuthMiddlewareStack should set this)
            self.user = self.scope.get("user")
            if self.user is None:
                # If user is not in scope, default to AnonymousUser
                self.user = AnonymousUser()
            
            client_ip = get_client_ip(self.scope)

            # Reject connection if user is not authenticated
            if isinstance(self.user, AnonymousUser):
                logger.warning(f"WebSocket connection rejected: {path} - Anonymous@{client_ip}")
                # Don't accept the connection - just return without accepting
                # This will cause the connection to fail gracefully
                return

            # Create user-specific room group
            self.room_group_name = f"realtime_{self.user.id}"

            # Join room group
            await self.channel_layer.group_add(
                self.room_group_name,
                self.channel_name
            )

            await self.accept()
            
            # Log successful connection
            user_identifier = get_user_identifier(self.scope)
            logger.info(f"WebSocket connected: {path} - {user_identifier}@{client_ip}")

            # Load modules now that user is available
            self._load_modules()

            # Send initial state for all modules
            for module in self.modules.values():
                await module.send_initial_state()
                
        except Exception as e:
            # Log the full traceback for debugging
            traceback_str = traceback.format_exc()
            user_identifier = get_user_identifier(self.scope)
            logger.error(f"WebSocket connection error: {path} - {user_identifier}@{client_ip}\n{traceback_str}")
            
            # Try to accept and close the connection with error code if not already accepted
            try:
                # Check if connection was already accepted by checking if room_group_name exists
                if not hasattr(self, 'room_group_name') or not self.room_group_name:
                    # Connection not accepted yet, just don't accept it
                    return
                else:
                    # Connection was accepted, close it properly
                    await self.close(code=1011)  # 1011 = Internal Server Error
            except Exception as close_error:
                # Log error when closing connection after initial error
                logger.warning(f"Error closing WebSocket connection after error: {path} - {user_identifier}@{client_ip}: {str(close_error)}")

    async def disconnect(self, close_code):
        """Handle WebSocket disconnection."""
        import traceback
        
        path = self.scope.get('path', 'unknown')
        client_ip = 'unknown'
        user_identifier = 'unknown'
        
        try:
            client_ip = get_client_ip(self.scope)
            user_identifier = get_user_identifier(self.scope)
            logger.info(f"WebSocket disconnected: {path} - {user_identifier}@{client_ip} - Close code: {close_code}")
            
            # Leave room group if it was created
            if hasattr(self, 'room_group_name') and self.room_group_name:
                await self.channel_layer.group_discard(
                    self.room_group_name,
                    self.channel_name
                )
        except Exception as e:
            # Log the error but don't raise - we're already disconnecting
            traceback_str = traceback.format_exc()
            user_identifier = get_user_identifier(self.scope)
            logger.error(f"WebSocket disconnect error: {path} - {user_identifier}@{client_ip}\n{traceback_str}")

    async def receive(self, text_data=None, bytes_data=None):
        """Handle messages received from WebSocket."""
        if text_data:
            try:
                data = json.loads(text_data)
                module_name = data.get('module')
                message_type = data.get('type')
                message_data = data.get('data', {})

                if message_type == 'ping':
                    # Send pong response in the same format as other messages
                    await self.send(text_data=self.encode_json({
                        'module': 'ping',
                        'type': 'pong',
                        'data': {}
                    }))
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



