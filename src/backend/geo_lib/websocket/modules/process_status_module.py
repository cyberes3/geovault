from typing import Dict, Any, Optional

from asgiref.sync import sync_to_async

from api.models import DatabaseLogging, FeatureStore, ImportQueue
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.messages import ERROR_TYPE_FILE_UNPARSABLE, PROCESSING_FAILED_WITH_LOGS
from geo_lib.processing.jobs.helpers.status_tracker import status_tracker
from geo_lib.websocket.base_module import BaseWebSocketModule
from geo_lib.websocket.modules.file_duplicate_utils import check_all_features_duplicate
from geo_lib.websocket.modules.process_status_duplicates import (
    build_geometry_duplicate_maps,
    build_hash_duplicate_maps,
    build_queue_hash_to_item,
)
from geo_lib.websocket.modules.process_status_pagination import (
    build_other_queue_sorted_indices,
    build_pagination_metadata,
    normalize_page_bounds,
    sort_features_spatially,
)

logger = get_tagged_logger('websocket')

"""
Process status WebSocket module.
Handles real-time status updates for a specific import item.
Sends all the nessesary data to load the page too.
"""


class ProcessStatusModule(BaseWebSocketModule):
    """WebSocket module for process status updates for a specific import item."""

    def __init__(self, consumer, import_item):
        """Initialize with the import item."""
        super().__init__(consumer)
        self.import_item = import_item

    @property
    def module_name(self) -> str:
        return "process_status"

    async def handle_message(self, message_type: str, data: dict) -> None:
        """Handle incoming messages for process status module."""
        if message_type == 'refresh':
            await self.send_initial_state()
        elif message_type == 'request_logs':
            after_id = data.get('after_id')
            await self.send_logs(after_id)
        elif message_type == 'request_page':
            page = data.get('page', 1)
            page_size = data.get('page_size', 50)
            await self.send_page(page, page_size)
        else:
            logger.warning(f"Unknown message type for process_status module: {message_type}")

    async def send_initial_state(self) -> None:
        """Send initial state with item status, features, and logs."""
        # Refresh the import item from database to get latest data
        get_item = sync_to_async(ImportQueue.objects.get)
        self.import_item = await get_item(id=self.import_item.id)

        # Check for file-level duplicates using raw file content hash
        # Only block duplicates that are still in the queue (not yet imported)
        # Allow re-importing files that were previously imported (but mark them as duplicates)
        file_duplicate = {
            'status': None,
            'original_filename': None
        }

        if self.import_item.file_hash:
            # Check for earlier files with same raw file hash still in queue (not imported)
            duplicate_in_queue_query = sync_to_async(ImportQueue.objects.filter(
                user_id=self.user.id,
                file_hash=self.import_item.file_hash,
                imported=False,
                timestamp__lt=self.import_item.timestamp
            ).order_by('timestamp').first)
            duplicate_in_queue = await duplicate_in_queue_query()

            if duplicate_in_queue:
                file_duplicate['status'] = 'duplicate_in_queue'
                file_duplicate['original_filename'] = duplicate_in_queue.original_filename
            else:
                # Check for already-imported files with same raw file hash
                duplicate_imported_query = sync_to_async(ImportQueue.objects.filter(
                    user_id=self.user.id,
                    file_hash=self.import_item.file_hash,
                    imported=True
                ).order_by('timestamp').first)
                duplicate_imported = await duplicate_imported_query()

                if duplicate_imported:
                    file_duplicate['status'] = 'duplicate_imported'
                    file_duplicate['original_filename'] = duplicate_imported.original_filename

        # Check if all features in the file are duplicates
        # Only check if file_hash duplicate status hasn't been set (lower priority)
        if file_duplicate['status'] is None:
            geofeatures = self.import_item.geofeatures if self.import_item.geofeatures else []
            duplicate_features = self.import_item.duplicate_features if self.import_item.duplicate_features else []
            
            if check_all_features_duplicate(geofeatures, duplicate_features):
                file_duplicate['status'] = 'all_features_duplicate'

        # Only block if it's a duplicate in the queue
        # Allow duplicates of already-imported files to proceed (they'll be marked as duplicates)
        if file_duplicate['status'] == 'duplicate_in_queue':
            # Send an error to the client via this websocket channel and do not proceed
            message = (
                "This upload is a duplicate of '" + file_duplicate['original_filename']
                if file_duplicate['original_filename'] else
                "This upload is a duplicate."
            )
            await self.send_to_client('error', {
                'code': 409,
                'message': message,
                'file_duplicate': file_duplicate,
                'item_id': self.import_item.id
            })
            return

        # Get current processing status
        is_processing = False
        job_details = None

        if not self.import_item.imported and not self.import_item.unparsable:
            # Check if currently being processed
            user_jobs = status_tracker.get_user_jobs(self.user.id)
            active_job_ids = {job.import_queue_id for job in user_jobs if job.status.value == 'processing' and job.import_queue_id}

            if self.import_item.id in active_job_ids:
                is_processing = True
                for job in user_jobs:
                    if job.import_queue_id == self.import_item.id and job.status.value == 'processing':
                        job_details = status_tracker.get_job_status(job.job_id)
                        break

        # Get paginated features (default page 1, size 50)
        features_data = await self._get_paginated_features(1, 50)

        # Get recent logs
        logs_data = await self._get_logs()

        initial_state = {
            'item_id': self.import_item.id,
            'imported': self.import_item.imported,
            'unparsable': self.import_item.unparsable,
            'original_filename': self.import_item.original_filename,
            'timestamp': self.import_item.timestamp.isoformat() if self.import_item.timestamp else None,
            'processing': is_processing,
            'job_details': job_details,
            'features': features_data,
            'logs': logs_data,
            # Include file duplicate info for informational purposes (duplicate_imported allows import)
            'file_duplicate': file_duplicate
        }

        await self.send_to_client('initial_state', initial_state)

    async def send_logs(self, after_id: Optional[int] = None) -> None:
        """Send logs, optionally starting from after_id for incremental updates."""
        logs_data = await self._get_logs(after_id)
        await self.send_to_client('logs', {'logs': logs_data, 'after_id': after_id})

    async def send_page(self, page: int, page_size: int) -> None:
        """Send a specific page of features."""
        features_data = await self._get_paginated_features(page, page_size)
        await self.send_to_client('page', features_data)

    async def handle_status_updated(self, data: Dict[str, Any]) -> None:
        """Handle status update events."""
        await self.send_to_client('status_updated', data)

    async def handle_logs_added(self, data: Dict[str, Any]) -> None:
        """Handle new log entries."""
        await self.send_to_client('log_added', data)

    async def handle_logs_batch_added(self, data: Dict[str, Any]) -> None:
        """Handle new log batch entries."""
        # Iterate and send individual updates to client (for now)
        logs = data.get('logs', [])
        for log in logs:
            await self.send_to_client('log_added', log)

    async def handle_item_completed(self, data: Dict[str, Any]) -> None:
        """Handle item completion."""
        await self.send_to_client('item_completed', data)

    async def handle_item_failed(self, data: Dict[str, Any]) -> None:
        """Handle item failure."""
        await self.send_to_client('item_failed', data)

    async def handle_item_deleted(self, data: Dict[str, Any]) -> None:
        """Handle item deletion - notify client and close connection."""
        await self.send_to_client('item_deleted', data)

    async def handle_duplicates_updated(self, data: Dict[str, Any]) -> None:
        """Handle duplicates updated event - refresh page data to show updated duplicate markers."""
        # Refresh the import item from database to get the latest duplicate_features

        get_item = sync_to_async(ImportQueue.objects.get)
        self.import_item = await get_item(id=self.import_item.id)

        # Send updated page data with new duplicates (current page, default page 1)
        features_data = await self._get_paginated_features(1, 50)
        await self.send_to_client('page', features_data)

    async def _get_other_queue_items(self):
        """Get other unimported ImportQueue items for the same user that are older (by timestamp)."""

        @sync_to_async
        def get_items():
            return list(ImportQueue.objects.filter(
                user_id=self.import_item.user_id,
                imported=False,
                timestamp__lt=self.import_item.timestamp  # Only older items
            ).exclude(id=self.import_item.id).only('id', 'original_filename', 'geofeatures'))

        return await get_items()

    async def _get_existing_hashes_and_ids(self):
        """Get the set of existing FeatureStore geojson_hash values for this user, and a hash -> id mapping for linking."""

        @sync_to_async
        def get_existing_hashes_and_ids():
            hash_to_id = {}
            hashes = set()
            for f in FeatureStore.objects.filter(user_id=self.import_item.user_id).values('id', 'geojson_hash'):
                if f['geojson_hash']:
                    hashes.add(f['geojson_hash'])
                    if f['geojson_hash'] not in hash_to_id:
                        hash_to_id[f['geojson_hash']] = f['id']
            return hashes, hash_to_id

        return await get_existing_hashes_and_ids()

    async def _get_paginated_features(self, page: int, page_size: int) -> Dict[str, Any]:
        """Get paginated features for the import item."""
        if self.import_item.imported:
            return {
                'data': [],
                'pagination': {
                    'page': 1,
                    'page_size': page_size,
                    'total_features': 0,
                    'total_pages': 0,
                    'has_next': False,
                    'has_previous': False
                }
            }

        if self.import_item.unparsable:
            return {
                'data': [{'error': ERROR_TYPE_FILE_UNPARSABLE, 'message': PROCESSING_FAILED_WITH_LOGS}],
                'pagination': {
                    'page': 1,
                    'page_size': page_size,
                    'total_features': 1,
                    'total_pages': 1,
                    'has_next': False,
                    'has_previous': False
                }
            }

        # Sort features spatially before pagination, and compute page bounds
        sorted_features, original_to_new_index = sort_features_spatially(self.import_item.geofeatures)
        total_features = len(sorted_features)
        page, page_size, start_idx, end_idx = normalize_page_bounds(page)
        paginated_features = sorted_features[start_idx:end_idx]

        # Get other unimported ImportQueue items for cross-queue duplicate checking,
        # and their spatially-sorted indices (needed to navigate to a duplicate's
        # position in the target item)
        other_queue_items = await self._get_other_queue_items()
        queue_item_sorted_indices = build_other_queue_sorted_indices(other_queue_items)

        # Get hash to feature_store_id mapping for linking
        existing_store_hashes, hash_to_store_id = await self._get_existing_hashes_and_ids()

        # Compute duplicate maps for the current page. Priority rule: feature_store
        # duplicates take precedence over cross_queue, and hash duplicates take
        # precedence over geometry duplicates.
        queue_hash_to_item = build_queue_hash_to_item(other_queue_items)
        feature_store_hash_duplicates, cross_queue_hash_duplicates, all_hash_duplicate_hashes = build_hash_duplicate_maps(
            self.import_item.geofeatures, original_to_new_index, start_idx, end_idx,
            existing_store_hashes, hash_to_store_id, queue_hash_to_item, queue_item_sorted_indices,
        )

        duplicate_features_list = self.import_item.duplicate_features if self.import_item.duplicate_features else []
        # The third return value (geometry-duplicate hashes) isn't consumed here -- `skipped_feature_ids`
        # below comes directly from the persisted import item, not from this page-scoped computation.
        feature_store_geometry_duplicates, cross_queue_geometry_duplicates, _ = build_geometry_duplicate_maps(
            duplicate_features_list, self.import_item.geofeatures, original_to_new_index, start_idx, end_idx,
            all_hash_duplicate_hashes, queue_item_sorted_indices,
        )

        # Return all skipped_feature_ids (geometry duplicates + manually skipped non-duplicates)
        # Hash duplicates are always blocked and should not be in skipped_feature_ids (enforced during processing)
        # We return all skipped_feature_ids to preserve manually skipped non-duplicate features
        skipped_feature_ids = self.import_item.skipped_feature_ids if self.import_item.skipped_feature_ids else []

        return {
            'data': paginated_features,
            'pagination': build_pagination_metadata(page, page_size, total_features, end_idx),
            'duplicates': {
                'feature_store_hash': feature_store_hash_duplicates,
                'feature_store_geometry': feature_store_geometry_duplicates,
                'cross_queue_hash': cross_queue_hash_duplicates,
                'cross_queue_geometry': cross_queue_geometry_duplicates
            },
            'skipped_feature_ids': skipped_feature_ids
        }

    async def _get_logs(self, after_id: Optional[int] = None) -> list:
        """Get logs for the import item."""
        if not self.import_item.log_id:
            return []

        # Create async database query
        def get_logs():
            query = DatabaseLogging.objects.filter(log_id=self.import_item.log_id)
            if after_id:
                query = query.filter(id__gt=after_id)
            return list(query.order_by('id'))

        get_logs_async = sync_to_async(get_logs)
        db_logs = await get_logs_async()

        return [{
            'id': log.id,
            'timestamp': log.timestamp.isoformat(),
            'msg': log.text,
            'source': log.source,
            'level': log.level
        } for log in db_logs]
