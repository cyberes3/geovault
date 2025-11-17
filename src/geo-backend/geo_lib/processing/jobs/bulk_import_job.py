"""
Bulk import job processor for asynchronous bulk import operations.
Handles importing multiple import queue items to the feature store.
"""

import json
import traceback
import threading
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, Any, List, Tuple, Optional

from django.contrib.gis.geos import GEOSGeometry
from django.db import transaction

from api.models import ImportQueue, FeatureStore, DatabaseLogging
from geo_lib.feature_id import generate_feature_hash
from geo_lib.processing.jobs.base_job import BaseJob
from geo_lib.processing.status_tracker import ProcessingStatus, JobType
from geo_lib.const_strings import CONST_INTERNAL_TAGS, filter_protected_tags
from geo_lib.types.feature import PointFeature, PolygonFeature, LineStringFeature, MultiLineStringFeature
from geo_lib.logging.console import get_job_logger

logger = get_job_logger()


def strip_icon_properties(feature: dict) -> dict:
    """
    Remove icon-related properties from a feature.
    
    Args:
        feature: Feature dictionary with properties
        
    Returns:
        Feature dictionary with icon properties removed
    """
    if not isinstance(feature, dict) or 'properties' not in feature:
        return feature
    
    # Common property names that might contain icon hrefs
    icon_property_names = [
        'marker-symbol',
        'icon',
        'icon-href',
        'iconUrl',
        'icon_url',
        'marker-icon',
        'symbol',
        'styleUrl',  # KML style URLs might reference icons
    ]
    
    # Remove icon properties
    for prop_name in icon_property_names:
        if prop_name in feature['properties']:
            del feature['properties'][prop_name]
    
    # Also check nested structures (e.g., style objects)
    def remove_icons_from_dict(d):
        if not isinstance(d, dict):
            return
        for key, value in list(d.items()):
            if key in icon_property_names:
                del d[key]
            elif isinstance(value, dict):
                remove_icons_from_dict(value)
            elif isinstance(value, list):
                for item in value:
                    if isinstance(item, dict):
                        remove_icons_from_dict(item)
    
    remove_icons_from_dict(feature['properties'])
    
    return feature


def _delete_logs_by_log_id(log_id):
    """Delete all logs from DatabaseLogging table by log_id"""
    deleted_count = DatabaseLogging.objects.filter(log_id=log_id).delete()[0]
    return deleted_count


