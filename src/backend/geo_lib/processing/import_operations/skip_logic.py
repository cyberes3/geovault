"""
Skip logic utilities for import operations.
Handles determining which features should be skipped during import.
"""

from typing import List, Optional, Tuple, Dict, Any

from api.models import ImportQueue
from geo_lib.processing.duplicate_detection.models import DuplicateMatchType


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
