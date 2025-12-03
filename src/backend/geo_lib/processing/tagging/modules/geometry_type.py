"""
Geometry type tag generator.
Generates type:* tags based on feature geometry type.
"""
from typing import List

from geo_lib.types.feature import GeoFeatureSupported
from geo_lib.processing.tagging.base import TagGenerator


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
        
        Args:
            feature: The feature to generate tags for
            import_log: Optional ImportLog (not used here)
            **kwargs: Additional keyword arguments (not used)
            
        Returns:
            List containing a single type tag
        """
        geometry_type = feature.geometry.type.value.lower()
        # Map linestring to line for tag
        tag_type = "line" if geometry_type == "linestring" else geometry_type
        return [f'type:{tag_type}']

