"""
Realtime WebSocket consumer for global real-time updates.
"""

import json

from geo_lib.logging.console import get_tagged_logger
from geo_lib.security.rate_limit import RedisRateLimiter
from geo_lib.utils.ip_utils import get_user_identifier
from geo_lib.websocket.ping_pong import is_ping_message, pong_payload
from geo_lib.websocket.modules.bulk_delete_job_module import BulkDeleteJobModule
from geo_lib.websocket.modules.bulk_import_job_module import BulkImportJobModule
from geo_lib.websocket.modules.delete_job_module import DeleteJobModule
from geo_lib.websocket.modules.import_history_module import ImportHistoryModule
from geo_lib.websocket.modules.import_queue_module import ImportQueueModule
from geo_lib.websocket.modules.process_job_module import ProcessJobModule
from geo_lib.websocket.registry import get_registered_websocket_modules

from api.ws_consumers.base import AuthenticatedJsonConsumer

_logger = get_tagged_logger()

# Ping is expected every 30s; generous headroom above that for legitimate bursts of module
# actions (e.g. subscribing to several jobs at once) while still capping a flooding client.
_receive_rate_limiter = RedisRateLimiter(name='realtime_ws_receive', limit=60, window_seconds=10.0)


class RealtimeConsumer(AuthenticatedJsonConsumer):
    """Global WebSocket consumer for realtime updates."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.modules = {}

    def _load_modules(self):
        """Load built-in and extension-registered WebSocket modules."""
        self.modules['import_queue'] = ImportQueueModule(self)
        self.modules['import_history'] = ImportHistoryModule(self)
        self.modules['process_job'] = ProcessJobModule(self)
        self.modules['delete_job'] = DeleteJobModule(self)
        self.modules['bulk_import_job'] = BulkImportJobModule(self)
        self.modules['bulk_delete_job'] = BulkDeleteJobModule(self)
        for name, module_class in get_registered_websocket_modules():
            self.modules[name] = module_class(self)

    async def on_connect(self, path, client_ip):
        """Join the user's realtime room, accept the connection, and load modules."""
        self.room_group_name = f"realtime_{self.user.id}"
        await self.join_group(self.room_group_name)
        await self.accept()

        user_identifier = get_user_identifier(self.scope)
        _logger.info(f"WebSocket connected: {path} - {user_identifier} - {client_ip}")

        # Load modules now that user is available
        self._load_modules()

        # Send initial state for all modules
        for module in self.modules.values():
            await module.send_initial_state()

    @_receive_rate_limiter.for_consumer()
    async def receive(self, text_data=None, bytes_data=None):
        """Handle messages received from WebSocket."""
        if text_data:
            try:
                data = json.loads(text_data)
                module_name = data.get('module')
                message_type = data.get('type')
                message_data = data.get('data', {})

                if is_ping_message(data):
                    await self.send(text_data=pong_payload(module='ping'))
                elif module_name in self.modules:
                    await self.modules[module_name].handle_message(message_type, message_data)
                else:
                    _logger.warning(f"Unknown module: {module_name}")
            except json.JSONDecodeError:
                _logger.warning(f"Invalid JSON received from user {self.user.id}")
        elif bytes_data:
            _logger.warning("Binary data received but not supported")

    async def live_track_track_updated(self, event):
        """No-op: live_track updates use the trackers-live consumer, not this realtime channel."""
        pass

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
