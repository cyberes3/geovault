"""
Geometry type tag generator.
Generates type:* tags based on feature geometry type.
"""
from typing import List

from geo_lib.processing.tagging.base import TagGenerator
from geo_lib.types.feature import GeoFeatureSupported


class GeometryTypeTagGenerator(TagGenerator):
    """Generates type:* tags for features."""

    priority = 10  # Execute early

    def __init__(self):
        super().__init__('type')

    def process(
            self,
            feature: GeoFeatureSupported,
            import_log=None,
            **kwargs
    ) -> List[str]:
        """
        Generate type tag based on geometry type.
        Uses simplified user-friendly names instead of technical GeoJSON types.
        
        Args:
            feature: The feature to generate tags for
            import_log: Optional ImportLog (not used here)
            **kwargs: Additional keyword arguments (not used)
            
        Returns:
            List containing a single type tag
        """
        geometry_type = feature.geometry.type.value.lower()

        # Map technical GeoJSON types to user-friendly names
        type_mapping = {
            'point': 'point',
            'multipoint': 'point',
            'linestring': 'line',
            'multilinestring': 'line',
            'polygon': 'polygon',
            'multipolygon': 'polygon'
        }

        tag_type = type_mapping.get(geometry_type, geometry_type)
        return [f'type:{tag_type}']
