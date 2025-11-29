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
            duplicate_status = None
            duplicate_original_filename = None

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
                    duplicate_status = 'duplicate_in_queue'
                    duplicate_original_filename = duplicate_in_queue.original_filename
                else:
                    # Check for already-imported files with same raw file hash
                    duplicate_imported_query = sync_to_async(ImportQueue.objects.filter(
                        user_id=self.user.id,
                        geojson_hash=self.import_item.geojson_hash,
                        imported=True
                    ).order_by('timestamp').first)
                    duplicate_imported = await duplicate_imported_query()

                    if duplicate_imported:
                        duplicate_status = 'duplicate_imported'
                        duplicate_original_filename = duplicate_imported.original_filename
                        
                        # If this file is a duplicate_imported but has no duplicate_features,
                        # it means the original file was imported AFTER this one was uploaded.
                        # Automatically recheck duplicates to mark the features properly.
                        if not self.import_item.duplicate_features or len(self.import_item.duplicate_features) == 0:
                            # Log to processing log for user visibility
                            if self.import_item.log_id:
                                from geo_lib.processing.logging import RealTimeImportLog, DatabaseLogLevel
                                
                                realtime_log = RealTimeImportLog(user_id=self.user.id, log_id=self.import_item.log_id)
                                await realtime_log.add_async(
                                    f"File is a duplicate of imported file, but features were not checked. Automatically rechecking duplicates now.",
                                    "Duplicate Detection",
                                    DatabaseLogLevel.INFO
                                )
                            
                            await self._auto_recheck_duplicates()

            # Only block if it's a duplicate in the queue
            # Allow duplicates of already-imported files to proceed (they'll be marked as duplicates)
            if duplicate_status == 'duplicate_in_queue':
                # Send an error to the client via this websocket channel and do not proceed
                message = (
                    "This upload is a duplicate of '" + duplicate_original_filename
                    if duplicate_original_filename else
                    "This upload is a duplicate."
                )
                await self.send_to_client('error', {
                    'code': 409,
                    'message': message,
                    'duplicate_status': duplicate_status,
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
                'duplicates': features_data.get('duplicates', []),  # Get duplicates from features_data
                # Include duplicate status for informational purposes (duplicate_imported allows import)
                'duplicate_status': duplicate_status,
                'duplicate_original_filename': duplicate_original_filename
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
        duplicates_optimized = []
        duplicate_indices = []

        # Import normalization function for coordinate comparison
        from geo_lib.processing.duplicate_detection import normalize_coordinates
        
        # Build a map of normalized coordinates to original indices
        # This allows us to mark ALL features with matching coordinates as duplicates
        coords_to_original_indices = {}
        for original_idx, feature in enumerate(self.import_item.geofeatures):
            feature_geom = feature.get('geometry', {})
            feature_coords = feature_geom.get('coordinates')
            feature_type = feature_geom.get('type', '').lower()
            
            if feature_coords:
                normalized_coords = normalize_coordinates(feature_coords)
                coords_key = (feature_type, json.dumps(normalized_coords, sort_keys=True))
                if coords_key not in coords_to_original_indices:
                    coords_to_original_indices[coords_key] = []
                coords_to_original_indices[coords_key].append(original_idx)
        
        # Now process each duplicate_info and mark all features with matching coordinates as duplicates
        # Convert original indices to new sorted indices
        duplicate_features_list = self.import_item.duplicate_features if self.import_item.duplicate_features else []
        
        for dup_info in duplicate_features_list:
            dup_feature = dup_info.get('feature')
            if dup_feature:
                dup_geom = dup_feature.get('geometry', {})
                dup_coords = dup_geom.get('coordinates')
                dup_type = dup_geom.get('type', '').lower()
                
                if dup_coords:
                    # Normalize duplicate feature coordinates for comparison
                    normalized_dup_coords = normalize_coordinates(dup_coords)
                    coords_key = (dup_type, json.dumps(normalized_dup_coords, sort_keys=True))
                    
                    # Mark ALL features with matching coordinates as duplicates
                    if coords_key in coords_to_original_indices:
                        for original_idx in coords_to_original_indices[coords_key]:
                            # Convert original index to new sorted index
                            if original_idx in original_to_new_index:
                                new_idx = original_to_new_index[original_idx]
                                if new_idx not in duplicate_indices:
                                    duplicate_indices.append(new_idx)
                                
                                # Only include duplicate info if it's in the current page
                                if start_idx <= new_idx < end_idx:
                                    existing_features_optimized = []
                                    for existing in dup_info.get('existing_features', []):
                                        existing_features_optimized.append({
                                            'id': existing.get('id'),
                                            'name': existing.get('name'),
                                            'type': existing.get('type'),
                                            'timestamp': existing.get('timestamp')
                                        })
                                    duplicates_optimized.append({
                                        'existing_features': existing_features_optimized,
                                        'page_index': new_idx - start_idx,
                                    })

        return {
            'data': paginated_features,
            'pagination': {
                'page': page,
                'page_size': page_size,
                'total_features': total_features,
                'total_pages': (total_features + page_size - 1) // page_size,
                'has_next': end_idx < total_features,
                'has_previous': page > 1,
                'duplicate_indices': duplicate_indices
            },
            'duplicates': duplicates_optimized
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

    async def _auto_recheck_duplicates(self) -> None:
        """
        Automatically recheck duplicates for this import item.
        This is called when a file has duplicate_imported status but no duplicate_features,
        which happens when the original file was imported after this one was uploaded.
        """
        try:
            # Get the features from the import item
            features = self.import_item.geofeatures
            if not features:
                logger.info(f"No features to check for item {self.import_item.id}")
                return
            
            # Create a real-time log for this operation
            realtime_log = RealTimeImportLog(user_id=self.user.id, log_id=self.import_item.log_id)
            
            # Log the start of the auto-recheck
            await realtime_log.add_async(
                "Automatically rechecking duplicates (file was imported after this upload)",
                "Auto Duplicate Recheck",
                DatabaseLogLevel.INFO
            )

            def run_duplicate_detection():
                rd_start_time = time.time()
                rd_unique_features, rd_duplicate_features, rd_duplicate_log = find_coordinate_duplicates(
                    features,
                    self.user.id
                )
                rd_duration = time.time() - rd_start_time
                return rd_unique_features, rd_duplicate_features, rd_duplicate_log, rd_duration
            
            # Run in executor to avoid blocking the event loop
            loop = asyncio.get_event_loop()
            unique_features, duplicate_features, duplicate_log, duration = await loop.run_in_executor(
                None, run_duplicate_detection
            )
            
            # Extend the real-time log with duplicate detection results
            await realtime_log.extend_async(duplicate_log)
            await realtime_log.add_timing_async("Auto duplicate recheck", duration, "Auto Duplicate Recheck")
            
            # Update the import queue item with new duplicate information
            def save_duplicates():
                item = ImportQueue.objects.get(id=self.import_item.id)
                item.duplicate_features = convert_features_to_pydantic(duplicate_features)
                item.save(update_fields=['duplicate_features'])
                return len(duplicate_features)
            
            duplicate_count = await sync_to_async(save_duplicates)()
            
            # Refresh our local copy
            get_item = sync_to_async(ImportQueue.objects.get)
            self.import_item = await get_item(id=self.import_item.id)
            
            # Log completion
            await realtime_log.add_async(
                f"Auto duplicate recheck completed. Found {duplicate_count} duplicate(s)",
                "Auto Duplicate Recheck",
                DatabaseLogLevel.INFO
            )
        except Exception:
            logger.error(f"Error auto-rechecking duplicates for item {self.import_item.id}: {traceback.format_exc()}")
            # Don't raise - we don't want to break the initial state send
