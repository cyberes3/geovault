"""
Shared utilities for import operations.
Contains helper functions used by both single and bulk import jobs.
"""

import copy
import json
import threading
import traceback
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, Any, Optional, Tuple, List

from django.contrib.gis.geos import GEOSGeometry
from django.db import IntegrityError
from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer

from api.models import ImportQueue, FeatureStore, DatabaseLogging
from geo_lib.tags.const_strings import prepare_user_tags
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.logging.console import get_job_logger
from geo_lib.processing.duplicate_models import SkippedDuplicates, SkippedDuplicateFeature, DuplicateMatchType
from geo_lib.types.validation import match_geometry_class
from website.settings_utils import get_required_setting
from geo_lib.validation.styling_validation import (
    is_valid_hex_color,
    is_valid_icon_url,
    normalize_hex_color,
    normalize_feature_colors_and_styles,
)
from api.validation.feature_updates import validate_pydantic_model, BulkOperationsPayload
from pydantic import ValidationError

logger = get_job_logger()


# ============================================================================
# Skip Logic Utilities
# ============================================================================

def build_features_to_skip(
    import_item: ImportQueue,
    user_skipped_feature_ids: Optional[List[str]] = None
) -> Tuple[set, set, set]:
    """
    Build sets of features to skip during import.
    
    This function handles the logic for determining which features should be skipped,
    separating geometry duplicates (which are always skipped) from manual user skips
    (which are only respected for non-duplicates).
    
    Args:
        import_item: The ImportQueue item being imported
        user_skipped_feature_ids: Optional list of feature IDs skipped by user in current request
                                  (used by single import, not by bulk import)
    
    Returns:
        Tuple of (geometry_duplicate_hashes, manually_skipped_non_duplicates, all_features_to_skip)
        - geometry_duplicate_hashes: Set of hashes for geometry duplicates (always skipped)
        - manually_skipped_non_duplicates: Set of hashes for manually skipped non-duplicates
        - all_features_to_skip: Combined set of all features to skip
    """
    # Build set of geometry duplicate hashes to auto-skip them
    # This bypasses user skip/restore choices - all geometry duplicates are automatically skipped
    geometry_duplicate_hashes = set()
    if import_item.duplicate_features:
        for dup_info in import_item.duplicate_features:
            if dup_info.get('match_type') == DuplicateMatchType.GEOMETRY:
                dup_feature = dup_info.get('feature')
                if dup_feature:
                    geojson_hash = dup_feature['properties'].get('geojson_hash')
                    if geojson_hash:
                        geometry_duplicate_hashes.add(geojson_hash)
    
    # Get manually skipped features (from user clicking "Skip" button on non-duplicates)
    # These are features the user explicitly doesn't want to import
    user_skipped_ids = set(user_skipped_feature_ids) if user_skipped_feature_ids else set()
    saved_skipped_ids = set(import_item.skipped_feature_ids if import_item.skipped_feature_ids else [])
    manually_skipped = user_skipped_ids.union(saved_skipped_ids)
    
    # Remove geometry duplicates from manually skipped (we handle those separately)
    # This allows us to bypass "restore" on geometry duplicates while respecting manual skips
    manually_skipped_non_duplicates = manually_skipped - geometry_duplicate_hashes
    
    # Combine: ALL geometry duplicates + manually skipped non-duplicates
    all_features_to_skip = geometry_duplicate_hashes.union(manually_skipped_non_duplicates)
    
    return geometry_duplicate_hashes, manually_skipped_non_duplicates, all_features_to_skip


def filter_features_to_process(
    import_item: ImportQueue,
    all_features_to_skip: set
) -> Tuple[List[Dict[str, Any]], int]:
    """
    Filter features to process by removing skipped features.
    
    Args:
        import_item: The ImportQueue item being imported
        all_features_to_skip: Set of feature hashes to skip
    
    Returns:
        Tuple of (features_to_process, skipped_count)
        - features_to_process: List of features that should be processed
        - skipped_count: Number of features that were skipped
    """
    features_to_process = []
    skipped_count = 0
    
    for feature in import_item.geofeatures:
        feature_id = feature['properties'].get('geojson_hash')
        if feature_id in all_features_to_skip:
            skipped_count += 1
            continue
        features_to_process.append(feature)
    
    return features_to_process, skipped_count


# ============================================================================
# Internal Job Result Helpers
# ============================================================================

