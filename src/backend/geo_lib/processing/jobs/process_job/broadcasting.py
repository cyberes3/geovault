"""
WebSocket broadcast helpers for process job status updates.

These are pure functions (no job/tracker state) so they can be called from anywhere in the
process job pipeline without threading `self` through; they only need the identifiers and
payload for the event being broadcast.
"""

from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer


def broadcast_to_import_queue_module(user_id: int, event_type: str, data: dict) -> None:
    """Broadcast a WebSocket event to the `import_queue` module (the upload queue list view)."""
    channel_layer = get_channel_layer()
    if channel_layer:
        async_to_sync(channel_layer.group_send)(
            f"realtime_{user_id}",
            {
                'type': f'import_queue_{event_type}',
                'data': data
            }
        )


def broadcast_item_added(user_id: int, import_queue_id: int) -> None:
    """Broadcast a WebSocket event when a new item is added to the import queue."""
    broadcast_to_import_queue_module(user_id, 'item_added', {'id': import_queue_id})


def broadcast_to_process_status_module(user_id: int, import_queue_id: int, event_type: str, data: dict) -> None:
    """Broadcast a WebSocket event to the `process_status` module for one specific item."""
    channel_layer = get_channel_layer()
    if channel_layer:
        async_to_sync(channel_layer.group_send)(
            f"process_status_{user_id}_{import_queue_id}",
            {
                'type': event_type,
                'data': data
            }
        )
