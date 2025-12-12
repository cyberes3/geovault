"""
Tagging system with modular tag generators.

This module provides a plugin-based architecture for generating tags.
Individual tag generator modules can be added to the modules/ directory
and will be automatically discovered and executed.
"""
from typing import List

from geo_lib.processing.tagging.base import TagGenerator
from geo_lib.processing.tagging.modules.driving_detection import DrivingDetectionTagGenerator
from geo_lib.processing.tagging.modules.elevation import ElevationTagGenerator
from geo_lib.processing.tagging.modules.feature_date import FeatureDateTagGenerator, update_feature_date_tags
from geo_lib.processing.tagging.modules.geocoding import ReverseGeocodingTagGenerator
from geo_lib.processing.tagging.modules.geometry_type import GeometryTypeTagGenerator
from geo_lib.processing.tagging.modules.import_date import ImportDateTagGenerator
from geo_lib.processing.tagging.modules.source_file import SourceFileTagGenerator
from geo_lib.processing.tagging.modules.track_detection import TrackDetectionTagGenerator

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
        ReverseGeocodingTagGenerator(),
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


# Initialize generators on module import
_discover_tag_generators()
