"""
WebSocket utilities for import operations.
Handles broadcasting import events to connected clients.
"""

from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer

from api.models import ImportQueue


def broadcast_item_imported(user_id: int, item_id: int):
    """Broadcast WebSocket event when an item is imported."""
    channel_layer = get_channel_layer()
    if channel_layer:
        # Get item details for history broadcast
        try:
            item = ImportQueue.objects.get(id=item_id)
            item_data = {
                'id': item_id,
                'original_filename': item.original_filename,
                'timestamp': item.timestamp.isoformat()
            }
        except ImportQueue.DoesNotExist:
            item_data = {'id': item_id}

        # Broadcast to import queue module
        async_to_sync(channel_layer.group_send)(
            f"realtime_{user_id}",
            {
                'type': 'import_queue_item_imported',
                'data': {'id': item_id}
            }
        )

        # Broadcast to import history module
        async_to_sync(channel_layer.group_send)(
            f"realtime_{user_id}",
            {
                'type': 'import_history_item_added',
                'data': item_data
            }
        )
