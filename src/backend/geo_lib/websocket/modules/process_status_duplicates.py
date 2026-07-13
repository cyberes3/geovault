"""
Duplicate mapping for the import review UI (`ProcessStatusModule`).

Given a page of an import queue item's features, computes which of them are
hash or geometry duplicates of existing FeatureStore features or of features
in other in-flight (not yet imported) queue items, so the review UI can
highlight them and link to the duplicate's location.

Priority rule: FeatureStore duplicates take precedence over cross-queue
duplicates, and hash duplicates take precedence over geometry duplicates
(geometry duplicates whose hash was already flagged are skipped).
"""
from typing import Any, Dict, List, Set, Tuple

from geo_lib.feature_id import generate_geojson_hash
from geo_lib.processing.duplicate_detection.models import DuplicateMatchType, DuplicateSource


def build_queue_hash_to_item(other_queue_items) -> Dict[str, Dict[str, Any]]:
    """
    Build a mapping of `geojson_hash -> {queue_item_id, queue_item_filename, feature_index}`
    for the first occurrence of each hash across a set of other in-flight
    `ImportQueue` items.
    """
    queue_hash_to_item: Dict[str, Dict[str, Any]] = {}
    for queue_item in other_queue_items:
        for feature_idx, feature in enumerate(queue_item.geofeatures):
            geojson_hash = feature.get('properties', {}).get('geojson_hash')
            if not geojson_hash:
                geojson_hash = generate_geojson_hash(feature)
            if geojson_hash not in queue_hash_to_item:
                queue_hash_to_item[geojson_hash] = {
                    'queue_item_id': queue_item.id,
                    'queue_item_filename': queue_item.original_filename,
                    'feature_index': feature_idx
                }
    return queue_hash_to_item


def build_hash_duplicate_maps(
    geofeatures: List[Dict[str, Any]],
    original_to_new_index: Dict[int, int],
    start_idx: int,
    end_idx: int,
    existing_store_hashes: Set[str],
    hash_to_store_id: Dict[str, int],
    queue_hash_to_item: Dict[str, Dict[str, Any]],
    queue_item_sorted_indices: Dict[int, Dict[int, int]],
) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]], Set[str]]:
    """
    Find hash duplicates for every feature (not just the current page),
    since page-independent state (`all_hash_duplicate_hashes`) is needed to
    correctly suppress geometry duplicates later. Only duplicates that fall
    within [start_idx, end_idx) are included in the returned lists.

    FeatureStore hash matches take precedence over cross-queue hash matches.

    Returns:
        Tuple of (feature_store_hash_duplicates, cross_queue_hash_duplicates, all_hash_duplicate_hashes)
    """
    feature_store_hash_duplicates = []
    cross_queue_hash_duplicates = []
    all_hash_duplicate_hashes: Set[str] = set()

    for original_idx, feature in enumerate(geofeatures):
        geojson_hash = feature.get('properties', {}).get('geojson_hash')
        if not geojson_hash:
            geojson_hash = generate_geojson_hash(feature)

        # Convert to sorted index for page display
        if original_idx not in original_to_new_index:
            continue
        new_idx = original_to_new_index[original_idx]

        # Check FeatureStore hash duplicates first (takes precedence)
        if geojson_hash in existing_store_hashes:
            all_hash_duplicate_hashes.add(geojson_hash)
            if start_idx <= new_idx < end_idx:
                dup_obj = {
                    'hash': geojson_hash,
                    'page_index': new_idx - start_idx,
                    'global_index': new_idx  # For cross-queue navigation
                }
                if geojson_hash in hash_to_store_id:
                    dup_obj['feature_store_id'] = hash_to_store_id[geojson_hash]
                feature_store_hash_duplicates.append(dup_obj)
        # Check cross-queue hash duplicates (only if not in FeatureStore)
        elif geojson_hash in queue_hash_to_item:
            all_hash_duplicate_hashes.add(geojson_hash)
            queue_info = queue_hash_to_item[geojson_hash]
            if start_idx <= new_idx < end_idx:
                # Get sorted index for the target queue item
                target_queue_id = queue_info['queue_item_id']
                target_original_idx = queue_info['feature_index']
                sorted_idx = queue_item_sorted_indices.get(target_queue_id, {}).get(target_original_idx, target_original_idx)

                cross_queue_hash_duplicates.append({
                    'hash': geojson_hash,
                    'page_index': new_idx - start_idx,
                    'global_index': sorted_idx,  # Sorted index in the TARGET queue item
                    'queue_item_id': queue_info['queue_item_id'],
                    'queue_item_filename': queue_info['queue_item_filename']
                })

    return feature_store_hash_duplicates, cross_queue_hash_duplicates, all_hash_duplicate_hashes


