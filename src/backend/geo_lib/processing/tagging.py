import os
from datetime import datetime
from typing import List, Optional, Tuple

from django.conf import settings

from geo_lib.types.feature import GeoFeatureSupported
from geo_lib.geolocation.reverse_geocode import get_reverse_geocoding_service
from geo_lib.processing.logging import DatabaseLogLevel
from geo_lib.logging.console import get_import_logger

logger = get_import_logger()


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


def generate_auto_tags(feature: GeoFeatureSupported, import_log=None, filename: Optional[str] = None) -> List[str]:
    """
    Generate automatic tags for a feature including geocoding tags.
    
    Args:
        feature: The feature to generate tags for
        import_log: Optional ImportLog for database logging
        filename: Optional original filename to add as source-file tag
        
    Returns:
        List of tag strings
    """
    tags = [
        f'type:{feature.geometry.type.value.lower()}'
    ]

    now = datetime.now()
    tags.append(f'import-year:{now.year}')
    tags.append(f'import-month:{now.strftime("%B")}')
    
    # Add feature-year and feature-month tags if created date exists
    if feature.properties.created:
        try:
            created_date = feature.properties.created
            if isinstance(created_date, datetime):
                tags.append(f'feature-year:{created_date.year}')
                tags.append(f'feature-month:{created_date.strftime("%B")}')
            elif isinstance(created_date, str):
                # Parse ISO format string
                parsed_date = datetime.fromisoformat(created_date.replace('Z', '+00:00'))
                tags.append(f'feature-year:{parsed_date.year}')
                tags.append(f'feature-month:{parsed_date.strftime("%B")}')
        except (ValueError, AttributeError) as e:
            logger.warning(f"Failed to parse created date for feature-year/feature-month tags: {e}")
    
    # Add is-track:yes tag for LineString/MultiLineString that are tracks or routes
    # GPX tracks have coordinateProperties.times, GPX routes have time property
    geometry_type = feature.geometry.type.value.lower()
    if geometry_type in ['linestring', 'multilinestring']:
        props_dict = feature.properties.model_dump()
        
        # Check for GPX track (has coordinateProperties.times)
        coordinate_properties = props_dict.get('coordinateProperties', {})
        if coordinate_properties and isinstance(coordinate_properties, dict):
            times = coordinate_properties.get('times')
            if times:
                tags.append('is-track:yes')
        # Check for GPX route (has time property)
        elif props_dict.get('time'):
            tags.append('is-track:yes')
    
    # Add source-file tag if filename is provided
    if filename:
        # Extract just the filename (not full path) if needed
        basename = os.path.basename(filename)
        tags.append(f'source-file:{basename}')
    
    # Add geocoding tags for points and lines only
    geometry_type = feature.geometry.type.value.lower()
    if geometry_type in ['point', 'multipoint', 'linestring', 'multilinestring']:
        # Check if geocoding is enabled before attempting to geocode
        if getattr(settings, 'REVERSE_GEOCODING_ENABLED', True):
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
    
    return [str(x) for x in tags]


def update_feature_date_tags(system_tags: List[str], created_date: Optional[str]) -> List[str]:
    """
    Update feature-year and feature-month system tags based on created date.
    Removes existing feature-year and feature-month tags and adds new ones if created_date is provided.
    
    Args:
        system_tags: Current list of system tags
        created_date: ISO format datetime string or None
        
    Returns:
        Updated list of system tags
    """
    if not isinstance(system_tags, list):
        system_tags = []
    
    # Remove existing feature-year and feature-month tags
    updated_tags = [tag for tag in system_tags if not (tag.startswith('feature-year:') or tag.startswith('feature-month:'))]
    
    # Add new feature-year and feature-month tags if created_date exists
    if created_date:
        try:
            # Parse ISO format string
            if isinstance(created_date, str):
                parsed_date = datetime.fromisoformat(created_date.replace('Z', '+00:00'))
                updated_tags.append(f'feature-year:{parsed_date.year}')
                updated_tags.append(f'feature-month:{parsed_date.strftime("%B")}')
            elif isinstance(created_date, datetime):
                updated_tags.append(f'feature-year:{created_date.year}')
                updated_tags.append(f'feature-month:{created_date.strftime("%B")}')
        except (ValueError, AttributeError) as e:
            logger.warning(f"Failed to parse created date for feature-year/feature-month tags: {e}")
    
    return updated_tags
