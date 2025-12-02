"""
Track detection tag generator.
Detects GPX tracks/routes and generates track:yes tag.
"""
from typing import List

from geo_lib.types.feature import GeoFeatureSupported
from geo_lib.processing.tagging.base import TagGenerator


class TrackDetectionTagGenerator(TagGenerator):
    """Detects GPX tracks/routes and generates track:yes tag."""
    
    priority = 40  # Execute after feature date
    
    def process(
        self,
        feature: GeoFeatureSupported,
        import_log=None,
        **kwargs
    ) -> List[str]:
        """
        Detect if feature is a GPX track or route and generate track:yes tag.
        
        GPX tracks have coordinateProperties.times, GPX routes have time property.
        
        Args:
            feature: The feature to generate tags for
            import_log: Optional ImportLog (not used here)
            **kwargs: Additional keyword arguments (not used)
            
        Returns:
            List containing track:yes tag if detected, empty list otherwise
        """
        tags = []
        
        geometry_type = feature.geometry.type.value.lower()
        if geometry_type in ['linestring', 'multilinestring']:
            props_dict = feature.properties.model_dump()
            
            # Check for GPX track (has coordinateProperties.times)
            coordinate_properties = props_dict.get('coordinateProperties', {})
            if coordinate_properties and isinstance(coordinate_properties, dict):
                times = coordinate_properties.get('times')
                if times:
                    tags.append('track:yes')
            # Check for GPX route (has time property)
            elif props_dict.get('time'):
                tags.append('track:yes')
        
        return tags

