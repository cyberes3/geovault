"""
Import queue WebSocket module.
"""

import json
import logging

from channels.db import database_sync_to_async
from django.core.serializers.json import DjangoJSONEncoder

from api.models import ImportQueue
from geo_lib.processing.status_tracker import status_tracker
from geo_lib.websocket.base_module import BaseWebSocketModule

logger = logging.getLogger(__name__)


class ImportQueueModule(BaseWebSocketModule):
    """WebSocket module for import queue functionality."""

    @property
    def module_name(self) -> str:
        return "import_queue"

    async def handle_message(self, message_type: str, data: dict) -> None:
        """Handle incoming messages for import queue module."""
        if message_type == 'refresh':
            await self.send_initial_state()
        else:
            logger.warning(f"Unknown message type for import_queue module: {message_type}")

    async def send_initial_state(self) -> None:
        """Send the current import queue state to the client."""
        try:
            # Get current import queue data
            queue_data = await self.get_import_queue_data()

            # Send initial state
            await self.send_to_client('initial_state', queue_data)
        except Exception as e:
            logger.error(f"Error sending initial state to user {self.user.id}: {str(e)}")
            await self.send_to_client('error', {'message': 'Failed to load import queue data'})

    @database_sync_to_async
    def get_import_queue_data(self):
        """Get current import queue data for the user."""
        # Get user items from database
        user_items = ImportQueue.objects.filter(
            user=self.user,
            imported=False
        ).order_by('-timestamp').values(
            'id', 'geofeatures', 'original_filename', 'geojson_hash',
            'log_id', 'timestamp', 'imported', 'unparsable'
        )

        data = json.loads(json.dumps(list(user_items), cls=DjangoJSONEncoder))

        # Get all active processing jobs for this user
        user_jobs = status_tracker.get_user_jobs(self.user.id)
        active_job_ids = {
            job.import_queue_id for job in user_jobs
            if job.status.value == 'processing' and job.import_queue_id
        }

        # Build a map of geojson_hash to items for duplicate detection
        hash_to_items = {}
        for item in data:
            if item.get('geojson_hash'):
                if item['geojson_hash'] not in hash_to_items:
                    hash_to_items[item['geojson_hash']] = []
                hash_to_items[item['geojson_hash']].append(item)

        # Check for imported files with same hash
        imported_hashes = {}
        if hash_to_items:
            imported_items = ImportQueue.objects.filter(
                user=self.user,
                imported=True,
                geojson_hash__in=list(hash_to_items.keys())
            ).values('geojson_hash', 'original_filename')

            for imported_item in imported_items:
                imported_hashes[imported_item['geojson_hash']] = imported_item['original_filename']

        # Process each item
        for i, item in enumerate(data):
            count = len(item['geofeatures'])

            # Check if this item is currently being processed
            item['processing'] = item['id'] in active_job_ids

            # Also consider items with empty geofeatures as processing if they were created recently
            if not item['processing'] and count == 0 and not item.get('unparsable'):
                from django.utils import timezone
                from datetime import timedelta

                # If item was created within the last 10 seconds, consider it as processing
                item_timestamp = item['timestamp']
                if isinstance(item_timestamp, str):
                    from datetime import datetime
                    item_timestamp = datetime.fromisoformat(item_timestamp.replace('Z', '+00:00'))

                time_since_creation = timezone.now() - item_timestamp
                if time_since_creation < timedelta(seconds=10):
                    item['processing'] = True

            # Check if there's an error in the geofeatures or if marked as unparsable
            if item.get('unparsable') or (count == 1 and item['geofeatures'] and isinstance(item['geofeatures'][0], dict) and 'error' in item['geofeatures'][0]):
                item['feature_count'] = 0
                item['processing_failed'] = True
            elif count == 0 and item['processing']:
                item['feature_count'] = -1  # Special value to indicate processing
                item['processing_failed'] = False
            else:
                item['feature_count'] = count
                item['processing_failed'] = False

            # Check for duplicate status
            item['duplicate_status'] = None
            if item.get('geojson_hash'):
                geojson_hash = item['geojson_hash']
                items_with_same_hash = hash_to_items.get(geojson_hash, [])

                # Check if there are other items in queue with same hash (uploaded earlier)
                earlier_items = [
                    other for other in items_with_same_hash
                    if other['id'] != item['id'] and other['timestamp'] < item['timestamp']
                ]

                if earlier_items:
                    item['duplicate_status'] = 'duplicate_in_queue'
                elif geojson_hash in imported_hashes:
                    item['duplicate_status'] = 'duplicate_imported'

            # Remove keys from response as they're not needed by frontend
            del item['geofeatures']
            del item['log_id']
            del item['geojson_hash']
            del item['unparsable']

        return data

    # WebSocket event handlers for channel layer events
    async def item_added(self, event):
        """Handle item_added event."""
        await self.send_to_client('item_added', event['data'])

    async def item_deleted(self, event):
        """Handle item_deleted event."""
        await self.send_to_client('item_deleted', event['data'])

    async def items_deleted(self, event):
        """Handle items_deleted event."""
        await self.send_to_client('items_deleted', event['data'])

    async def item_imported(self, event):
        """Handle item_imported event."""
        await self.send_to_client('item_imported', event['data'])

    async def status_updated(self, event):
        """Handle status_updated event."""
        await self.send_to_client('status_updated', event['data'])
