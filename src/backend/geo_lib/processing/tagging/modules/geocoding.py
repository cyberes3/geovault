"""
Geocoding tag generator.
Generates location-based tags (city, state, country, protected areas, lakes, etc.)
"""
from typing import List, Tuple, Optional

from django.conf import settings
from website.settings_utils import get_required_setting

from geo_lib.types.feature import GeoFeatureSupported
from geo_lib.geolocation.reverse_geocode import get_reverse_geocoding_service
from geo_lib.processing.tagging.base import TagGenerator
from geo_lib.processing.logging import DatabaseLogLevel
from geo_lib.logging.console import get_job_logger

logger = get_job_logger()


def get_representative_points(feature: GeoFeatureSupported) -> List[Tuple[float, float]]:
    """
    Get representative points from a feature for geocoding.
    For points: returns the point itself
    For lines: returns start, middle, and end points
    For polygons: returns empty list (not geocoded)
    
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
        # For linestrings, use start, middle, and end points
        if geometry.type.value.lower() == 'linestring':
            coords_list = geometry.coordinates
        else:  # multilinestring
            # Use the first linestring
            coords_list = geometry.coordinates[0] if geometry.coordinates else []
        
        if coords_list:
            # Start point
            start_coords = coords_list[0]
            points.append((start_coords[1], start_coords[0]))  # (lat, lon)
            
            # Middle point
            if len(coords_list) > 2:
                mid_idx = len(coords_list) // 2
                mid_coords = coords_list[mid_idx]
                points.append((mid_coords[1], mid_coords[0]))  # (lat, lon)
            
            # End point
            if len(coords_list) > 1:
                end_coords = coords_list[-1]
                points.append((end_coords[1], end_coords[0]))  # (lat, lon)
    
    # Polygons are not geocoded (as per user's requirement)
    
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
        Generate geocoding tags for points and lines only.
        
        Args:
            feature: The feature to generate tags for
            import_log: Optional ImportLog for database logging
            **kwargs: Additional keyword arguments (not used)
            
        Returns:
            List of geocoding tags
        """
        tags = []
        
        # Add geocoding tags for points and lines only
        geometry_type = feature.geometry.type.value.lower()
        if geometry_type in ['point', 'multipoint', 'linestring', 'multilinestring']:
            # Check if geocoding is enabled before attempting to geocode
            if get_required_setting('REVERSE_GEOCODING_ENABLED'):
                try:
                    points = get_representative_points(feature)
                    if points:
                        geocoding_service = get_reverse_geocoding_service()
                        all_location_tags = set()
                        
                        for lat, lon in points:
                            try:
                                location_tags = geocoding_service.get_location_tags(lat, lon, import_log)
                                all_location_tags.update(location_tags)
                            except Exception as geocode_point_error:
                                error_msg = f"Geocoding failed at coordinates ({lat}, {lon}): {str(geocode_point_error)}"
                                logger.warning(error_msg)
                                if import_log:
                                    import_log.add(
                                        error_msg,
                                        "Geocoding",
                                        DatabaseLogLevel.WARNING
                                    )
                        
                        tags.extend(sorted(all_location_tags))
                        
                        if import_log:
                            tag_count = len(all_location_tags)
                            if tag_count > 0:
                                import_log.add(
                                    f"Added {tag_count} geocoding tag(s) to feature",
                                    "Geocoding",
                                    DatabaseLogLevel.INFO
                                )
                except Exception as e:
                    logger.warning(f"Failed to geocode feature for tagging: {e}")
                    if import_log:
                        import_log.add(
                            f"Geocoding failed: {str(e)}",
                            "Geocoding",
                            DatabaseLogLevel.WARNING
                        )
        
        return tags

