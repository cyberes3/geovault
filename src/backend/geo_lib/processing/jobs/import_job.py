"""
Import job processor for asynchronous import operations.
Handles importing a single import queue item to the feature store.
"""

import json
import threading
import traceback
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, Any, List, Tuple, Optional

from django.contrib.gis.geos import GEOSGeometry

from api.models import ImportQueue, FeatureStore
from geo_lib.const_strings import prepare_user_tags
from geo_lib.feature_id import generate_feature_hash
from geo_lib.logging.console import get_job_logger
from geo_lib.processing.import_utils import (
    strip_icon_properties,
    delete_logs_by_log_id,
    broadcast_item_imported
)
from geo_lib.processing.jobs.base_job import BaseJob
from geo_lib.processing.status_tracker import ProcessingStatus
from geo_lib.types.feature import PointFeature, LineStringFeature, MultiLineStringFeature, PolygonFeature
from website.settings_utils import get_required_setting

logger = get_job_logger()


class ImportJob(BaseJob):
    """
    Handles asynchronous import of a single import queue item to the feature store.
    """

    def get_job_type(self) -> str:
        return "import"

    def start_import_job(self, item_id: int, user_id: int, import_custom_icons: bool = True, skipped_feature_ids: List[str] = None) -> str:
        """
        Start an import job for a single import queue item.
        
        Args:
            item_id: ImportQueue item ID to import
            user_id: ID of the user who owns the item
            import_custom_icons: Whether to import custom icons (default True)
            skipped_feature_ids: List of feature IDs to skip during import
            
        Returns:
            Job ID string
        """
        if skipped_feature_ids is None:
            skipped_feature_ids = []

        # Create a job
        import_item = ImportQueue.objects.get(id=item_id)
        job_id = self.status_tracker.create_job(f"Import {import_item.original_filename}", user_id)

        # Start the job
        self.start_job(
            job_id,
            item_id=item_id,
            user_id=user_id,
            import_custom_icons=import_custom_icons,
            skipped_feature_ids=skipped_feature_ids
        )

        return job_id

    def _execute_job(self, job_id: str, kwargs: Dict[str, Any]):
        """
        Execute the import job processing logic.
        """
        item_id = kwargs['item_id']
        user_id = kwargs['user_id']
        import_custom_icons = kwargs.get('import_custom_icons', True)
        skipped_feature_ids = set(kwargs.get('skipped_feature_ids', []))

        try:
            # Get the import queue item
            import_item = ImportQueue.objects.get(id=item_id, user_id=user_id)
        except ImportQueue.DoesNotExist:
            error_msg = f"Import queue item {item_id} not found"
            logger.error(error_msg)
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.FAILED,
                error_msg,
                error_message=error_msg
            )
            return

        # Update status
        self.status_tracker.update_job_status(
            job_id, ProcessingStatus.PROCESSING,
            "Starting feature import...", 10.0
        )

        # Prepare features for bulk import
        features_to_create = []
        existing_hashes = set()
        current_batch_hashes = set()  # Track hashes in current import batch

        # Get existing feature hashes for this user to avoid duplicates
        existing_features = FeatureStore.objects.filter(user_id=user_id).values_list('file_hash', flat=True)
        existing_hashes.update(existing_features)

        # Thread-safe duplicate checking
        duplicate_check_lock = threading.Lock()

        def process_feature_with_index(args: Tuple[int, Dict[str, Any]]) -> Optional[FeatureStore]:
            """Wrapper to unpack index and feature for executor.map()"""
            feature_index, feature = args
            return process_single_feature_for_import(feature, feature_index)

        def process_single_feature_for_import(feature: Dict[str, Any], feature_index: int) -> Optional[FeatureStore]:
            """
            Process a single feature for import, including validation, tag generation, and FeatureStore creation.
            This is a worker function designed to be called in parallel.
            
            Args:
                feature: Feature dictionary from geofeatures
                feature_index: Index of the feature (for logging)
                
            Returns:
                FeatureStore object if successful, None if skipped or failed
            """
            try:
                c = None
                if 'geometry' not in feature or not feature['geometry']:
                    logger.warning(f"Skipping feature {feature_index} due to missing or empty geometry: {feature.get('properties', {}).get('name', 'Unnamed')}")
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
                        feature_name = feature.get('properties', {}).get('name', 'Unnamed')
                        logger.warning(f"Skipping feature {feature_index} '{feature_name}' due to unsupported geometry type: {geometry_type}")
                        return None

                assert c is not None

                # Strip icon properties if import_custom_icons is False
                if not import_custom_icons:
                    feature = strip_icon_properties(feature.copy())

                feature_instance = c(**feature)
                # Tags are already generated during processing step, just use existing tags
                existing_tags = feature_instance.properties.tags or []
                # Prepare tags before storing (lowercase and deduplicate)
                existing_tags = prepare_user_tags(existing_tags)
                feature_instance.properties.tags = existing_tags

                # Create the GeoJSON data
                geojson_data = json.loads(feature_instance.model_dump_json())

                # Generate hash-based ID for the feature
                feature_hash = generate_feature_hash(geojson_data)

                # Check if this feature already exists for this user or in current batch (thread-safe)
                with duplicate_check_lock:
                    if feature_hash in existing_hashes or feature_hash in current_batch_hashes:
                        # Skip importing duplicate features
                        feature_name = geojson_data.get('properties', {}).get('name', 'Unnamed')
                        # Skipping duplicate feature (normal operation)
                        return None

                    # Add to current batch hashes to prevent duplicates within the same import
                    current_batch_hashes.add(feature_hash)

                # Update the feature's ID in the GeoJSON data
                geojson_data['properties']['id'] = feature_hash

                # Create geometry object for spatial queries
                geometry = None
                if 'geometry' in geojson_data and geojson_data['geometry']:
                    try:
                        # Ensure coordinates are properly formatted for GEOSGeometry
                        geom_data = geojson_data['geometry'].copy()

                        # Handle 3D coordinates by ensuring they're properly structured
                        if geom_data['type'] == 'Point':
                            coords = geom_data['coordinates']
                            # Ensure Point has exactly 3 coordinates (x, y, z) or 2 (x, y)
                            if len(coords) == 2:
                                coords = [coords[0], coords[1], 0.0]  # Add Z=0 for 2D points
                            elif len(coords) == 3:
                                coords = [coords[0], coords[1], coords[2]]  # Keep 3D
                            geom_data['coordinates'] = coords

                        elif geom_data['type'] == 'LineString':
                            coords = geom_data['coordinates']
                            # Ensure each coordinate in LineString has 3 dimensions
                            geom_data['coordinates'] = [
                                [coord[0], coord[1], coord[2] if len(coord) > 2 else 0.0]
                                for coord in coords
                            ]

                        elif geom_data['type'] == 'Polygon':
                            coords = geom_data['coordinates']
                            # Ensure each coordinate in Polygon has 3 dimensions
                            geom_data['coordinates'] = [
                                [
                                    [coord[0], coord[1], coord[2] if len(coord) > 2 else 0.0]
                                    for coord in ring
                                ]
                                for ring in coords
                            ]

                        geometry = GEOSGeometry(json.dumps(geom_data))
                    except Exception as e:
                        # Log internal error details for debugging - don't expose to user
                        logger.warning(f"Error creating geometry for feature {feature_index}: {type(e).__name__}: {str(e)}")
                        logger.error(f"Geometry creation error traceback for feature {feature_index}: {traceback.format_exc()}")

                # Create FeatureStore object
                return FeatureStore(
                    geojson=geojson_data,
                    file_hash=feature_hash,
                    geometry=geometry,
                    source=import_item,
                    user_id=user_id
                )
            except Exception as e:
                feature_name = feature.get('properties', {}).get('name', 'Unnamed')
                logger.error(f"Error processing feature {feature_index} '{feature_name}': {type(e).__name__}: {str(e)}")
                logger.error(f"Feature processing error traceback: {traceback.format_exc()}")
                return None

        # Filter out skipped features before processing
        features_to_process = []
        skipped_count = 0
        for feature in import_item.geofeatures:
            feature_id = feature.get('properties', {}).get('id')
            if feature_id and feature_id in skipped_feature_ids:
                skipped_count += 1
                continue
            features_to_process.append(feature)

        # Update progress
        self.status_tracker.update_job_status(
            job_id, ProcessingStatus.PROCESSING,
            f"Processing {len(features_to_process)} features...", 30.0
        )

        # Get number of threads from settings
        num_threads = get_required_setting('IMPORT_PROCESSING_THREADS')

        # Process features in parallel using ThreadPoolExecutor
        if len(features_to_process) > 0:
            with ThreadPoolExecutor(max_workers=num_threads) as executor:
                # Process all features in parallel and collect results
                results = executor.map(
                    process_feature_with_index,
                    enumerate(features_to_process)
                )

                # Collect results from all workers
                for feature_store in results:
                    if feature_store is not None:
                        features_to_create.append(feature_store)

        # Update progress
        self.status_tracker.update_job_status(
            job_id, ProcessingStatus.PROCESSING,
            f"Importing {len(features_to_create)} features to database...", 70.0
        )

        # Track successful feature creation
        successful_imports = 0

        # Bulk create all features at once for better performance
        if features_to_create:
            try:
                # Importing features to database
                bulk_batch_size = get_required_setting('BULK_CREATE_BATCH_SIZE')
                FeatureStore.objects.bulk_create(features_to_create, batch_size=bulk_batch_size)
                successful_imports = len(features_to_create)
                # Features imported successfully
            except Exception as e:
                logger.warning(f"Bulk import failed for user {user_id}, falling back to individual imports: {str(e)}")
                logger.error(f"Bulk import error traceback: {traceback.format_exc()}")
                # Fallback to individual creation if bulk fails
                for feature in features_to_create:
                    try:
                        feature.save()
                        successful_imports += 1
                    except Exception as individual_error:
                        logger.error(f"Error creating individual feature for user {user_id}: {individual_error}")
                        logger.error(f"Individual feature creation error traceback: {traceback.format_exc()}")
                        # If it's a duplicate key error, that's expected and we can continue
                        if "duplicate key" not in str(individual_error).lower():
                            logger.error(f"Unexpected error creating feature for user {user_id}: {individual_error}")

                # Fallback import completed

        # Log final summary
        total_processed = len(import_item.geofeatures)
        total_imported = successful_imports
        total_skipped_duplicates = total_processed - skipped_count - len(features_to_create)  # Features skipped due to duplicates or errors

        # Build success message
        msg_parts = [f'Successfully imported {total_imported} features']
        if skipped_count > 0:
            msg_parts.append(f'{skipped_count} skipped by user')
        if total_skipped_duplicates > 0:
            msg_parts.append(f'{total_skipped_duplicates} already existed')
        success_msg = ' (' + ', '.join(msg_parts[1:]) + ')' if len(msg_parts) > 1 else ''
        success_msg = msg_parts[0] + success_msg

        # Only mark as imported and proceed with cleanup if at least one feature was successfully created
        if successful_imports > 0:
            # Mark as imported only after successful feature creation
            import_item.imported = True

            # Delete logs before clearing the log_id
            if import_item.log_id:
                delete_logs_by_log_id(str(import_item.log_id))

            # Erase some unneeded data since it's not needed anymore now that it's in the feature store.
            import_item.geofeatures = []
            import_item.log_id = None

            import_item.save()

            # Broadcast WebSocket event for item import
            broadcast_item_imported(user_id, item_id)

            # Mark job as completed
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.COMPLETED,
                success_msg, 100.0
            )
        else:
            # No features were successfully imported
            logger.warning(f"Import failed for user {user_id}: No features were imported from '{import_item.original_filename}'")

            # Determine reason for failure
            if len(features_to_create) == 0:
                if total_processed == 0:
                    reason = "No features found in the file"
                else:
                    reason = f"All {total_processed} features were skipped (duplicates, missing geometry, or unsupported types)"
            else:
                reason = f"Failed to create {len(features_to_create)} features in the database"

            error_msg = f'No features were imported. {reason}.'
            self.status_tracker.update_job_status(
                job_id, ProcessingStatus.FAILED,
                error_msg,
                error_message=error_msg
            )
