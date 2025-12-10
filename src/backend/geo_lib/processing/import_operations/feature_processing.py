"""
Feature processing utilities for import operations.
Handles individual feature validation and preparation for database storage.
"""

import json
import threading
import traceback
from typing import Dict, Any, Optional, List

from django.contrib.gis.geos import GEOSGeometry

from api.models import ImportQueue, FeatureStore
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.duplicate_detection.models import SkippedDuplicateFeature
from geo_lib.processing.import_operations.styling import strip_icon_properties
from geo_lib.tags.const_strings import prepare_user_tags
from geo_lib.types.validation import match_geometry_class
from geo_lib.validation.styling_validation import normalize_feature_colors_and_styles

_logger = get_tagged_logger(__name__)


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
            _logger.warning(f"Skipping feature {feature_index} due to missing or empty geometry: {feature.get('properties', {}).get('name', 'Unnamed')}")
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
            _logger.info(f"  -> Skipping geometry duplicate (in skip list)")
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
                _logger.info(f"  -> Skipping cross-queue hash duplicate (always blocked)")
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
                _logger.info(f"  -> Skipping feature store hash duplicate (always blocked)")
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
                _logger.warning(f"Error creating geometry for feature {feature_index}: {type(e).__name__}: {str(e)}")
                _logger.error(f"Geometry creation error traceback for feature {feature_index}: {traceback.format_exc()}")

        # Create FeatureStore object
        return FeatureStore(
            geojson=geojson_data,
            geojson_hash=geojson_hash,
            geometry=geometry,
            source=import_item,
            user_id=user_id
        )
    except Exception as e:
        _logger.error(f"Error processing feature {feature_index}: {str(e)}")
        _logger.error(f"Feature processing error traceback: {traceback.format_exc()}")
        return None
