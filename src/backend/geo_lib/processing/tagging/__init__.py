"""
Tagging system with modular tag generators.

This module provides a plugin-based architecture for generating tags.
Individual tag generator modules can be added to the modules/ directory
and will be automatically discovered and executed.
"""
from typing import List, Optional

from geo_lib.types.feature import GeoFeatureSupported
from geo_lib.processing.tagging.base import TagGenerator

# Import all tag generator classes
from geo_lib.processing.tagging.modules.geometry_type import GeometryTypeTagGenerator
from geo_lib.processing.tagging.modules.import_date import ImportDateTagGenerator
from geo_lib.processing.tagging.modules.feature_date import FeatureDateTagGenerator, update_feature_date_tags
from geo_lib.processing.tagging.modules.track_detection import TrackDetectionTagGenerator
from geo_lib.processing.tagging.modules.source_file import SourceFileTagGenerator
from geo_lib.processing.tagging.modules.elevation import ElevationTagGenerator
from geo_lib.processing.tagging.modules.geocoding import GeocodingTagGenerator

# Export update_feature_date_tags for backward compatibility
__all__ = ['generate_auto_tags', 'update_feature_date_tags']

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
        SourceFileTagGenerator(),
        ElevationTagGenerator(),
        GeocodingTagGenerator(),
    ]
    
    _tag_generators.extend(generators)
    
    # Sort by priority (lower priority numbers execute first)
    _tag_generators.sort(key=lambda g: g.priority)


def generate_auto_tags(
    feature: GeoFeatureSupported,
    import_log=None,
    filename: Optional[str] = None
) -> List[str]:
    """
    Generate automatic tags for a feature using all registered tag generators.
    
    This function maintains backward compatibility with the original interface.
    It discovers and executes all tag generator modules in priority order.
    
    Args:
        feature: The feature to generate tags for
        import_log: Optional ImportLog for database logging
        filename: Optional original filename to add as source-file tag
        
    Returns:
        List of tag strings
    """
    # Discover generators if not already done
    if not _tag_generators:
        _discover_tag_generators()
    
    all_tags = []
    
    # Execute all generators in priority order
    for generator in _tag_generators:
        try:
            tags = generator.process(feature, import_log=import_log, filename=filename)
            if tags:
                all_tags.extend(tags)
        except Exception as e:
            # Log error but continue with other generators
            from geo_lib.logging.console import get_import_logger
            logger = get_import_logger()
            logger.warning(f"Tag generator {generator.__class__.__name__} failed: {e}")
            if import_log:
                from geo_lib.processing.logging import DatabaseLogLevel
                import_log.add(
                    f"Tag generator {generator.__class__.__name__} failed: {str(e)}",
                    "Tagging",
                    DatabaseLogLevel.WARNING
                )
    
    # Ensure all tags are strings
    return [str(tag) for tag in all_tags]


# Initialize generators on module import
_discover_tag_generators()

