"""
Tagging system with modular tag generators.

This module provides a plugin-based architecture for generating tags.
Individual tag generator modules can be added to the modules/ directory
and will be automatically discovered and executed.
"""
from typing import List, Optional

from geo_lib.logging.console import get_job_logger
from geo_lib.processing.logging import DatabaseLogLevel
from geo_lib.processing.tagging.base import TagGenerator
from geo_lib.processing.tagging.modules.driving_detection import DrivingDetectionTagGenerator
from geo_lib.processing.tagging.modules.elevation import ElevationTagGenerator
from geo_lib.processing.tagging.modules.feature_date import FeatureDateTagGenerator, update_feature_date_tags
from geo_lib.processing.tagging.modules.geocoding import GeocodingTagGenerator
from geo_lib.processing.tagging.modules.geometry_type import GeometryTypeTagGenerator
from geo_lib.processing.tagging.modules.import_date import ImportDateTagGenerator
from geo_lib.processing.tagging.modules.source_file import SourceFileTagGenerator
from geo_lib.processing.tagging.modules.track_detection import TrackDetectionTagGenerator
from geo_lib.types.feature import GeoFeatureSupported

# Export update_feature_date_tags for backward compatibility

# Registry of tag generators
_tag_generators: List[TagGenerator] = []


def _discover_tag_generators():
    """
    Discover and register all TagGenerator classes.
    This is called once on module import.
    """
    global _tag_generators

    if _tag_generators:
        return  # Already discovered

    # Register all tag generators
    generators = [
        GeometryTypeTagGenerator(),
        ImportDateTagGenerator(),
        FeatureDateTagGenerator(),
        TrackDetectionTagGenerator(),
        DrivingDetectionTagGenerator(),
        SourceFileTagGenerator(),
        ElevationTagGenerator(),
        GeocodingTagGenerator(),
    ]

    _tag_generators.extend(generators)

    # Sort by priority (lower priority numbers execute first)
    _tag_generators.sort(key=lambda g: g.priority)


def get_internal_tags() -> List[str]:
    """
    Get the list of internal/system tag prefixes from registered tag generators.
    
    This function dynamically builds the list of internal tags by extracting
    the tag names from all registered tag generators.
    
    Returns:
        List of internal tag prefix strings (e.g., ['type', 'import-year', 'elevation'])
    """
    # Discover generators if not already done
    if not _tag_generators:
        _discover_tag_generators()

    # Collect all tag names from all generators
    internal_tags = []
    for generator in _tag_generators:
        internal_tags.extend(generator.tag_names)

    return internal_tags


def generate_auto_tags_batch(
        features: List[GeoFeatureSupported],
        import_log=None,
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
        import_log: Optional ImportLog for database logging
        filename: Optional original filename to add as source-file tag
        skip_geocoding: If True, skip the GeocodingTagGenerator
        
    Returns:
        List of tag lists (one per feature)
    """
    if not features:
        return []
    
    # Discover generators if not already done
    if not _tag_generators:
        _discover_tag_generators()
    
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
            except Exception as e:
                logger = get_job_logger()
                logger.warning(f"Tag generator {generator.__class__.__name__} failed for feature {i}: {e}")
                if import_log:
                    import_log.add(
                        f"Tag generator {generator.__class__.__name__} failed: {str(e)}",
                        "Tagging",
                        DatabaseLogLevel.WARNING
                    )
    
    # Batch process geocoding for ALL features at once
    if geocoding_gen and not skip_geocoding:
        try:
            geocode_tags = geocoding_gen.process_batch(features, import_log=import_log)
            for i, tags in geocode_tags.items():
                all_feature_tags[i].extend(tags)
        except Exception as e:
            logger = get_job_logger()
            logger.warning(f"Batch geocoding failed: {e}")
            if import_log:
                import_log.add(
                    f"Batch geocoding failed: {str(e)}",
                    "Tagging",
                    DatabaseLogLevel.WARNING
                )
    
    # Post-processing for each feature
    for i in range(len(features)):
        # Handle conflicting type tags
        if 'type:track' in all_feature_tags[i]:
            all_feature_tags[i] = [tag for tag in all_feature_tags[i] 
                                   if tag not in ['type:line', 'type:point']]
        
        # Ensure all tags are strings
        all_feature_tags[i] = [str(tag) for tag in all_feature_tags[i]]
    
    return all_feature_tags


def generate_auto_tags(
        feature: GeoFeatureSupported,
        import_log=None,
        filename: Optional[str] = None,
        skip_geocoding: bool = False
) -> List[str]:
    """
    Generate automatic tags for a single feature using all registered tag generators.
    
    For processing multiple features, prefer using generate_auto_tags_batch() 
    for better performance with coordinate deduplication.
    
    Args:
        feature: The feature to generate tags for
        import_log: Optional ImportLog for database logging
        filename: Optional original filename to add as source-file tag
        skip_geocoding: If True, skip the GeocodingTagGenerator (for async processing)
        
    Returns:
        List of tag strings
    """
    # Call batch version with single feature for consistency
    result = generate_auto_tags_batch([feature], import_log, filename, skip_geocoding)
    return result[0] if result else []


# Initialize generators on module import
_discover_tag_generators()
