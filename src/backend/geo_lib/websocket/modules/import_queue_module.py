import json
from typing import Any, Optional

from channels.db import database_sync_to_async
from django.core.serializers.json import DjangoJSONEncoder
from django.db.models import Count
from pydantic import BaseModel, Field

from api.models import ImportQueue
from geo_lib.importing.draft_store import ImportDraftStore
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.jobs.helpers.status_tracker import status_tracker
from geo_lib.websocket.base_module import BaseWebSocketModule

logger = get_tagged_logger('websocket')


class ImportQueueStatusCounts(BaseModel):
    feature_count: int = Field(ge=-1)


class ImportQueueStatusDelta(BaseModel):
    item_id: int
    status: Optional[str] = None
    counts: ImportQueueStatusCounts


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
        ).annotate(
            draft_count=Count('draft_features')
        ).order_by('-timestamp').values(
            'id', 'original_filename', 'file_hash',
            'log_id', 'timestamp', 'imported', 'unparsable',
            'queue_status', 'duplicate_counts', 'draft_count', 'skip_intent'
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
            count = item.get('draft_count') or 0
            item['processing'] = item['id'] in active_job_ids or item.get('queue_status') == ImportQueue.STATUS_PROCESSING
            item['queued'] = item['id'] in queued_job_ids
            if item.get('unparsable') or item.get('queue_status') == ImportQueue.STATUS_FAILED:
                item['feature_count'] = 0
                item['processing_failed'] = True
            elif count == 0 and (item['processing'] or item['queued']):
                item['feature_count'] = -1
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
                counts = item.get('duplicate_counts') or {}
                hash_count = int(counts.get('hash') or 0)
                geometry_count = int(counts.get('geometry') or 0)
                restored = len((item.get('skip_intent') or {}).get('user_restored_geometry') or [])
                importable = count - hash_count - max(geometry_count - restored, 0)
                if count > 0 and importable <= 0:
                    file_duplicate_status = 'all_features_duplicate'

            item['file_duplicate'] = {
                'status': file_duplicate_status,
                'original_filename': None
            }
            del item['log_id']
            del item['file_hash']
            del item['unparsable']
            item.pop('duplicate_counts', None)
            item.pop('draft_count', None)
            item.pop('queue_status', None)
            item.pop('skip_intent', None)

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
        """Handle status_updated event as a single-item delta, not a full queue snapshot."""
        data = event.get('data') or {}
        item_id = data.get('item_id') or data.get('id') or data.get('import_queue_id')
        if item_id is None:
            logger.warning("import_queue status_updated missing item id")
            return
        delta = await database_sync_to_async(self.build_status_delta)(int(item_id), data)
        await self.send_to_client('status_updated', delta)

    def build_status_delta(self, item_id: int, event_data: dict) -> dict[str, Any]:
        """Build `{ item_id, status, counts }` from the event plus the current queue row."""
        item = ImportQueue.objects.filter(user=self.user, id=item_id).values(
            'id', 'unparsable', 'imported', 'queue_status',
        ).first()
        status = event_data.get('status')
        feature_count = 0
        if item:
            count = ImportDraftStore(ImportQueue.objects.get(id=item_id)).feature_count()
            if item.get('unparsable') or item.get('queue_status') == ImportQueue.STATUS_FAILED:
                feature_count = 0
            elif count == 0 and status in ('processing', 'queued'):
                feature_count = -1
            else:
                feature_count = count
            if status is None:
                if item['imported'] or item.get('queue_status') == ImportQueue.STATUS_IMPORTED:
                    status = 'completed'
                elif item['unparsable'] or item.get('queue_status') == ImportQueue.STATUS_FAILED:
                    status = 'failed'
        delta = ImportQueueStatusDelta(
            item_id=item_id,
            status=status,
            counts=ImportQueueStatusCounts(feature_count=feature_count),
        )
        return delta.model_dump(mode='json')
