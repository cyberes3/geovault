"""
Process status WebSocket consumer for specific import item updates.
"""

import json

from channels.db import database_sync_to_async
from django.http import Http404

from api.models import ImportQueue
from api.utils.authorization import get_object_or_404_for_user
from geo_lib.logging.console import get_tagged_logger
from geo_lib.security.rate_limit import RedisRateLimiter
from geo_lib.utils.ip_utils import get_user_identifier
from geo_lib.websocket.modules.process_status_module import ProcessStatusModule
from geo_lib.websocket.ping_pong import is_ping_message, pong_payload

from api.ws_consumers.base import AuthenticatedJsonConsumer

_logger = get_tagged_logger()

# Lower than the realtime consumer's limit since request_logs/request_page hit the database;
# still well above any legitimate polling/pagination pattern from the process status UI.
_receive_rate_limiter = RedisRateLimiter(name='process_status_ws_receive', limit=30, window_seconds=10.0)


class ProcessStatusConsumer(AuthenticatedJsonConsumer):
    """WebSocket consumer for process status updates for a specific import item."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.item_id = None
        self.room_group_name = None

    def disconnect_log_context(self) -> str:
        return f" - Item: {self.item_id or 'Unknown'}"

    async def on_connect(self, path, client_ip):
        """Verify the user owns the requested import item, then accept and load its status module."""
        self.item_id = self.scope['url_route']['kwargs']['item_id']

        try:
            get_item = database_sync_to_async(get_object_or_404_for_user)
            item = await get_item(ImportQueue, self.user, id=self.item_id)
        except Http404:
            # Accept connection briefly to send error message, then close
            await self.accept()
            user_identifier = get_user_identifier(self.scope)
            _logger.warning(f"WebSocket connection rejected: {path} - {user_identifier} - {client_ip} - Item {self.item_id} not found")
            await self.send(text_data=json.dumps({
                'type': 'error',
                'data': {
                    'code': 404,
                    'message': 'Item not found'
                }
            }))
            await self.close(code=4004)  # 4004 = 404 Not Found
            return

        # Only accept the connection if the item exists and user owns it
        await self.accept()

        user_identifier = get_user_identifier(self.scope)
        _logger.info(f"WebSocket connected: {path} - {user_identifier} - {client_ip} - Item: {self.item_id}")

        # Create item-specific room group
        self.room_group_name = f"process_status_{self.user.id}_{self.item_id}"
        await self.join_group(self.room_group_name)

        # Load process status module and send initial state
        self.process_status_module = ProcessStatusModule(self, item)
        await self.process_status_module.send_initial_state()

    @_receive_rate_limiter.for_consumer()
    async def receive(self, text_data=None, bytes_data=None):
        """Handle messages received from WebSocket."""
        if text_data:
            try:
                data = json.loads(text_data)
                message_type = data.get('type')
                message_data = data.get('data', {})

                if is_ping_message(data):
                    await self.send(text_data=pong_payload())
                elif message_type == 'refresh':
                    await self.process_status_module.send_initial_state()
                elif message_type == 'request_logs':
                    after_id = message_data.get('after_id')
                    await self.process_status_module.send_logs(after_id)
                elif message_type == 'request_page':
                    page = message_data.get('page', 1)
                    page_size = message_data.get('page_size', 50)
                    await self.process_status_module.send_page(page, page_size)
                else:
                    _logger.warning(f"Unknown message type for process status: {message_type}")
            except json.JSONDecodeError:
                _logger.warning(f"Invalid JSON received from user {self.user.id}")
        elif bytes_data:
            _logger.warning("Binary data received but not supported")

    # Event handlers for WebSocket events
    async def status_updated(self, event):
        """Handle status update events."""
        await self.process_status_module.handle_status_updated(event['data'])

    async def logs_added(self, event):
        """Handle new log entries."""
        await self.process_status_module.handle_logs_added(event['data'])

    async def logs_batch_added(self, event):
        """Handle new log batch entries."""
        await self.process_status_module.handle_logs_batch_added(event['data'])

    async def item_completed(self, event):
        """Handle item completion."""
        await self.process_status_module.handle_item_completed(event['data'])

    async def item_failed(self, event):
        """Handle item failure."""
        await self.process_status_module.handle_item_failed(event['data'])

    async def item_deleted(self, event):
        """Handle item deletion event."""
        await self.process_status_module.handle_item_deleted(event['data'])
        # Close the connection since the item no longer exists
        await self.close()

    async def duplicates_updated(self, event):
        """Handle duplicates updated event."""
        await self.process_status_module.handle_duplicates_updated(event['data'])