def job_success_result(imported: int = 0, duplicates_skipped = None, **kwargs) -> Dict[str, Any]:
    """
    Create a standardized success result for job operations.
    
    Args:
        imported: Number of features successfully imported
        duplicates_skipped: Either an int count or a dict with skipped duplicate details
        **kwargs: Additional fields to include in the result
        
    Returns:
        Dictionary with success=True and result data
    """
    result = {'success': True, 'imported': imported}
    if duplicates_skipped:
        result['duplicates_skipped'] = duplicates_skipped
    result.update(kwargs)
    return result


def job_error_result(error_message: str, **kwargs) -> Dict[str, Any]:
    """
    Create a standardized error result for job operations.
    
    Args:
        error_message: Human-readable error message
        **kwargs: Additional fields to include in the result
        
    Returns:
        Dictionary with success=False and error message
    """
    result = {'success': False, 'error': error_message}
    result.update(kwargs)
    return result


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


def validate_bulk_operations_payload(bulk_ops: Dict[str, Any]) -> Tuple[bool, Optional[str]]:
    """
    Validate a bulk_operations payload used for styling/tagging.

    Enforces that only tags, colors, and icon fields can be changed and that
    values have the expected types.

    Args:
        bulk_ops: Dictionary from the request's bulk_operations field

    Returns:
        (is_valid, error_message). error_message is None when is_valid is True.
        
    Note: This function maintains backward compatibility with the old signature.
    It uses Pydantic validation internally but returns the legacy tuple format.
    """
    if not isinstance(bulk_ops, dict):
        return False, "bulk_operations must be a JSON object"

    # Use Pydantic validation
    try:
        validated_dict = validate_pydantic_model(BulkOperationsPayload, bulk_ops)
        return True, None
    except ValidationError:
        return False, "Invalid bulk operations format"


def delete_logs_by_log_id(log_id):
    """Delete all logs from DatabaseLogging table by log_id"""
    deleted_count = DatabaseLogging.objects.filter(log_id=log_id).delete()[0]
    return deleted_count


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


