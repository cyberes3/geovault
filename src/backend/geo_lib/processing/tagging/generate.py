import traceback
from typing import Optional, List

from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.logging import DatabaseLogLevel
from geo_lib.processing.tagging import _tag_generators, GeocodingTagGenerator
from geo_lib.types.feature import GeoFeatureSupported

_logger = get_tagged_logger(__name__)


def generate_auto_tags(
        feature: GeoFeatureSupported,
        import_log,
        filename: Optional[str] = None,
        skip_geocoding: bool = False
) -> List[str]:
    """
    Generate automatic tags for a single feature using all registered tag generators.

    For processing multiple features, prefer using generate_auto_tags_batch()
    for better performance with coordinate deduplication.

    Args:
        feature: The feature to generate tags for
        import_log: ImportLog for database logging
        filename: Optional original filename to add as source-file tag
        skip_geocoding: If True, skip the GeocodingTagGenerator (for async processing)

    Returns:
        List of tag strings
    """
    # Call batch version with single feature for consistency
    result = generate_auto_tags_batch([feature], import_log, filename, skip_geocoding)
    return result[0] if result else []


def generate_auto_tags_batch(
        features: List[GeoFeatureSupported],
        import_log,
        filename: Optional[str] = None,
        skip_geocoding: bool = False
) -> List[List[str]]:
    """
    Generate tags for multiple features at once with batched geocoding.

    This is the preferred method for processing multiple features as it:
    - Deduplicates coordinates before geocoding
    - Makes fewer API calls
    - Leverages cache more efficiently

    Args:
        features: List of features to generate tags for
        import_log: ImportLog for database logging
        filename: Optional original filename to add as source-file tag
        skip_geocoding: If True, skip the GeocodingTagGenerator

    Returns:
        List of tag lists (one per feature)
    """
    if not features:
        return []

    # Separate generators into batch-capable and single-only
    geocoding_gen = None
    other_generators = []

    for generator in _tag_generators:
        if isinstance(generator, GeocodingTagGenerator):
            geocoding_gen = generator
        else:
            other_generators.append(generator)

    # Process non-geocoding generators per-feature
    all_feature_tags = [[] for _ in features]

    for generator in other_generators:
        for i, feature in enumerate(features):
            try:
                tags = generator.process(feature, import_log=import_log, filename=filename)
                if tags:
                    all_feature_tags[i].extend(tags)
            except:
                _logger.warning(f"Tag generator {generator.__class__.__name__} failed for feature {i}: {traceback.format_exc()}")
                import_log.add(
                    f"Tag generator {generator.__class__.__name__} failed",
                    "Tagging",
                    DatabaseLogLevel.WARNING
                )

    # Batch process geocoding for ALL features at once
    if geocoding_gen and not skip_geocoding:
        try:
            geocode_tags = geocoding_gen.process_batch(features, import_log=import_log)
            for i, tags in geocode_tags.items():
                all_feature_tags[i].extend(tags)
        except:
            _logger.warning(f"Batch geocoding failed: {traceback.format_exc()}")
            import_log.add(
                f"Batch geocoding failed",
                "Tagging",
                DatabaseLogLevel.WARNING
            )

    # Post-processing for each feature
    for i in range(len(features)):
        # Handle conflicting type tags
        if 'type:track' in all_feature_tags[i]:
            all_feature_tags[i] = [
                tag for tag in all_feature_tags[i] if tag not in ['type:line', 'type:point']
            ]

        # Ensure all tags are strings
        all_feature_tags[i] = [str(tag) for tag in all_feature_tags[i]]

    return all_feature_tags
