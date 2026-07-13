"""
Feature splitting and validation step.

Splits complex geometries (GeometryCollection, MultiPoint, MultiPolygon) into
individual features, validates coordinates, and normalizes properties. Does
NOT generate tags -- that happens in the separate tagging step. Runs in
parallel across a thread pool since each raw feature is independent.
"""
import traceback
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Any, Callable, Dict, List, Tuple

from geo_lib.feature_id import generate_geojson_hash
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.geo import extract_track_created_date, geojson_property_generation, split_complex_geometries
from geo_lib.processing.logging import DatabaseLogLevel, ImportLog
from geo_lib.types.geojson import GeojsonRawProperty
from geo_lib.utils.date_parser import parse_date_field
from geo_lib.validation.geometry_validation import GeometryValidationError, validate_coordinates_values

_logger = get_tagged_logger('FEATURE_SPLITTING')

_SUPPORTED_GEOMETRY_TYPES = ['Point', 'MultiPoint', 'LineString', 'MultiLineString', 'Polygon', 'MultiPolygon']


def process_single_feature(
    feature: Dict[str, Any],
    is_canceled: Callable[[], bool],
) -> Tuple[List[Dict[str, Any]], ImportLog, int, bool]:
    """
    Split and validate a single raw feature. Worker for `split_and_validate_features()`.

    Returns:
        Tuple of (processed_features_list, feature_log, skipped_count, was_split)
    """
    # Check for cancellation at the very start
    if is_canceled():
        return [], ImportLog(), 0, False

    feature_log = ImportLog()
    processed_features = []
    skipped_count = 0
    was_split = False

    # Split complex geometries (GeometryCollection, MultiPoint, MultiPolygon) into separate features
    split_features = split_complex_geometries(feature)

    # Check if this feature was split
    if len(split_features) > 1:
        was_split = True

    # Skip features with no valid geometry
    if not split_features:
        skipped_count += 1
        return processed_features, feature_log, skipped_count, was_split

    for split_feature in split_features:
        # Check for cancellation before processing each split feature
        if is_canceled():
            break

        # Validate coordinates
        try:
            validate_coordinates_values(split_feature['geometry'])
        except GeometryValidationError as e:
            feature_name = split_feature.get('properties', {}).get('name', 'Unnamed')
            feature_log.add(
                f"Skipping feature '{feature_name}' due to invalid coordinates: {str(e)}",
                "Feature Processing",
                DatabaseLogLevel.WARNING
            )
            skipped_count += 1
            continue

        if split_feature['geometry']['type'] not in _SUPPORTED_GEOMETRY_TYPES:
            feature_log.add(f'Skipping unsupported geometry type: {split_feature["geometry"]["type"]}', 'Feature Processing', DatabaseLogLevel.WARNING)
            skipped_count += 1
            continue

        try:
            # Extract track created date BEFORE normalization (to ensure coordinateProperties is available)
            # Only extract if created date is not already present
            original_properties = split_feature.get('properties', {})
            if not original_properties.get('created'):
                track_timestamp = extract_track_created_date(split_feature)
                # Set created date in properties BEFORE normalization so it's preserved
                if track_timestamp:
                    split_feature['properties']['created'] = track_timestamp

            # Check if created date is the Unix epoch (1970-01-01 00:00:00) and log it
            created_date_value = split_feature.get('properties', {}).get('created')
            if created_date_value:
                parsed_date = parse_date_field(created_date_value)
                if parsed_date is None:
                    # The date was filtered out as epoch date
                    feature_name = split_feature.get('properties', {}).get('name', 'Unnamed')
                    feature_log.add(
                        f"Feature '{feature_name}' has created date 1970-01-01 00:00:00 (Unix epoch), treating as no date provided",
                        'Feature Processing',
                        DatabaseLogLevel.DEBUG
                    )

            # First, normalize raw togeojson output (converts feature_tags -> tags, etc.)
            split_feature['properties'] = GeojsonRawProperty(**split_feature['properties']).model_dump(mode='json', exclude_none=True, by_alias=True)

            # Then validate and normalize properties with styling (uses PropertiesModel)
            split_feature['properties'] = geojson_property_generation(split_feature)

            # Finally, generate the geojson hash after all the normalization is complete
            split_feature['properties']['geojson_hash'] = generate_geojson_hash(split_feature)

            # Check for cancellation before finalizing feature
            if is_canceled():
                break

            processed_features.append(split_feature)
        except Exception:
            feature_name = split_feature.get('properties', {}).get('name', 'Unnamed')
            feature_log.add(f"Failed to process feature '{feature_name}', skipping", 'Feature Processing', DatabaseLogLevel.WARNING)
            _logger.error(f"Feature processing error for '{feature_name}': {traceback.format_exc()}")
            skipped_count += 1

    return processed_features, feature_log, skipped_count, was_split


