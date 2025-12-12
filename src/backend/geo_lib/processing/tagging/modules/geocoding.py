"""
Reverse geocoding tag generator.
Generates location-based tags (city, state, country, protected areas, lakes, etc.)
using reverse geocoding.
"""
from typing import List, Tuple, Dict

from website.settings_utils import get_required_setting

from geo_lib.types.feature import GeoFeatureSupported
from geo_lib.geocoding.constants import REVERSE_GEOCODING_TAG_PREFIXES
from geo_lib.geocoding.location_tags import batch_reverse_geocode_coordinates
from geo_lib.processing.tagging.base import TagGenerator
from geo_lib.processing.logging import DatabaseLogLevel
from geo_lib.logging.console import get_tagged_logger

logger = get_tagged_logger()


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


class ReverseGeocodingTagGenerator(TagGenerator):
    """Generates location-based tags using reverse geocoding."""
    
    priority = 100  # Execute last (reverse geocoding can be slow)
    
    def __init__(self):
        # Register all reverse geocoding tag prefixes that this generator produces
        # Use the centralized constant to ensure consistency
        super().__init__(REVERSE_GEOCODING_TAG_PREFIXES)
    
    def process_batch(
        self,
        features: List[GeoFeatureSupported],
        import_log=None,
        **kwargs
    ) -> Dict[int, List[str]]:
        """
        Process multiple features at once with coordinate deduplication.
        This is the preferred method for processing multiple features efficiently.
        
        Args:
            features: List of features to geocode
            import_log: Optional ImportLog for database logging
            
        Returns:
            Dict mapping feature index to list of tags
        """
        if not get_required_setting('REVERSE_GEOCODING_ENABLED'):
            return {i: [] for i in range(len(features))}
        
        # Step 1: Extract all coordinates from all features
        feature_coords = {}  # Maps feature index -> list of coordinates
        all_coordinates = []
        
        for i, feature in enumerate(features):
            geometry_type = feature.geometry.type.value.lower()
            if geometry_type in ['point', 'multipoint', 'linestring', 'multilinestring']:
                points = get_representative_points(feature)
                if points:
                    feature_coords[i] = points
                    all_coordinates.extend(points)
        
        if not all_coordinates:
            return {i: [] for i in range(len(features))}
        
        # Step 2: SINGLE CALL to batch reverse geocode all coordinates with deduplication
        reverse_geocode_results = batch_reverse_geocode_coordinates(all_coordinates)
        
        # Step 3: Assign tags back to features
        feature_tags = {}
        for i, coords in feature_coords.items():
            all_location_tags = set()
            
            for lat, lon in coords:
                tags, log_messages = reverse_geocode_results.get((lat, lon), ([], []))
                all_location_tags.update(tags)
                
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
            
            feature_tags[i] = sorted(all_location_tags)
        
        # Return empty list for features that weren't geocoded
        return {i: feature_tags.get(i, []) for i in range(len(features))}
    
    def process(
        self,
        feature: GeoFeatureSupported,
        import_log=None,
        **kwargs
    ) -> List[str]:
        """
        Process single feature (backwards compatibility).
        Prefer using process_batch() for better performance when processing multiple features.
        
        Args:
            feature: The feature to generate tags for
            import_log: Optional ImportLog for database logging
            **kwargs: Additional keyword arguments (not used)
            
        Returns:
            List of reverse geocoding tags
        """
        # Call batch version with single feature
        result = self.process_batch([feature], import_log, **kwargs)
        return result.get(0, [])

