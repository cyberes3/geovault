"""
Process status WebSocket module.
Handles real-time status updates for a specific import item.
"""
from api.models import ImportQueue
from geo_lib.processing.duplicate_detection import find_coordinate_duplicates
from geo_lib.processing.logging import RealTimeImportLog, DatabaseLogLevel
from geo_lib.utils.pydantic_serialization import convert_features_to_pydantic
from asgiref.sync import sync_to_async
import time
import asyncio
import json
import traceback
from typing import Dict, Any, Optional

from django.conf import settings
from geo_lib.processing.status_tracker import status_tracker
from geo_lib.processing.messages import PROCESSING_FAILED_WITH_LOGS, ERROR_TYPE_FILE_UNPARSABLE
from geo_lib.websocket.base_module import BaseWebSocketModule
from geo_lib.logging.console import get_websocket_logger

logger = get_websocket_logger()


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
        try:
            # Refresh the import item from database to get latest data
            from api.models import ImportQueue
            from asgiref.sync import sync_to_async

            get_item = sync_to_async(ImportQueue.objects.get)
            self.import_item = await get_item(id=self.import_item.id)

            # Check for file-level duplicates using raw file content hash
            # Only block duplicates that are still in the queue (not yet imported)
            # Allow re-importing files that were previously imported (but mark them as duplicates)
            file_duplicate = {
                'status': None,
                'original_filename': None
            }

            if self.import_item.geojson_hash:
                # Check for earlier files with same raw file hash still in queue (not imported)
                duplicate_in_queue_query = sync_to_async(ImportQueue.objects.filter(
                    user_id=self.user.id,
                    geojson_hash=self.import_item.geojson_hash,
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
                        geojson_hash=self.import_item.geojson_hash,
                        imported=True
                    ).order_by('timestamp').first)
                    duplicate_imported = await duplicate_imported_query()

                    if duplicate_imported:
                        file_duplicate['status'] = 'duplicate_imported'
                        file_duplicate['original_filename'] = duplicate_imported.original_filename
                        
                        # Note: Auto-recheck was removed when we switched to sequential processing
                        # With Redis locks ensuring sequential per-user processing, the race condition
                        # that required auto-recheck no longer exists. Duplicate detection now always
                        # runs after all previous files are fully processed.

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

        except Exception as e:
            logger.error(f"Error sending initial state: {traceback.format_exc()}")
            await self.send_to_client('error', {'message': 'Failed to load initial state'})

    async def send_logs(self, after_id: Optional[int] = None) -> None:
        """Send logs, optionally starting from after_id for incremental updates."""
        try:
            logs_data = await self._get_logs(after_id)
            await self.send_to_client('logs', {'logs': logs_data, 'after_id': after_id})
        except Exception as e:
            logger.error(f"Error sending logs: {str(e)}")
            await self.send_to_client('error', {'message': 'Failed to load logs'})

    async def send_page(self, page: int, page_size: int) -> None:
        """Send a specific page of features."""
        try:
            features_data = await self._get_paginated_features(page, page_size)
            await self.send_to_client('page', features_data)
        except Exception as e:
            logger.error(f"Error sending page: {str(e)}")
            await self.send_to_client('error', {'message': 'Failed to load page'})

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
        from api.models import ImportQueue
        from asgiref.sync import sync_to_async
        
        get_item = sync_to_async(ImportQueue.objects.get)
        self.import_item = await get_item(id=self.import_item.id)
        
        # Send updated page data with new duplicates (current page, default page 1)
        features_data = await self._get_paginated_features(1, 50)
        await self.send_to_client('page', features_data)

    def _get_feature_bounding_box_center(self, feature: Dict[str, Any]) -> Optional[tuple]:
        """
        Calculate the bounding box center (lat, lon) for a feature.
        
        Args:
            feature: GeoJSON feature dictionary
            
        Returns:
            Tuple of (lat, lon) center coordinates, or None if feature has no valid geometry
        """
        geometry = feature.get('geometry', {})
        if not geometry:
            return None
        
        geom_type = geometry.get('type', '').lower()
        coordinates = geometry.get('coordinates')
        
        if not coordinates:
            return None
        
        # Collect all coordinate points from the geometry
        all_points = []
        
        try:
            if geom_type == 'point':
                # Point: [lon, lat] or [lon, lat, elevation]
                if isinstance(coordinates, list) and len(coordinates) >= 2:
                    all_points.append(coordinates)
            
            elif geom_type == 'multipoint':
                # MultiPoint: [[lon, lat], [lon, lat], ...]
                if isinstance(coordinates, list):
                    for point in coordinates:
                        if isinstance(point, list) and len(point) >= 2:
                            all_points.append(point)
            
            elif geom_type == 'linestring':
                # LineString: [[lon, lat], [lon, lat], ...]
                if isinstance(coordinates, list):
                    for point in coordinates:
                        if isinstance(point, list) and len(point) >= 2:
                            all_points.append(point)
            
            elif geom_type == 'multilinestring':
                # MultiLineString: [[[lon, lat], ...], [[lon, lat], ...], ...]
                if isinstance(coordinates, list):
                    for linestring in coordinates:
                        if isinstance(linestring, list):
                            for point in linestring:
                                if isinstance(point, list) and len(point) >= 2:
                                    all_points.append(point)
            
            elif geom_type == 'polygon':
                # Polygon: [[[lon, lat], ...], [[lon, lat], ...], ...] (exterior ring + holes)
                if isinstance(coordinates, list):
                    for ring in coordinates:
                        if isinstance(ring, list):
                            for point in ring:
                                if isinstance(point, list) and len(point) >= 2:
                                    all_points.append(point)
            
            elif geom_type == 'multipolygon':
                # MultiPolygon: [[[[lon, lat], ...], ...], [[[lon, lat], ...], ...], ...]
                if isinstance(coordinates, list):
                    for polygon in coordinates:
                        if isinstance(polygon, list):
                            for ring in polygon:
                                if isinstance(ring, list):
                                    for point in ring:
                                        if isinstance(point, list) and len(point) >= 2:
                                            all_points.append(point)
            
            # Calculate bounding box from all points
            if not all_points:
                return None
            
            # Extract lons and lats (GeoJSON uses [lon, lat] format)
            lons = [point[0] for point in all_points if isinstance(point[0], (int, float))]
            lats = [point[1] for point in all_points if isinstance(point[1], (int, float))]
            
            if not lons or not lats:
                return None
            
            # Calculate center
            center_lon = (min(lons) + max(lons)) / 2.0
            center_lat = (min(lats) + max(lats)) / 2.0
            
            return (center_lat, center_lon)
        
        except (TypeError, IndexError, ValueError) as e:
            # Handle any errors in coordinate extraction gracefully
            logger.debug(f"Error calculating bounding box center for feature: {str(e)}")
            return None

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

        # Validate pagination parameters
        if page < 1:
            page = 1
        # Force page_size to 50
        page_size = 50

        # Sort features spatially before pagination
        # Create list of (feature, original_index, sort_key) tuples
        features_with_indices = []
        for original_idx, feature in enumerate(self.import_item.geofeatures):
            center = self._get_feature_bounding_box_center(feature)
            if center is not None:
                # Sort by (-lat, lon) to get north-to-south, west-to-east ordering
                sort_key = (-center[0], center[1])
            else:
                # Features without valid geometry go to the end (high sort key)
                sort_key = (float('inf'), float('inf'))
            features_with_indices.append((feature, original_idx, sort_key))
        
        # Sort by spatial center
        features_with_indices.sort(key=lambda x: x[2])
        
        # Extract sorted features and create mapping from original index to new index
        sorted_features = [item[0] for item in features_with_indices]
        original_to_new_index = {item[1]: new_idx for new_idx, item in enumerate(features_with_indices)}

        # Calculate pagination
        total_features = len(sorted_features)
        start_idx = (page - 1) * page_size
        end_idx = start_idx + page_size

        # Get paginated features from sorted list
        paginated_features = sorted_features[start_idx:end_idx]

        # Get duplicate information for current page
        # New structure: four separate arrays for each duplicate type
        feature_store_hash_duplicates = []
        feature_store_geometry_duplicates = []
        cross_queue_hash_duplicates = []
        cross_queue_geometry_duplicates = []
        geometry_duplicate_hashes_for_skipping = []  # For skipped_feature_ids

        # Import necessary functions
        from geo_lib.processing.duplicate_detection import normalize_coordinates
        from geo_lib.feature_id import generate_feature_hash
        from geo_lib.processing.duplicate_models import DuplicateSource, DuplicateMatchType
        
        # Get other unimported ImportQueue items for cross-queue checking
        other_queue_items = await self._get_other_queue_items()
        
        # Get existing feature hashes from FeatureStore
        from api.models import FeatureStore
        from asgiref.sync import sync_to_async
        
        @sync_to_async
        def get_existing_hashes_and_ids():
            # Get hash to feature_store_id mapping for linking
            hash_to_id = {}
            hashes = set()
            for feature in FeatureStore.objects.filter(user_id=self.import_item.user_id).values('id', 'geojson_hash'):
                if feature['geojson_hash']:
                    hashes.add(feature['geojson_hash'])
                    if feature['geojson_hash'] not in hash_to_id:
                        hash_to_id[feature['geojson_hash']] = feature['id']
            return hashes, hash_to_id
        
        existing_store_hashes, hash_to_store_id = await get_existing_hashes_and_ids()
        
        # Build hash map from other queue items
        queue_hash_to_item = {}
        for queue_item in other_queue_items:
            for feature in queue_item.geofeatures:
                feature_hash = feature.get('properties', {}).get('feature_hash')
                if not feature_hash:
                    feature_hash = generate_feature_hash(feature)
                if feature_hash not in queue_hash_to_item:
                    queue_hash_to_item[feature_hash] = {
                        'queue_item_id': queue_item.id,
                        'queue_item_filename': queue_item.original_filename
                    }
        
        # Track all hash duplicates (both sources) to exclude from geometry checking
        all_hash_duplicate_hashes = set()
        
        # Check each feature for hash duplicates
        # Priority rule: feature_store takes precedence over cross_queue
        for original_idx, feature in enumerate(self.import_item.geofeatures):
            feature_hash = feature.get('properties', {}).get('feature_hash')
            if not feature_hash:
                feature_hash = generate_feature_hash(feature)
            
            # Convert to sorted index for page display
            if original_idx not in original_to_new_index:
                continue
            new_idx = original_to_new_index[original_idx]
            
            # Check FeatureStore hash duplicates first (takes precedence)
            if feature_hash in existing_store_hashes:
                all_hash_duplicate_hashes.add(feature_hash)
                if start_idx <= new_idx < end_idx:
                    dup_obj = {
                        'hash': feature_hash,
                        'page_index': new_idx - start_idx
                    }
                    if feature_hash in hash_to_store_id:
                        dup_obj['feature_store_id'] = hash_to_store_id[feature_hash]
                    feature_store_hash_duplicates.append(dup_obj)
            # Check cross-queue hash duplicates (only if not in FeatureStore)
            elif feature_hash in queue_hash_to_item:
                all_hash_duplicate_hashes.add(feature_hash)
                queue_info = queue_hash_to_item[feature_hash]
                if start_idx <= new_idx < end_idx:
                    cross_queue_hash_duplicates.append({
                        'hash': feature_hash,
                        'page_index': new_idx - start_idx,
                        'queue_item_id': queue_info['queue_item_id'],
                        'queue_item_filename': queue_info['queue_item_filename']
                    })
        
        # Now process geometry duplicates from the stored duplicate_features
        # These have already been filtered to exclude hash duplicates during processing
        duplicate_features_list = self.import_item.duplicate_features if self.import_item.duplicate_features else []
        
        for dup_info in duplicate_features_list:
            # Check source and match_type to categorize properly
            source = dup_info.get('source')
            match_type = dup_info.get('match_type')
            dup_feature = dup_info.get('feature')
            existing_features = dup_info.get('existing_features', [])
            
            if not dup_feature or not source or not match_type:
                continue
            
            # Get feature hash
            feature_hash = dup_feature.get('properties', {}).get('feature_hash')
            if not feature_hash:
                feature_hash = generate_feature_hash(dup_feature)
            
            # Skip if this is a hash duplicate (already processed above)
            if feature_hash in all_hash_duplicate_hashes:
                continue
            
            # Only process geometry duplicates here
            if match_type != DuplicateMatchType.GEOMETRY:
                continue
            
            # Find the feature in geofeatures to get its index
            feature_idx = None
            for idx, feat in enumerate(self.import_item.geofeatures):
                feat_hash = feat.get('properties', {}).get('feature_hash')
                if not feat_hash:
                    feat_hash = generate_feature_hash(feat)
                if feat_hash == feature_hash:
                    feature_idx = idx
                    break
            
            if feature_idx is None or feature_idx not in original_to_new_index:
                continue
            
            new_idx = original_to_new_index[feature_idx]
            
            # Track for skipped_feature_ids
            geometry_duplicate_hashes_for_skipping.append(feature_hash)
            
            # Only add to arrays if on current page
            if start_idx <= new_idx < end_idx:
                dup_obj = {
                    'hash': feature_hash,
                    'page_index': new_idx - start_idx
                }
                
                # Add linking information from existing_features
                if existing_features and len(existing_features) > 0:
                    first_existing = existing_features[0]
                    if source == DuplicateSource.FEATURE_STORE:
                        # Link to feature store (map)
                        if 'id' in first_existing:
                            dup_obj['feature_store_id'] = first_existing['id']
                    elif source == DuplicateSource.CROSS_QUEUE:
                        # Link to queue item
                        if 'id' in first_existing and 'name' in first_existing:
                            dup_obj['queue_item_id'] = first_existing['id']
                            dup_obj['queue_item_filename'] = first_existing['name']
                
                # Add to appropriate array based on source
                if source == DuplicateSource.FEATURE_STORE:
                    feature_store_geometry_duplicates.append(dup_obj)
                elif source == DuplicateSource.CROSS_QUEUE:
                    cross_queue_geometry_duplicates.append(dup_obj)
        
        # Filter skipped_feature_ids to only include geometry duplicates
        # Hash duplicates are always blocked and should not be in skipped list
        original_skipped_feature_ids = self.import_item.skipped_feature_ids if self.import_item.skipped_feature_ids else []
        filtered_skipped_ids = [
            fid for fid in original_skipped_feature_ids 
            if fid in geometry_duplicate_hashes_for_skipping
        ]

        return {
            'data': paginated_features,
            'pagination': {
                'page': page,
                'page_size': page_size,
                'total_features': total_features,
                'total_pages': (total_features + page_size - 1) // page_size,
                'has_next': end_idx < total_features,
                'has_previous': page > 1
            },
            'duplicates': {
                'feature_store_hash': feature_store_hash_duplicates,
                'feature_store_geometry': feature_store_geometry_duplicates,
                'cross_queue_hash': cross_queue_hash_duplicates,
                'cross_queue_geometry': cross_queue_geometry_duplicates
            },
            'skipped_feature_ids': filtered_skipped_ids
        }

    async def _get_logs(self, after_id: Optional[int] = None) -> list:
        """Get logs for the import item."""
        if not self.import_item.log_id:
            return []

        try:
            from api.models import DatabaseLogging
            from asgiref.sync import sync_to_async

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
        except Exception as e:
            logger.error(f"Error fetching logs: {str(e)}")
            return []

    # _auto_recheck_duplicates method removed - no longer needed with sequential processing
    # With Redis locks ensuring per-user sequential processing, the race condition that
    # required auto-recheck no longer exists.