def build_geometry_duplicate_maps(
    duplicate_features_list: List[Dict[str, Any]],
    geofeatures: List[Dict[str, Any]],
    original_to_new_index: Dict[int, int],
    start_idx: int,
    end_idx: int,
    all_hash_duplicate_hashes: Set[str],
    queue_item_sorted_indices: Dict[int, Dict[int, int]],
) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]], List[str]]:
    """
    Process the item's stored `duplicate_features` (already filtered to
    exclude hash duplicates during processing) into page-scoped geometry
    duplicate maps.

    Returns:
        Tuple of (feature_store_geometry_duplicates, cross_queue_geometry_duplicates,
        geometry_duplicate_hashes_for_skipping)
    """
    feature_store_geometry_duplicates = []
    cross_queue_geometry_duplicates = []
    geometry_duplicate_hashes_for_skipping = []

    for dup_info in duplicate_features_list:
        # Check source and match_type to categorize properly
        source = dup_info.get('source')
        match_type = dup_info.get('match_type')
        dup_feature = dup_info.get('feature')
        existing_features = dup_info.get('existing_features', [])

        if not dup_feature or not source or not match_type:
            continue

        # Get feature hash
        dup_geojson_hash = dup_feature.get('properties', {}).get('geojson_hash')
        if not dup_geojson_hash:
            dup_geojson_hash = generate_geojson_hash(dup_feature)

        # Skip if this is a hash duplicate (already processed above)
        if dup_geojson_hash in all_hash_duplicate_hashes:
            continue

        # Only process geometry duplicates here
        if match_type != DuplicateMatchType.GEOMETRY:
            continue

        # Find the feature in geofeatures to get its index
        feature_idx = None
        for idx, feat in enumerate(geofeatures):
            feat_geojson_hash = feat.get('properties', {}).get('geojson_hash')
            if not feat_geojson_hash:
                feat_geojson_hash = generate_geojson_hash(feat)
            if feat_geojson_hash == dup_geojson_hash:
                feature_idx = idx
                break

        if feature_idx is None or feature_idx not in original_to_new_index:
            continue

        new_idx = original_to_new_index[feature_idx]

        # Track for skipped_feature_ids
        geometry_duplicate_hashes_for_skipping.append(dup_geojson_hash)

        # Only add to arrays if on current page
        if start_idx <= new_idx < end_idx:
            dup_obj = {
                'hash': dup_geojson_hash,
                'page_index': new_idx - start_idx,
                'global_index': new_idx  # For cross-queue navigation
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
                        # Convert original feature_index to sorted index for navigation
                        if 'feature_index' in first_existing:
                            target_queue_id = first_existing['id']
                            target_original_idx = first_existing['feature_index']
                            sorted_idx = queue_item_sorted_indices.get(target_queue_id, {}).get(target_original_idx, target_original_idx)
                            dup_obj['global_index'] = sorted_idx

            # Add to appropriate array based on source
            if source == DuplicateSource.FEATURE_STORE:
                feature_store_geometry_duplicates.append(dup_obj)
            elif source == DuplicateSource.CROSS_QUEUE:
                cross_queue_geometry_duplicates.append(dup_obj)

    return feature_store_geometry_duplicates, cross_queue_geometry_duplicates, geometry_duplicate_hashes_for_skipping
