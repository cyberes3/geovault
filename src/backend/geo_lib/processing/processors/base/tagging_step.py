"""
Feature tagging step.

Instantiates typed feature wrappers and batch-generates auto tags (type,
import date, source file, elevation, reverse-geocoded location, etc.) for all
processed features using coordinate-deduplicated batch processing, then
applies the results back onto the raw feature dicts.
"""
import traceback
from typing import Any, Callable, Dict, List, Optional

from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.logging import DatabaseLogLevel, ImportLog
from geo_lib.processing.tagging.const_strings import CONST_INTERNAL_TAGS, filter_protected_tags, prepare_user_tags
from geo_lib.processing.tagging.generate import generate_auto_tags_batch
from geo_lib.types.feature import LineStringFeature, MultiLineStringFeature, PointFeature, PolygonFeature

_logger = get_tagged_logger('TAGGING_STEP')

_GEOMETRY_TYPE_TO_FEATURE_CLASS = {
    'point': PointFeature,
    'multipoint': PointFeature,
    'linestring': LineStringFeature,
    'multilinestring': MultiLineStringFeature,
    'polygon': PolygonFeature,
    'multipolygon': PolygonFeature,
}


def tag_features(
    processed_features: List[Dict[str, Any]],
    filename: str,
    normalized_file_data: Optional[str],
    is_canceled: Callable[[], bool],
) -> ImportLog:
    """
    Generate and apply auto tags (including reverse geocoding) for a batch of
    already-split, already-validated features. Modifies `processed_features`
    in-place. Callers should skip invoking this when there are no features to
    tag or minimal processing is requested.
    """
    feature_log = ImportLog()

    # Log start of tagging and reverse geocoding process
    feature_log.add(f"Starting tagging and reverse geocoding for {len(processed_features)} feature(s)", "Tagging and Reverse Geocoding", DatabaseLogLevel.INFO)

    try:
        # Check for cancellation before starting
        if is_canceled():
            feature_log.add("Processing canceled before tagging and reverse geocoding", "Tagging and Reverse Geocoding", DatabaseLogLevel.WARNING)
            return feature_log

        # Create feature instances for all features
        feature_instances = []
        for feature in processed_features:
            try:
                geometry_type = feature['geometry']['type'].lower()
                feature_class = _GEOMETRY_TYPE_TO_FEATURE_CLASS.get(geometry_type)
                if feature_class is None:
                    # Unsupported geometry type, add empty instance to maintain index alignment
                    feature_instances.append(None)
                    continue

                feature_instances.append(feature_class(**feature))
            except Exception as e:
                _logger.warning(f"Failed to create feature instance for tagging: {e}")
                feature_instances.append(None)

        # Check for cancellation after creating instances
        if is_canceled():
            feature_log.add("Processing canceled during feature instance creation", "Tagging and Reverse Geocoding", DatabaseLogLevel.WARNING)
            return feature_log

        # Batch generate all tags for all features at once (including reverse geocoding)
        all_feature_tags = generate_auto_tags_batch(
            [f for f in feature_instances if f is not None],
            import_log=feature_log,
            filename=filename,
            skip_reverse_geocoding=False,  # Include reverse geocoding
            file_content=normalized_file_data
        )

        # Check for cancellation after tag generation
        if is_canceled():
            feature_log.add("Processing canceled after tag generation", "Tagging and Reverse Geocoding", DatabaseLogLevel.WARNING)
            return feature_log

        # Apply tags to features
        tag_index = 0
        for i, feature in enumerate(processed_features):
            if feature_instances[i] is None:
                # Skip features that couldn't be instantiated
                continue

            try:
                auto_tags = all_feature_tags[tag_index]
                tag_index += 1

                # Separate system tags from user tags
                existing_tags = feature.get('properties', {}).get('tags', [])
                if not isinstance(existing_tags, list):
                    existing_tags = []

                # Strip system tags from existing tags (defensive - in case user added them)
                user_tags = filter_protected_tags(existing_tags, CONST_INTERNAL_TAGS)

                # Prepare user tags (lowercase and deduplicate)
                user_tags = prepare_user_tags(user_tags)

                # Store system tags separately
                feature['properties']['system_tags'] = auto_tags
                # Store user tags (filtered to remove any system tags)
                feature['properties']['tags'] = user_tags
            except Exception as tag_error:
                feature_name = feature.get('properties', {}).get('name', 'Unnamed')
                feature_log.add(
                    f"Tag application failed for feature '{feature_name}': {str(tag_error)}",
                    "Tagging and Reverse Geocoding",
                    DatabaseLogLevel.WARNING
                )
                _logger.warning(f"Tag application failed for feature '{feature_name}': {traceback.format_exc()}")

    except Exception as e:
        feature_log.add(
            f"Tagging and reverse geocoding failed: {str(e)}",
            "Tagging and Reverse Geocoding",
            DatabaseLogLevel.ERROR
        )
        _logger.error(f"Tagging and reverse geocoding error: {traceback.format_exc()}")

    return feature_log