def process_single_feature_for_import(
    feature: Dict[str, Any], feature_index: int, import_item: ImportQueue,
    user_id: int, import_custom_icons: bool, existing_hashes: set,
    current_batch_hashes: set, duplicate_check_lock: threading.Lock,
    queue_hash_to_item: Dict[str, Dict[str, Any]],
    skipped_hash_duplicates: List[SkippedDuplicateFeature],
    skipped_feature_ids: set, geometry_duplicate_hashes: set,
    skipped_geometry_duplicates: List[SkippedDuplicateFeature]
) -> Optional[FeatureStore]:
    """
    Process a single feature for import, including validation, tag generation, and FeatureStore creation.
    This is a worker function designed to be called in parallel.
    
    Args:
        feature: Feature dictionary from geofeatures
        feature_index: Index of the feature (for logging)
        import_item: The ImportQueue item being imported
        user_id: ID of the user importing the feature
        import_custom_icons: Whether to import custom icons
        existing_hashes: Set of feature hashes already in the database
        current_batch_hashes: Set of feature hashes in the current batch (for internal duplicate detection)
        duplicate_check_lock: Lock for thread-safe duplicate checking
        queue_hash_to_item: Map of hash -> queue item info (id, filename)
        skipped_hash_duplicates: List to append skipped hash duplicates to (thread-safe via lock)
        skipped_feature_ids: Set of feature IDs that should be skipped (geometry duplicates from user)
        geometry_duplicate_hashes: Set of feature hashes that are geometry duplicates
        skipped_geometry_duplicates: List to append skipped geometry duplicates to (thread-safe via lock)
        
    Returns:
        FeatureStore object if successful, None if skipped or failed
    """
    try:
        c = None
        if 'geometry' not in feature or not feature['geometry']:
            logger.warning(f"Skipping feature {feature_index} due to missing or empty geometry: {feature.get('properties', {}).get('name', 'Unnamed')}")
            return None

        geometry_type = match_geometry_class(feature['geometry']['type'])

        # Note: Geometry duplicates are no longer automatically blocked here.
        # They are handled via skipped_feature_ids if the user chooses to skip them.
        # Only hash-based duplicates are automatically blocked.

        # Strip icon properties if import_custom_icons is False
        if not import_custom_icons:
            feature = strip_icon_properties(feature.copy())

        feature_instance = geometry_type(**feature)
        # Tags are already generated during processing step, just use existing tags
        existing_tags = feature_instance.properties.tags or []
        # Prepare tags before storing (lowercase and deduplicate)
        existing_tags = prepare_user_tags(existing_tags)
        feature_instance.properties.tags = existing_tags

        # Create the GeoJSON data
        geojson_data = json.loads(feature_instance.model_dump_json())
        
        # Normalize colors and apply style normalization using shared function
        if 'properties' in geojson_data and 'geometry' in geojson_data:
            normalize_feature_colors_and_styles(
                geojson_data['properties'],
                geojson_data['geometry']
            )

        # Generate hash-based ID for the feature
        # Use stored hash from properties if available (calculated on raw data during processing)
        # This ensures consistency with duplicate detection in ProcessJob
        geojson_hash = feature['properties']['geojson_hash']
        # if not geojson_hash:
        #     geojson_hash = generate_geojson_hash(geojson_data)

        feature_name = feature.get('properties', {}).get('name', 'Unnamed')
        
        # Check if this is a geometry duplicate
        # Only skip if user explicitly added it to skipped_feature_ids
        if geojson_hash in geometry_duplicate_hashes and geojson_hash in skipped_feature_ids:
            # User explicitly skipped this geometry duplicate
            logger.info(f"  -> Skipping geometry duplicate (in skip list)")
            with duplicate_check_lock:
                skipped_geometry_duplicates.append(SkippedDuplicateFeature(
                    name=feature_name,
                    hash=geojson_hash
                ))
            return None

        # Check if this feature already exists for this user or in current batch (thread-safe)
        with duplicate_check_lock:
            # Check for cross-queue hash duplicates FIRST (always blocked)
            queue_info = queue_hash_to_item.get(geojson_hash, {})
            if queue_info:
                # This is a cross-queue hash duplicate - always blocked
                logger.info(f"  -> Skipping cross-queue hash duplicate (always blocked)")
                skipped_hash_duplicates.append(SkippedDuplicateFeature(
                    name=feature_name,
                    hash=geojson_hash,
                    queue_item_id=queue_info.get('queue_item_id'),
                    queue_item_filename=queue_info.get('queue_item_filename', 'Unknown')
                ))
                return None

            # Check for hash duplicates from FeatureStore or current batch (always blocked)
            if geojson_hash in existing_hashes or geojson_hash in current_batch_hashes:
                # This is a hash-based duplicate from FeatureStore (blocked automatically)
                logger.info(f"  -> Skipping feature store hash duplicate (always blocked)")
                skipped_hash_duplicates.append(SkippedDuplicateFeature(
                    name=feature_name,
                    hash=geojson_hash
                ))
                return None

            # Add to current batch hashes to prevent duplicates within the same import
            current_batch_hashes.add(geojson_hash)

        # Update the feature's ID in the GeoJSON data
        geojson_data['properties']['geojson_hash'] = geojson_hash

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
            geojson_hash=geojson_hash,
            geometry=geometry,
            source=import_item,
            user_id=user_id
        )
    except Exception as e:
        logger.error(f"Error processing feature {feature_index}: {str(e)}")
        logger.error(f"Feature processing error traceback: {traceback.format_exc()}")
        return None


