"""
Process status WebSocket consumer for specific import item updates.
"""

import json

from channels.generic.websocket import AsyncWebsocketConsumer
from django.contrib.auth.models import AnonymousUser

from geo_lib.websocket.modules.process_status_module import ProcessStatusModule
from geo_lib.logging.console import get_websocket_logger
from geo_lib.utils.ip_utils import get_client_ip, get_user_identifier

logger = get_websocket_logger()


class ProcessStatusConsumer(AsyncWebsocketConsumer):
    """WebSocket consumer for process status updates for a specific import item."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.item_id = None
        self.room_group_name = None

    async def connect(self):
        """Handle WebSocket connection."""
        import traceback
        
        path = self.scope.get('path', 'unknown')
        client_ip = 'unknown'
        user_identifier = 'unknown'
        
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

        # Get item_id from URL parameters
        self.item_id = self.scope['url_route']['kwargs']['item_id']

        try:
            # Verify user owns this import item using async database query
            from api.models import ImportQueue
            from asgiref.sync import sync_to_async

            # Use sync_to_async to make the database query async-safe
            get_item = sync_to_async(ImportQueue.objects.get)
            item = await get_item(id=self.item_id, user=self.user)

            # Only accept the connection if the item exists and user owns it
            await self.accept()
            
            # Log successful connection
            user_identifier = get_user_identifier(self.scope)
            logger.info(f"WebSocket connected: {path} - {user_identifier}@{client_ip} - Item: {self.item_id}")

            # Create item-specific room group
            self.room_group_name = f"process_status_{self.user.id}_{self.item_id}"

            # Join room group
            await self.channel_layer.group_add(
                self.room_group_name,
                self.channel_name
            )

            # Load process status module
            self.process_status_module = ProcessStatusModule(self, item)

            # Send initial state
            await self.process_status_module.send_initial_state()

        except ImportQueue.DoesNotExist:
            # Accept connection briefly to send error message, then close
            await self.accept()
            user_identifier = get_user_identifier(self.scope)
            logger.warning(f"WebSocket connection rejected: {path} - {user_identifier}@{client_ip} - Item {self.item_id} not found")
            await self.send(text_data=json.dumps({
                'type': 'error',
                'data': {
                    'code': 404,
                    'message': 'Item not found'
                }
            }))
            await self.close(code=4004)  # 4004 = 404 Not Found
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
                logger.warning(f"Error closing WebSocket connection after error: {path} - {user_identifier}@{client_ip} - Item: {self.item_id}: {str(close_error)}")

    async def disconnect(self, close_code):
        """Handle WebSocket disconnection."""
        path = self.scope.get('path', 'unknown')
        client_ip = get_client_ip(self.scope)
        user_identifier = get_user_identifier(self.scope)
        item_id = getattr(self, 'item_id', 'Unknown')
        logger.info(f"WebSocket disconnected: {path} - {user_identifier}@{client_ip} - Item: {item_id} - Close code: {close_code}")
        
        if self.room_group_name:
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
                message_type = data.get('type')
                message_data = data.get('data', {})

                if message_type == 'ping':
                    # Send pong response in the same format as other messages
                    await self.send(text_data=json.dumps({
                        'type': 'pong',
                        'data': {}
                    }))
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
                    logger.warning(f"Unknown message type for process status: {message_type}")
            except json.JSONDecodeError:
                logger.warning(f"Invalid JSON received from user {self.user.id}")
        elif bytes_data:
            logger.warning("Binary data received but not supported")

    def encode_json(self, data):
        """Encode data as JSON."""
        return json.dumps(data)

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
