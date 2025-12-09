"""
Reverse geocoding tag generator.
Generates location-based tags (city, state, country, protected areas, lakes, etc.)
using reverse geocoding.
"""
from typing import List, Tuple

from website.settings_utils import get_required_setting

from geo_lib.types.feature import GeoFeatureSupported
from geo_lib.geocoding.reverse_geocode import get_reverse_geocoding_service
from geo_lib.processing.tagging.base import TagGenerator
from geo_lib.processing.logging import DatabaseLogLevel
from geo_lib.logging.console import get_job_logger

logger = get_job_logger()


def get_representative_points(feature: GeoFeatureSupported) -> List[Tuple[float, float]]:
    """
    Get representative points from a feature for reverse geocoding.
    For points: returns the point itself
    For lines: returns only the middle point
    For polygons: returns empty list (not reverse geocoded)
    
    Returns:
        List of (latitude, longitude) tuples
    """
    points = []
    geometry = feature.geometry
    
    if geometry.type.value.lower() == 'point':
        coords = geometry.coordinates
        # GeoJSON coordinates are [longitude, latitude] or [longitude, latitude, elevation]
        points.append((coords[1], coords[0]))  # (lat, lon)
    
    elif geometry.type.value.lower() in ['linestring', 'multilinestring']:
        # For linestrings, use only the middle point
        if geometry.type.value.lower() == 'linestring':
            coords_list = geometry.coordinates
        else:  # multilinestring
            # Use the first linestring
            coords_list = geometry.coordinates[0] if geometry.coordinates else []
        
        if coords_list:
            # Middle point only
            mid_idx = len(coords_list) // 2
            mid_coords = coords_list[mid_idx]
            points.append((mid_coords[1], mid_coords[0]))  # (lat, lon)
    
    # Polygons are not reverse geocoded (as per user's requirement)
    
    return points


class GeocodingTagGenerator(TagGenerator):
    """Generates location-based tags using reverse geocoding."""
    
    priority = 100  # Execute last (geocoding can be slow)
    
    def __init__(self):
        super().__init__('geocoding')
    
    def process(
        self,
        feature: GeoFeatureSupported,
        import_log=None,
        **kwargs
    ) -> List[str]:
        """
        Generate reverse geocoding tags for points and lines only.
        
        Args:
            feature: The feature to generate tags for
            import_log: Optional ImportLog for database logging
            **kwargs: Additional keyword arguments (not used)
            
        Returns:
            List of reverse geocoding tags
        """
        tags = []
        
        # Add reverse geocoding tags for points and lines only
        geometry_type = feature.geometry.type.value.lower()
        if geometry_type in ['point', 'multipoint', 'linestring', 'multilinestring']:
            # Check if reverse geocoding is enabled before attempting to reverse geocode
            if get_required_setting('REVERSE_GEOCODING_ENABLED'):
                try:
                    points = get_representative_points(feature)
                    if points:
                        geocoding_service = get_reverse_geocoding_service()
                        all_location_tags = set()
                        
                        for lat, lon in points:
                            try:
                                location_tags, log_messages = geocoding_service.get_location_tags(lat, lon)
                                all_location_tags.update(location_tags)
                                
                                # Add log messages to import log
                                if import_log and log_messages:
                                    for log_msg in log_messages:
                                        # Map level string to DatabaseLogLevel
                                        if log_msg.level == 'ERROR':
                                            level = DatabaseLogLevel.ERROR
                                        elif log_msg.level == 'WARNING':
                                            level = DatabaseLogLevel.WARNING
                                        else:
                                            level = DatabaseLogLevel.INFO
                                        
                                        import_log.add(
                                            log_msg.message,
                                            log_msg.source,
                                            level
                                        )
                            except Exception as geocode_point_error:
                                error_msg = f"Reverse geocoding failed at coordinates ({lat}, {lon}): {str(geocode_point_error)}"
                                logger.warning(error_msg)
                                if import_log:
                                    import_log.add(
                                        error_msg,
                                        "Reverse Geocoding",
                                        DatabaseLogLevel.WARNING
                                    )
                        
                        tags.extend(sorted(all_location_tags))
                except Exception as e:
                    logger.warning(f"Failed to reverse geocode feature for tagging: {e}")
                    if import_log:
                        import_log.add(
                            f"Reverse geocoding failed: {str(e)}",
                            "Reverse Geocoding",
                            DatabaseLogLevel.WARNING
                        )
        
        return tags

