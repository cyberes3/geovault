"""
Duplicate detection logic for geospatial features.
Handles finding duplicate features within a file and against the existing feature store.
"""

from datetime import datetime
from typing import List, Dict, Tuple, Any, Optional, Literal

from geo_lib.feature_id import generate_geojson_hash
from geo_lib.processing.duplicate_detection.find import _find_hash_duplicates, _find_geometry_duplicates
from geo_lib.processing.logging import ImportLog


def remove_internal_duplicates(features: List[Dict[str, Any]]) -> Tuple[List[Dict[str, Any]], int]:
    """
    Remove 100% duplicate GeoJSON features and return unique features with count.
    
    Args:
        features: List of GeoJSON feature dictionaries to check for duplicates
        
    Returns:
        Tuple of (unique_features, duplicate_count) where:
        - unique_features: List of unique GeoJSON feature dictionaries
        - duplicate_count: Number of duplicate features that were removed
    """
    if not features:
        return features, 0

    # Track features by hash
    seen_hashes = set()
    unique_features = []
    duplicate_feature_count = 0

    for i, feature in enumerate(features):
        # Generate hash for this feature
        geojson_hash = generate_geojson_hash(feature)

        if geojson_hash in seen_hashes:
            # This is a duplicate
            duplicate_feature_count += 1
        else:
            # This is a unique feature
            seen_hashes.add(geojson_hash)
            unique_features.append(feature)

    return unique_features, duplicate_feature_count


def get_skipped_feature_ids_from_duplicates(
        duplicate_features: List[Dict],
        existing_skipped_ids: Optional[List[str]] = None
) -> set:
    """
    Extract feature IDs from duplicate features to add to skipped_feature_ids.
    
    Args:
        duplicate_features: List of duplicate feature info dicts
        existing_skipped_ids: Optional existing list of skipped feature IDs
    
    Returns:
        Set of feature IDs to skip (including existing ones)
    """
    skipped_feature_ids = set(existing_skipped_ids if existing_skipped_ids else [])

    for dup_info in duplicate_features:
        dup_feature = dup_info.get('feature')
        if dup_feature:
            geojson_hash = generate_geojson_hash(dup_feature)
            feature_id = dup_feature.get('properties', {}).get('geojson_hash', geojson_hash)
            skipped_feature_ids.add(feature_id)

    return skipped_feature_ids


def find_duplicates_for_source(
        features: List[Dict],
        user_id: int,
        source: Literal['feature_store', 'cross_queue'],
        exclude_queue_id: Optional[int] = None,
        exclude_timestamp: Optional[datetime] = None
) -> Tuple[List[Dict], List[Dict], ImportLog]:
    """
    Find all duplicates (hash + geometry) for a specific source with proper priority.
    
    This consolidates hash and geometry checking for a single source, applying the
    priority rule that hash duplicates take precedence over geometry duplicates.
    
    Args:
        features: List of features to check for duplicates
        user_id: User ID to check duplicates for
        source: Source to check - 'feature_store' or 'cross_queue'
        exclude_queue_id: Optional ImportQueue item ID to exclude from cross-queue checks
        exclude_timestamp: Optional timestamp - only check queue items older than this timestamp
    
    Returns:
        Tuple of (remaining_features, all_duplicates, import_log)
        - remaining_features: features that are NOT duplicates in this source
        - all_duplicates: list of duplicate info dicts (hash + geometry), hash duplicates first
        - import_log: log messages from detection
        
    Raises:
        ValueError: If source is not 'feature_store' or 'cross_queue'
    """
    # Validate source parameter
    if source not in ['feature_store', 'cross_queue']:
        raise ValueError(f"Invalid source: '{source}'. Must be 'feature_store' or 'cross_queue'")

    import_log = ImportLog()

    # STEP 1: Find hash duplicates for this source
    hash_duplicates = _find_hash_duplicates(
        features,
        user_id,
        exclude_queue_id=exclude_queue_id,
        exclude_timestamp=exclude_timestamp,
        source_filter=source
    )

    # Build set of features that are hash duplicates
    hash_dup_hashes = {
        generate_geojson_hash(dup['feature'])
        for dup in hash_duplicates if dup.get('feature')
    }

    # STEP 2: Find geometry duplicates for this source (on remaining features)
    remaining_after_hash = [
        f for f in features
        if generate_geojson_hash(f) not in hash_dup_hashes
    ]

    _, geom_duplicates, geom_log = _find_geometry_duplicates(
        remaining_after_hash,
        user_id,
        exclude_queue_id=exclude_queue_id,
        exclude_timestamp=exclude_timestamp,
        source_filter=source
    )
    import_log.extend(geom_log)

    # Build set of features that are geometry duplicates
    geom_dup_hashes = {
        generate_geojson_hash(dup['feature'])
        for dup in geom_duplicates if dup.get('feature')
    }

    # Combine: hash duplicates first (higher priority), then geometry
    all_duplicates = hash_duplicates + geom_duplicates

    # Remaining features: not hash or geometry duplicates
    all_dup_hashes = hash_dup_hashes | geom_dup_hashes
    remaining_features = [
        f for f in features
        if generate_geojson_hash(f) not in all_dup_hashes
    ]

    return remaining_features, all_duplicates, import_log
