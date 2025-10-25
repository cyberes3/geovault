"""
Upload status WebSocket consumer for specific import item updates.
"""

import json
import logging

from channels.generic.websocket import AsyncWebsocketConsumer
from django.contrib.auth.models import AnonymousUser

from geo_lib.websocket.modules.upload_status_module import UploadStatusModule

logger = logging.getLogger(__name__)


class UploadStatusConsumer(AsyncWebsocketConsumer):
    """WebSocket consumer for upload status updates for a specific import item."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.item_id = None
        self.room_group_name = None

    async def connect(self):
        """Handle WebSocket connection."""
        # Get user from scope
        self.user = self.scope["user"]

        # Reject connection if user is not authenticated
        if isinstance(self.user, AnonymousUser):
            await self.close()
            return

        # Get item_id from URL parameters
        self.item_id = self.scope['url_route']['kwargs']['item_id']

        try:
            # Verify user owns this import item using async database query
            from data.models import ImportQueue
            from asgiref.sync import sync_to_async

            # Use sync_to_async to make the database query async-safe
            get_item = sync_to_async(ImportQueue.objects.get)
            item = await get_item(id=self.item_id, user=self.user)

            # Only accept the connection if the item exists and user owns it
            await self.accept()

            # Create item-specific room group
            self.room_group_name = f"upload_status_{self.user.id}_{self.item_id}"

            # Join room group
            await self.channel_layer.group_add(
                self.room_group_name,
                self.channel_name
            )

            # Load upload status module
            self.upload_status_module = UploadStatusModule(self, item)

            # Send initial state
            await self.upload_status_module.send_initial_state()

        except ImportQueue.DoesNotExist:
            # Accept connection briefly to send error message, then close
            await self.accept()
            await self.send(text_data=json.dumps({
                'type': 'error',
                'data': {
                    'code': 404,
                    'message': 'Item not found'
                }
            }))
            await self.close(code=4004)  # 4004 = 404 Not Found
        except Exception as e:
            logger.error(f"Error in UploadStatusConsumer connect: {str(e)}")
            await self.close()

    async def disconnect(self, close_code):
        """Handle WebSocket disconnection."""
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
                    await self.send(text_data=json.dumps({'type': 'pong'}))
                elif message_type == 'refresh':
                    await self.upload_status_module.send_initial_state()
                elif message_type == 'request_logs':
                    after_id = message_data.get('after_id')
                    await self.upload_status_module.send_logs(after_id)
                elif message_type == 'request_page':
                    page = message_data.get('page', 1)
                    page_size = message_data.get('page_size', 50)
                    await self.upload_status_module.send_page(page, page_size)
                else:
                    logger.warning(f"Unknown message type for upload status: {message_type}")
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
        await self.upload_status_module.handle_status_updated(event['data'])

    async def logs_added(self, event):
        """Handle new log entries."""
        await self.upload_status_module.handle_logs_added(event['data'])

    async def item_completed(self, event):
        """Handle item completion."""
        await self.upload_status_module.handle_item_completed(event['data'])

    async def item_failed(self, event):
        """Handle item failure."""
        await self.upload_status_module.handle_item_failed(event['data'])

    async def item_deleted(self, event):
        """Handle item deletion event."""
        await self.upload_status_module.handle_item_deleted(event['data'])
        # Close the connection since the item no longer exists
        await self.close()