def apply_bulk_operations(features: List[Dict[str, Any]], bulk_ops: Dict[str, Any]) -> List[Dict[str, Any]]:
    """
    Apply bulk operations (tags, styling) to a list of features.
    Shared utility function used by both single and bulk import jobs.
    
    Args:
        features: List of GeoJSON features
        bulk_ops: Dictionary containing bulk operations (tags, pointColor, pointIcon, lineColor, polyColor)
        
    Returns:
        List of features with bulk operations applied
    """
    if not bulk_ops:
        return features

    result = []
    for feature in features:
        # Skip duplicates
        if feature.get('properties', {}).get('isDuplicate', False):
            result.append(feature)
            continue

        # Create a copy to avoid mutating the original
        modified_feature = copy.deepcopy(feature)

        # Initialize properties if not present
        if 'properties' not in modified_feature:
            modified_feature['properties'] = {}

        # Apply tags (merge with existing tags, avoiding duplicates)
        if bulk_ops.get('tags') and len(bulk_ops['tags']) > 0:
            if 'tags' not in modified_feature['properties']:
                modified_feature['properties']['tags'] = []

            # Merge tags, avoiding duplicates
            existing_tags = set(tag.lower() for tag in modified_feature['properties']['tags'])
            for tag in bulk_ops['tags']:
                lower_tag = tag.lower()
                if lower_tag not in existing_tags:
                    modified_feature['properties']['tags'].append(lower_tag)
                    existing_tags.add(lower_tag)

        geometry_type = modified_feature.get('geometry', {}).get('type')

        # Apply point styling (only if value is not None)
        # Applies to both Point and MultiPoint
        DEFAULT_COLOR = '#ff0000'

        if geometry_type in ('Point', 'MultiPoint'):
            if bulk_ops.get('pointColor') is not None:
                color = bulk_ops['pointColor']
                if is_valid_hex_color(color):
                    modified_feature['properties']['marker-color'] = normalize_hex_color(
                        color
                    )
                else:
                    # Invalid color - set to default red
                    modified_feature['properties']['marker-color'] = DEFAULT_COLOR

            if bulk_ops.get('pointIcon') is not None:
                icon_value = bulk_ops['pointIcon']
                if is_valid_icon_url(icon_value):
                    # Keep a single canonical property plus common aliases for compatibility
                    modified_feature['properties']['icon'] = icon_value
                    modified_feature['properties']['icon_url'] = icon_value
                    modified_feature['properties']['iconUrl'] = icon_value
                    modified_feature['properties']['icon-href'] = icon_value

        # Apply line styling (only if value is not None)
        # Applies to both LineString and MultiLineString
        if geometry_type in ('LineString', 'MultiLineString'):
            if bulk_ops.get('lineColor') is not None:
                color = bulk_ops['lineColor']
                if is_valid_hex_color(color):
                    modified_feature['properties']['stroke'] = normalize_hex_color(
                        color
                    )
                else:
                    # Invalid color - set to default red
                    modified_feature['properties']['stroke'] = DEFAULT_COLOR

        # Apply polygon styling (only if value is not None)
        if geometry_type in ('Polygon', 'MultiPolygon'):
            if bulk_ops.get('polyColor') is not None:
                color = bulk_ops['polyColor']
                if is_valid_hex_color(color):
                    norm_color = normalize_hex_color(color)
                    # Set both stroke (border) and fill to the same color
                    # Fill should have 10% opacity
                    modified_feature['properties']['stroke'] = norm_color
                    modified_feature['properties']['fill'] = norm_color
                    modified_feature['properties']['fill-opacity'] = 0.1
                else:
                    # Invalid color - set to default red
                    modified_feature['properties']['stroke'] = DEFAULT_COLOR
                    modified_feature['properties']['fill'] = DEFAULT_COLOR
                    modified_feature['properties']['fill-opacity'] = 0.1

        result.append(modified_feature)

    return result


