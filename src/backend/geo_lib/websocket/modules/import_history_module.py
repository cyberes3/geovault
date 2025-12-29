import json
import math

from channels.db import database_sync_to_async
from django.core.serializers.json import DjangoJSONEncoder

from api.models import ImportQueue
from geo_lib.logging.console import get_tagged_logger
from geo_lib.websocket.base_module import BaseWebSocketModule

logger = get_tagged_logger('websocket')


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
        """Send the current import history state to the client (page 1 only)."""
        history_data = await self.get_import_history_data(page=1, page_size=10)
        await self.send_to_client('initial_state', history_data)

    @database_sync_to_async
    def get_import_history_data(self, page=1, page_size=10):
        """
        Get paginated import history data for the user.
        
        Args:
            page: Page number (default: 1)
            page_size: Items per page (default: 10)
            
        Returns:
            Dictionary with items and pagination metadata
        """
        # Get user's imported items from database (exclude replacement uploads)
        queryset = ImportQueue.objects.filter(
            user=self.user,
            imported=True,
            replacement__isnull=True
        ).order_by('-timestamp')
        
        # Calculate pagination
        total_items = queryset.count()
        total_pages = math.ceil(total_items / page_size) if total_items > 0 else 0
        
        # Get paginated items
        start = (page - 1) * page_size
        end = start + page_size
        user_items = queryset.values('id', 'original_filename', 'timestamp')[start:end]
        
        # Convert to list and format timestamps
        items_list = []
        for item in user_items:
            items_list.append({
                'id': item['id'],
                'original_filename': item['original_filename'],
                'timestamp': item['timestamp'].isoformat() if item['timestamp'] else None
            })
        
        # Return paginated structure
        return {
            'items': items_list,
            'pagination': {
                'page': page,
                'page_size': page_size,
                'total_items': total_items,
                'total_pages': total_pages,
                'has_next': page < total_pages,
                'has_previous': page > 1
            }
        }

    # WebSocket event handlers for channel layer events
    async def item_added(self, event):
        """Handle item_added event."""
        await self.send_to_client('item_added', event['data'])
