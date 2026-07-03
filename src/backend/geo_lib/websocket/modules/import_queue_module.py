import json

from channels.db import database_sync_to_async
from django.core.serializers.json import DjangoJSONEncoder

from api.models import ImportQueue
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.jobs.helpers.status_tracker import status_tracker
from geo_lib.utils.redis_connection import get_redis_connection
from geo_lib.websocket.base_module import BaseWebSocketModule
from geo_lib.websocket.modules.file_duplicate_utils import check_all_features_duplicate

logger = get_tagged_logger('websocket')


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
        queue_data = await self.get_import_queue_data()
        await self.send_to_client('initial_state', queue_data)

    @database_sync_to_async
    def get_import_queue_data(self):
        """Get current import queue data for the user."""
        # Get user items from database (exclude replacement uploads)
        user_items = ImportQueue.objects.filter(
            user=self.user,
            imported=False,
            replacement__isnull=True
        ).order_by('-timestamp').values(
            'id', 'geofeatures', 'original_filename', 'file_hash',
            'log_id', 'timestamp', 'imported', 'unparsable', 'duplicate_features'
        )

        data = json.loads(json.dumps(list(user_items), cls=DjangoJSONEncoder))

        # Get all active processing jobs for this user
        user_jobs = status_tracker.get_user_jobs(self.user.id)
        active_job_ids = {
            job.import_queue_id for job in user_jobs
            if job.status.value == 'processing' and job.import_queue_id
        }

        # Get all queued jobs for this user
        queued_job_ids = {
            job.import_queue_id for job in user_jobs
            if job.status.value == 'queued' and job.import_queue_id
        }

        # Check if there are items queued in Redis for this user
        # (This handles recovered jobs that haven't started processing yet)
        queued_import_ids = set()
        redis_client = get_redis_connection()
        queue_key = f"processing_queue:user:{self.user.id}"

        # Get all items in the queue without removing them (LRANGE 0 -1)
        queue_items = redis_client.lrange(queue_key, 0, -1)
        for item_json in queue_items:
            try:
                job_data = json.loads(item_json)
                if 'import_queue_id' in job_data:
                    queued_import_ids.add(job_data['import_queue_id'])
            except (json.JSONDecodeError, TypeError) as e:
                logger.error(f"Error parsing queue item for user {self.user.id}: {e}")

        # Build a map of file hash to items for duplicate detection (hash of raw file content)
        hash_to_items = {}
        queue_hashes = set()
        for item in data:
            if item.get('file_hash'):
                file_hash = item['file_hash']
                queue_hashes.add(file_hash)
                if file_hash not in hash_to_items:
                    hash_to_items[file_hash] = []
                hash_to_items[file_hash].append(item)

        # Check for imported files with same hash
        # Get all imported items for this user that have a hash matching any item in the queue
        imported_hashes = {}
        if queue_hashes:
            imported_items = ImportQueue.objects.filter(
                user=self.user,
                imported=True,
                file_hash__in=list(queue_hashes),
                file_hash__isnull=False
            ).values('file_hash', 'original_filename').distinct()

            for imported_item in imported_items:
                imported_hashes[imported_item['file_hash']] = imported_item['original_filename']

        # Process each item
        for i, item in enumerate(data):
            count = len(item['geofeatures'])

            # Check if this item is currently being processed
            item['processing'] = item['id'] in active_job_ids

            # Check if this item is queued (from job tracker or Redis queue)
            item['queued'] = item['id'] in queued_job_ids or item['id'] in queued_import_ids

            # Check if there's an error in the geofeatures or if marked as unparsable
            if item.get('unparsable') or (count == 1 and item['geofeatures'] and isinstance(item['geofeatures'][0], dict) and 'error' in item['geofeatures'][0]):
                item['feature_count'] = 0
                item['processing_failed'] = True
            elif count == 0 and (item['processing'] or item['queued']):
                item['feature_count'] = -1  # Special value to indicate processing or queued
                item['processing_failed'] = False
            else:
                item['feature_count'] = count
                item['processing_failed'] = False

            # Check for file-level duplicate status
            file_duplicate_status = None
            if item.get('file_hash'):
                file_hash = item['file_hash']
                items_with_same_hash = hash_to_items.get(file_hash, [])

                # Check if there are other items in queue with same hash (uploaded earlier)
                earlier_items = [
                    other for other in items_with_same_hash
                    if other['id'] != item['id'] and other['timestamp'] < item['timestamp']
                ]

                if earlier_items:
                    file_duplicate_status = 'duplicate_in_queue'
                elif file_hash in imported_hashes:
                    file_duplicate_status = 'duplicate_imported'

            # Check if all features in the file are duplicates
            # Only check if file_hash duplicate status hasn't been set (lower priority)
            if file_duplicate_status is None:
                geofeatures = item.get('geofeatures', [])
                duplicate_features = item.get('duplicate_features', [])
                
                if check_all_features_duplicate(geofeatures, duplicate_features):
                    file_duplicate_status = 'all_features_duplicate'

            item['file_duplicate'] = {
                'status': file_duplicate_status,
                'original_filename': None  # We don't track the original filename in the queue list
            }

            # Remove keys from response as they're not needed by frontend. geofeatures and
            # duplicate_features in particular can be several MB of embedded GeoJSON per item
            # (they're only needed above, to compute feature_count/file_duplicate_status) --
            # leaving either in the payload risks exceeding the WebSocket message size limit
            # on large/dupe-heavy imports.
            del item['geofeatures']
            del item['duplicate_features']
            del item['log_id']
            del item['file_hash']
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
        """Handle status_updated event - refresh queue to update duplicate status."""
        # Refresh the queue data to ensure duplicate status is up to date
        await self.send_initial_state()
