from datetime import datetime
from typing import List, Optional, Tuple
import logging

from geo_lib.types.feature import GeoFeatureSupported
from geo_lib.geolocation.reverse_geocode import get_reverse_geocoding_service
from geo_lib.processing.logging import DatabaseLogLevel

logger = logging.getLogger(__name__)


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


def generate_auto_tags(feature: GeoFeatureSupported, import_log=None) -> List[str]:
    """
    Generate automatic tags for a feature including geocoding tags.
    
    Args:
        feature: The feature to generate tags for
        import_log: Optional ImportLog for database logging
        
    Returns:
        List of tag strings
    """
    tags = [
        f'type:{feature.geometry.type.value.lower()}'
    ]

    now = datetime.now()
    tags.append(f'import-year:{now.year}')
    tags.append(f'import-month:{now.strftime("%B")}')
    
    # Add geocoding tags for points and lines only
    geometry_type = feature.geometry.type.value.lower()
    if geometry_type in ['point', 'multipoint', 'linestring', 'multilinestring']:
        try:
            points = get_representative_points(feature)
            if points:
                geocoding_service = get_reverse_geocoding_service()
                all_location_tags = set()
                
                for lat, lon in points:
                    location_tags = geocoding_service.get_location_tags(lat, lon)
                    all_location_tags.update(location_tags)
                
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
    
    return [str(x) for x in tags]