def split_and_validate_features(
    features: List[Dict[str, Any]],
    num_threads: int,
    is_canceled: Callable[[], bool],
) -> Tuple[List[Dict[str, Any]], ImportLog]:
    """
    Split complex geometries and validate coordinates for a batch of raw
    features, processing them in parallel across a thread pool.

    Uses `as_completed()` rather than `map()` so cancellation can be checked
    between individual feature results, rather than only after the whole batch.
    """
    processed_features = []
    feature_log = ImportLog()
    skipped_count = 0
    geometry_collection_count = 0

    feature_log.add(f"Processing {len(features)} raw features from file", "Feature Processing", DatabaseLogLevel.INFO)

    # Check for cancellation before starting
    if is_canceled():
        feature_log.add("Processing canceled before feature processing started", "Feature Processing", DatabaseLogLevel.WARNING)
        return processed_features, feature_log

    if len(features) > 0:
        executor = ThreadPoolExecutor(max_workers=num_threads)
        executor_shutdown_called = False
        try:
            # Submit all tasks
            future_to_feature = {
                executor.submit(process_single_feature, feature, is_canceled): feature
                for feature in features
            }

            # Collect results as they complete, checking for cancellation
            completed_count = 0
            canceled = False
            for future in as_completed(future_to_feature):
                # Check for cancellation before processing each result
                if is_canceled():
                    if not canceled:
                        feature_log.add(f"Processing canceled after {completed_count} features", "Feature Processing", DatabaseLogLevel.WARNING)
                        canceled = True
                        # Cancel remaining futures (they'll finish but we won't process results)
                        for remaining_future in future_to_feature:
                            if not remaining_future.done():
                                remaining_future.cancel()
                        # Shutdown executor without waiting for remaining tasks
                        executor.shutdown(wait=False)
                        executor_shutdown_called = True
                        # Break immediately - don't process any more results
                        break

                # Only process results if not canceled
                if not canceled:
                    try:
                        result_features, result_log, result_skipped, was_split = future.result()
                        processed_features.extend(result_features)
                        feature_log.extend(result_log)
                        skipped_count += result_skipped

                        # Track what type of split occurred by checking the original feature
                        if was_split:
                            original_feature = future_to_feature[future]
                            original_geom_type = original_feature.get('geometry', {}).get('type', '')
                            if original_geom_type == 'GeometryCollection':
                                geometry_collection_count += 1
                        completed_count += 1
                    except Exception as e:
                        feature = future_to_feature[future]
                        feature_name = feature.get('properties', {}).get('name', 'Unnamed')
                        _logger.error(f"Error processing feature '{feature_name}': {traceback.format_exc()}")
                        feature_log.add(f"Error processing feature '{feature_name}': {str(e)}", "Feature Processing", DatabaseLogLevel.ERROR)
                        skipped_count += 1
                        completed_count += 1
                else:
                    # Cancellation detected - skip processing this result
                    completed_count += 1
        finally:
            # Ensure executor is always properly shut down
            if not executor_shutdown_called:
                # If not already shut down, wait for all tasks to complete
                executor.shutdown(wait=True)

    # Log summary
    if is_canceled():
        feature_log.add(f"Processing was canceled. Processed {len(processed_features)} features before cancellation", "Feature Processing", DatabaseLogLevel.WARNING)
    else:
        if geometry_collection_count > 0:
            feature_log.add(f"Split {geometry_collection_count} geometry collection(s) into individual features", "Feature Processing", DatabaseLogLevel.INFO)

        if skipped_count > 0:
            feature_log.add(f"Skipped {skipped_count} features (invalid geometry or unsupported type)", "Feature Processing", DatabaseLogLevel.INFO)

        feature_log.add(f"Successfully processed {len(processed_features)} features", "Feature Processing", DatabaseLogLevel.INFO)

    return processed_features, feature_log
