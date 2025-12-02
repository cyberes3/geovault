"""
Elevation tag generator.
Generates high-elevation and low-elevation tags for points and linestrings.
"""
from typing import List, Optional

from geo_lib.types.feature import GeoFeatureSupported
from geo_lib.processing.tagging.base import TagGenerator

# Conversion factor: 1 meter = 3.28084 feet
METERS_TO_FEET = 3.28084

# Thresholds in feet
HIGH_ELEVATION_FEET = 8000
LOW_ELEVATION_FEET = 100
# Convert to meters for exact comparison
HIGH_ELEVATION_METERS = HIGH_ELEVATION_FEET / METERS_TO_FEET  # ~2438.4 meters
LOW_ELEVATION_METERS = LOW_ELEVATION_FEET / METERS_TO_FEET  # ~30.48 meters


class ElevationTagGenerator(TagGenerator):
    """Generates high-elevation and low-elevation tags for points and linestrings."""
    
    priority = 60  # Execute after source file, before geocoding
    
    def __init__(self):
        super().__init__('elevation')
    
    def _extract_elevations(self, feature: GeoFeatureSupported) -> List[float]:
        """
        Extract all elevation values from a feature's coordinates.
        
        Args:
            feature: The feature to extract elevations from
            
        Returns:
            List of elevation values in meters
        """
        elevations = []
        geometry = feature.geometry
        geometry_type = geometry.type.value.lower()
        
        if geometry_type == 'point':
            coords = geometry.coordinates
            # Point coordinates: [lon, lat] or [lon, lat, elevation]
            if len(coords) >= 3:
                elevation = coords[2]
                # Skip 0.0 values as they represent missing elevation data, not actual sea-level
                if elevation is not None and elevation != 0.0:
                    elevations.append(float(elevation))
        
        elif geometry_type == 'linestring':
            coords_list = geometry.coordinates
            for coord in coords_list:
                # LineString coordinates: [lon, lat] or [lon, lat, elevation]
                if len(coord) >= 3:
                    elevation = coord[2]
                    # Skip 0.0 values as they represent missing elevation data, not actual sea-level
                    if elevation is not None and elevation != 0.0:
                        elevations.append(float(elevation))
        
        elif geometry_type == 'multilinestring':
            coords_list = geometry.coordinates
            for line in coords_list:
                for coord in line:
                    # MultiLineString coordinates: [lon, lat] or [lon, lat, elevation]
                    if len(coord) >= 3:
                        elevation = coord[2]
                        # Skip 0.0 values as they represent missing elevation data, not actual sea-level
                        if elevation is not None and elevation != 0.0:
                            elevations.append(float(elevation))
        
        return elevations
    
    def process(
        self,
        feature: GeoFeatureSupported,
        import_log=None,
        **kwargs
    ) -> List[str]:
        """
        Generate elevation tags if feature has elevation data.
        
        Only processes points and linestrings (not polygons).
        
        Args:
            feature: The feature to generate tags for
            import_log: Optional ImportLog (not used here)
            **kwargs: Additional keyword arguments (not used)
            
        Returns:
            List containing elevation tags if applicable
        """
        tags = []
        
        geometry_type = feature.geometry.type.value.lower()
        
        # Only process points and linestrings
        if geometry_type not in ['point', 'multipoint', 'linestring', 'multilinestring']:
            return tags
        
        # Extract all elevation values
        elevations = self._extract_elevations(feature)
        
        if not elevations:
            return tags  # No elevation data available
        
        # Check for high elevation (>= 8000 feet)
        # Compare in meters for precision
        max_elevation_meters = max(elevations)
        if max_elevation_meters >= HIGH_ELEVATION_METERS:
            tags.append('elevation:high')
        
        # Check for low elevation (<= 100 feet)
        # Compare in meters for precision, with small epsilon for floating point comparison
        min_elevation_meters = min(elevations)
        if min_elevation_meters <= LOW_ELEVATION_METERS + 0.01:  # Small epsilon for floating point precision
            tags.append('elevation:low')
        
        return tags