class BulkImportJob(BaseJob):
    """
    Handles asynchronous bulk import of multiple import queue items.
    Processes items sequentially to avoid database contention.
    """

    def get_job_type(self) -> str:
        return "bulk_import"

    def start_bulk_import_job(self, item_ids: List[int], user_id: int, import_custom_icons: bool = True) -> str:
        """
        Start a bulk import job for multiple import queue items.
        
        Args:
            item_ids: List of ImportQueue item IDs to import
            user_id: ID of the user who owns the items
            import_custom_icons: Whether to import custom icons (default True)
            
        Returns:
            Job ID for tracking the bulk import
        """
        # Create bulk import job
        filename = f"Bulk import of {len(item_ids)} item(s)"
        job_id = self.status_tracker.create_job(filename, user_id, JobType.BULK_IMPORT)

        # Store item IDs and settings in result data
        self.status_tracker.set_job_result(job_id, {
            'item_ids': item_ids,
            'import_custom_icons': import_custom_icons
        })

        # Start the job
        if self.start_job(job_id, item_ids=item_ids, user_id=user_id, import_custom_icons=import_custom_icons):
            return job_id
        else:
            return None

    def _execute_job(self, job_id: str, kwargs: Dict[str, Any]):
        """
        Execute the bulk import job processing logic.
        """
        item_ids = kwargs['item_ids']
        user_id = kwargs['user_id']
        import_custom_icons = kwargs.get('import_custom_icons', True)

        # Get the job for user info
        job = self.status_tracker.get_job(job_id)
        if not job:
            logger.error(f"Bulk import job {job_id} not found")
            return

        try:
            # Update status to processing
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                f"Starting bulk import of {len(item_ids)} item(s)...", 0.0
            )

            # Broadcast WebSocket event for bulk import start
            self._broadcast_job_started(user_id, job_id, item_ids=item_ids)

            # Get all items that belong to the user
            items = ImportQueue.objects.filter(id__in=item_ids, user_id=user_id)
            found_ids = list(items.values_list('id', flat=True))

            # Check if any requested IDs were not found or don't belong to the user
            missing_ids = set(item_ids) - set(found_ids)
            if missing_ids:
                error_msg = f"Items not found or not authorized: {list(missing_ids)}"
                logger.warning(f"Bulk import job {job_id}: {error_msg}")
                self.status_tracker.update_job_status(
                    job_id, ProcessingStatus.FAILED,
                    error_msg, error_message=error_msg
                )
                self._broadcast_job_failed(job_id, error_msg)
                return

            # Process each item sequentially
            total_items = len(found_ids)
            successful_imports = 0
            failed_imports = []
            skipped_items = []

            for index, item in enumerate(items):
                item_progress = (index / total_items) * 100.0
                self.status_tracker.update_job_status(
                    job_id, ProcessingStatus.PROCESSING,
                    f"Importing item {index + 1}/{total_items}: {item.original_filename}...", item_progress
                )
                self._broadcast_job_status_updated(
                    user_id, job_id, "processing", item_progress,
                    f"Importing item {index + 1}/{total_items}: {item.original_filename}...",
                    current_item_id=item.id, current_item_filename=item.original_filename
                )

                try:
                    # Import this item
                    result = self._import_single_item(item, user_id, import_custom_icons)
                    if result['success']:
                        successful_imports += 1
                    else:
                        failed_imports.append({
                            'item_id': item.id,
                            'filename': item.original_filename,
                            'error': result['error']
                        })
                except Exception as e:
                    error_msg = str(e)
                    logger.error(f"Bulk import job {job_id}: Error importing item {item.id}: {error_msg}")
                    logger.error(f"Bulk import error traceback: {traceback.format_exc()}")
                    failed_imports.append({
                        'item_id': item.id,
                        'filename': item.original_filename,
                        'error': error_msg
                    })

            # Mark as completed
            if failed_imports:
                completion_msg = f"Completed: {successful_imports} imported, {len(failed_imports)} failed"
            else:
                completion_msg = f"Successfully imported {successful_imports} item(s)"

            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.COMPLETED,
                completion_msg, 100.0
            )

            # Broadcast completion
            self._broadcast_job_completed(
                user_id, job_id,
                item_ids=found_ids,
                successful_count=successful_imports,
                failed_count=len(failed_imports),
                failed_items=failed_imports
            )

            logger.info(f"Successfully completed bulk import job {job_id}: {successful_imports} imported, {len(failed_imports)} failed")

        except Exception as e:
            error_msg = f"Bulk import job failed: {str(e)}"
            logger.error(f"Bulk import job {job_id} error: {error_msg}")
            logger.error(f"Bulk import job error traceback: {traceback.format_exc()}")

            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.FAILED,
                error_msg, error_message=error_msg
            )

            # Broadcast failure
            self._broadcast_job_failed(job_id, error_msg)

    def _import_single_item(self, import_item: ImportQueue, user_id: int, import_custom_icons: bool) -> Dict[str, Any]:
        """
        Import a single import queue item to the feature store.
        Reuses logic from import_to_featurestore function.
        
        Returns:
            Dict with 'success' (bool) and 'error' (str if failed)
        """
        try:
            # Prevent importing items that have already been imported
            if import_item.imported:
                return {'success': False, 'error': 'Item already imported'}

            # Check for file-level duplicates before importing
            if import_item.geojson_hash:
                earlier_duplicates = ImportQueue.objects.filter(
                    user_id=user_id,
                    geojson_hash=import_item.geojson_hash,
                    imported=False,
                    timestamp__lt=import_item.timestamp
                ).order_by('timestamp').first()
                
                if earlier_duplicates:
                    return {'success': False, 'error': f'Duplicate of "{earlier_duplicates.original_filename}"'}

            # Prepare features for bulk import
            features_to_create = []
            existing_hashes = set()
            current_batch_hashes = set()

            # Get existing feature hashes for this user to avoid duplicates
            existing_features = FeatureStore.objects.filter(user_id=user_id).values_list('geojson_hash', flat=True)
            existing_hashes.update(existing_features)

            # Thread-safe duplicate checking
            duplicate_check_lock = threading.Lock()
            
            def process_feature_with_index(args: Tuple[int, Dict[str, Any]]) -> Optional[FeatureStore]:
                """Wrapper to unpack index and feature for executor.map()"""
                feature_index, feature = args
                return self._process_single_feature_for_import(
                    feature, feature_index, import_item, user_id, import_custom_icons,
                    existing_hashes, current_batch_hashes, duplicate_check_lock
                )

            # Get number of threads from settings
            from django.conf import settings
            num_threads = getattr(settings, 'IMPORT_PROCESSING_THREADS', 4)
            
            # Process features in parallel using ThreadPoolExecutor
            if len(import_item.geofeatures) > 0:
                with ThreadPoolExecutor(max_workers=num_threads) as executor:
                    results = executor.map(
                        process_feature_with_index,
                        enumerate(import_item.geofeatures)
                    )
                    
                    for feature_store in results:
                        if feature_store is not None:
                            features_to_create.append(feature_store)

            # Track successful feature creation
            successful_imports = 0

            # Bulk create all features at once for better performance
            if features_to_create:
                try:
                    FeatureStore.objects.bulk_create(features_to_create, batch_size=1000)
                    successful_imports = len(features_to_create)
                except Exception as e:
                    logger.warning(f"Bulk import failed for user {user_id}, falling back to individual imports: {str(e)}")
                    # Fallback to individual creation if bulk fails
                    for feature in features_to_create:
                        try:
                            feature.save()
                            successful_imports += 1
                        except Exception as individual_error:
                            if "duplicate key" not in str(individual_error).lower():
                                logger.error(f"Error creating individual feature for user {user_id}: {individual_error}")

            # Only mark as imported if at least one feature was successfully created
            if successful_imports > 0:
                # Mark as imported only after successful feature creation
                import_item.imported = True

                # Delete logs before clearing the log_id
                if import_item.log_id:
                    _delete_logs_by_log_id(str(import_item.log_id))

                # Erase some unneeded data
                import_item.geofeatures = []
                import_item.log_id = None

                import_item.save()
                
                # Broadcast WebSocket event for item import
                self._broadcast_item_imported(user_id, import_item.id)

                return {'success': True}
            else:
                return {'success': False, 'error': 'No features were imported'}

        except Exception as e:
            logger.error(f"Error importing item {import_item.id}: {str(e)}")
            logger.error(f"Import error traceback: {traceback.format_exc()}")
            return {'success': False, 'error': str(e)}

    def _process_single_feature_for_import(
        self, feature: Dict[str, Any], feature_index: int, import_item: ImportQueue,
        user_id: int, import_custom_icons: bool, existing_hashes: set,
        current_batch_hashes: set, duplicate_check_lock: threading.Lock
    ) -> Optional[FeatureStore]:
        """
        Process a single feature for import.
        """
        try:
            c = None
            if 'geometry' not in feature or not feature['geometry']:
                return None

            geometry_type = feature['geometry']['type'].lower()
            match geometry_type:
                case 'point':
                    c = PointFeature
                case 'multipoint':
                    c = PointFeature
                case 'linestring':
                    c = LineStringFeature
                case 'multilinestring':
                    c = MultiLineStringFeature
                case 'polygon':
                    c = PolygonFeature
                case 'multipolygon':
                    c = PolygonFeature
                case _:
                    return None
            
            assert c is not None

            # Strip icon properties if import_custom_icons is False
            if not import_custom_icons:
                feature = strip_icon_properties(feature.copy())

            feature_instance = c(**feature)
            # Tags are already generated during processing step, just use existing tags
            existing_tags = feature_instance.properties.tags or []
            feature_instance.properties.tags = existing_tags

            # Create the GeoJSON data
            geojson_data = json.loads(feature_instance.model_dump_json())

            # Generate hash-based ID for the feature
            feature_hash = generate_feature_hash(geojson_data)

            # Check if this feature already exists (thread-safe)
            with duplicate_check_lock:
                if feature_hash in existing_hashes or feature_hash in current_batch_hashes:
                    return None
                current_batch_hashes.add(feature_hash)

            # Update the feature's ID in the GeoJSON data
            geojson_data['properties']['id'] = feature_hash

            # Create geometry object for spatial queries
            geometry = None
            if 'geometry' in geojson_data and geojson_data['geometry']:
                try:
                    geom_data = geojson_data['geometry'].copy()

                    # Handle 3D coordinates
                    if geom_data['type'] == 'Point':
                        coords = geom_data['coordinates']
                        if len(coords) == 2:
                            coords = [coords[0], coords[1], 0.0]
                        elif len(coords) == 3:
                            coords = [coords[0], coords[1], coords[2]]
                        geom_data['coordinates'] = coords
                    elif geom_data['type'] == 'LineString':
                        coords = geom_data['coordinates']
                        geom_data['coordinates'] = [
                            [coord[0], coord[1], coord[2] if len(coord) > 2 else 0.0]
                            for coord in coords
                        ]
                    elif geom_data['type'] == 'Polygon':
                        coords = geom_data['coordinates']
                        geom_data['coordinates'] = [
                            [
                                [coord[0], coord[1], coord[2] if len(coord) > 2 else 0.0]
                                for coord in ring
                            ]
                            for ring in coords
                        ]

                    geometry = GEOSGeometry(json.dumps(geom_data))
                except Exception as e:
                    logger.warning(f"Error creating geometry for feature {feature_index}: {str(e)}")

            # Create FeatureStore object
            return FeatureStore(
                geojson=geojson_data,
                geojson_hash=feature_hash,
                geometry=geometry,
                source=import_item,
                user_id=user_id
            )
        except Exception as e:
            logger.error(f"Error processing feature {feature_index}: {str(e)}")
            return None

    def _broadcast_item_imported(self, user_id: int, item_id: int):
        """Broadcast WebSocket event when an item is imported."""
        from channels.layers import get_channel_layer
        from asgiref.sync import async_to_sync
        from api.models import ImportQueue
        
        channel_layer = get_channel_layer()
        if channel_layer:
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

