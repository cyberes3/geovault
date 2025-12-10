"""
Track detection tag generator.
Detects GPX tracks/routes and generates type:track tag.
"""
from typing import List

from geo_lib.processing.tagging.base import TagGenerator
from geo_lib.types.feature import GeoFeatureSupported


class TrackDetectionTagGenerator(TagGenerator):
    """Detects GPX tracks/routes and generates type:track tag."""

    priority = 40  # Execute after feature date

    def __init__(self):
        super().__init__('track')

    def process(
            self,
            feature: GeoFeatureSupported,
            import_log=None,
            **kwargs
    ) -> List[str]:
        """
        Detect if feature is a GPX track or route and generate type:track tag.
        
        GPX tracks have coordinateProperties.times, GPX routes have time property.
        This overrides the generic type:line tag with the more specific type:track.
        
        Args:
            feature: The feature to generate tags for
            import_log: Optional ImportLog (not used here)
            **kwargs: Additional keyword arguments (not used)
            
        Returns:
            List containing type:track tag if detected, empty list otherwise
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
                    tags.append('type:track')
            # Check for GPX route (has time property)
            elif props_dict.get('time'):
                tags.append('type:track')

        return tags