def process_features_for_import(
    import_item: ImportQueue,
    user_id: int,
    import_custom_icons: bool,
    features_to_process: Optional[List[Dict[str, Any]]] = None,
    skipped_feature_ids: Optional[set] = None
) -> Tuple[List[FeatureStore], SkippedDuplicates]:
    """
    Process features from an import item and return FeatureStore objects ready for creation.
    Shared utility used by both single and bulk import jobs.
    
    Args:
        import_item: The ImportQueue item being imported
        user_id: ID of the user importing
        import_custom_icons: Whether to import custom icons
        features_to_process: Optional list of features to process (defaults to import_item.geofeatures)
        skipped_feature_ids: Optional set of feature IDs/hashes that should be skipped (coordinate duplicates)
        
    Returns:
        Tuple of (List of FeatureStore objects ready for bulk_create, SkippedDuplicates model)
    """
    if features_to_process is None:
        features_to_process = import_item.geofeatures

    if skipped_feature_ids is None:
        skipped_feature_ids = set()

    # Setup duplicate detection
    features_to_create = []
    existing_hashes = set()
    current_batch_hashes = set()
    skipped_hash_duplicates: List[SkippedDuplicateFeature] = []
    skipped_geometry_duplicates: List[SkippedDuplicateFeature] = []

    # Build set of geometry duplicate hashes from duplicate_features
    geometry_duplicate_hashes = set()
    if import_item.duplicate_features:
        for dup_info in import_item.duplicate_features:
            dup_feature = dup_info.get('feature')
            if dup_feature:
                # Use stored hash if available (preserves original hash from processing)
                geojson_hash = dup_feature['properties'].get('geojson_hash')
                geometry_duplicate_hashes.add(geojson_hash)

    # Get existing feature hashes for this user to avoid duplicates
    existing_features = FeatureStore.objects.filter(user_id=user_id).values_list('geojson_hash', flat=True)
    existing_hashes.update(existing_features)

    # Query other unimported ImportQueue items for cross-queue duplicate detection
    # Only check against older items (by timestamp) - newer items should be marked as duplicates of older ones
    other_queue_items = ImportQueue.objects.filter(
        user_id=user_id,
        imported=False,
        timestamp__lt=import_item.timestamp  # Only older items
    ).exclude(id=import_item.id)

    # Extract feature hashes and build lookup map
    queue_hash_to_item = {}
    for queue_item in other_queue_items:
        for feature in queue_item.geofeatures:
            # Use stored hash if available (preserves original hash from processing)
            geojson_hash = feature.get('properties', {}).get('geojson_hash')
            if not geojson_hash:
                geojson_hash = generate_geojson_hash(feature)
            if geojson_hash not in queue_hash_to_item:
                queue_hash_to_item[geojson_hash] = {
                    'queue_item_id': queue_item.id,
                    'queue_item_filename': queue_item.original_filename
                }

    duplicate_check_lock = threading.Lock()

    def process_feature_with_index(args: Tuple[int, Dict[str, Any]]) -> Optional[FeatureStore]:
        """Wrapper to unpack index and feature for executor.map()"""
        feature_index, feature = args
        result = process_single_feature_for_import(
            feature, feature_index, import_item, user_id, import_custom_icons,
            existing_hashes, current_batch_hashes, duplicate_check_lock,
            queue_hash_to_item, skipped_hash_duplicates,
            skipped_feature_ids, geometry_duplicate_hashes, skipped_geometry_duplicates
        )
        return result

    # Apply bulk operations
    bulk_ops = import_item.bulk_operations or {}
    if bulk_ops:
        features_to_process = apply_bulk_operations(features_to_process, bulk_ops)

    # Process features in parallel
    if len(features_to_process) > 0:
        with ThreadPoolExecutor(max_workers=get_required_setting('IMPORT_PROCESSING_THREADS')) as executor:
            results = executor.map(process_feature_with_index, enumerate(features_to_process))
            for feature_store in results:
                if feature_store is not None:
                    features_to_create.append(feature_store)

    return features_to_create, SkippedDuplicates(
        hash=skipped_hash_duplicates,
        geometry=skipped_geometry_duplicates
    )


def bulk_create_features_with_fallback(
    features_to_create: List[FeatureStore],
    user_id: int
) -> Tuple[int, int]:
    """
    Attempt to bulk create features, falling back to individual saves on failure.
    Shared utility used by both single and bulk import jobs.
    
    Args:
        features_to_create: List of FeatureStore objects to create
        user_id: User ID for logging purposes
        
    Returns:
        Tuple of (successful_imports, duplicates_skipped)
    """
    successful_imports = 0
    duplicates_skipped = 0

    if not features_to_create:
        return 0, 0

    try:
        bulk_batch_size = get_required_setting('BULK_CREATE_BATCH_SIZE')
        FeatureStore.objects.bulk_create(features_to_create, batch_size=bulk_batch_size)
        successful_imports = len(features_to_create)
    except Exception as e:
        logger.warning(f"Bulk import failed for user {user_id}, falling back to individual imports: {str(e)}")
        logger.error(f"Bulk import error traceback: {traceback.format_exc()}")

        # Fallback to individual creation if bulk fails
        for feature in features_to_create:
            try:
                feature.save()
                successful_imports += 1
            except IntegrityError as e:
                # Hash collision - feature already exists for this user
                if 'unique_user_geojson_hash' in str(e).lower():
                    duplicates_skipped += 1
                    # Skip silently - this is expected behavior
                else:
                    # Unexpected integrity error
                    logger.error(f"Unexpected integrity error for user {user_id}: {traceback.format_exc()}")
            except Exception:
                logger.error(f"Error creating individual feature for user {user_id}: {traceback.format_exc()}")

    return successful_imports, duplicates_skipped


def finalize_import_item(import_item: ImportQueue, user_id: int) -> None:
    """
    Mark import item as imported and clean up temporary data.
    Shared utility used by both single and bulk import jobs.
    
    Args:
        import_item: The ImportQueue item to finalize
        user_id: User ID for broadcasting
    """
    import_item.imported = True

    # Delete logs before clearing the log_id
    if import_item.log_id:
        delete_logs_by_log_id(str(import_item.log_id))

    # Erase unneeded data
    import_item.geofeatures = []
    import_item.log_id = None

    import_item.save()

    # Broadcast WebSocket event
    broadcast_item_imported(user_id, import_item.id)
