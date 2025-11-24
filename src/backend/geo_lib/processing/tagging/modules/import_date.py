"""
Import date tag generator.
Generates import-year:* and import-month:* tags based on current date.
"""
from datetime import datetime
from typing import List

from geo_lib.types.feature import GeoFeatureSupported
from geo_lib.processing.tagging.base import TagGenerator


class ImportDateTagGenerator(TagGenerator):
    """Generates import-year:* and import-month:* tags."""
    
    priority = 20  # Execute after geometry type
    
    def process(
        self,
        feature: GeoFeatureSupported,
        import_log=None,
        **kwargs
    ) -> List[str]:
        """
        Generate import date tags based on current date.
        
        Args:
            feature: The feature to generate tags for
            import_log: Optional ImportLog (not used here)
            **kwargs: Additional keyword arguments (not used)
            
        Returns:
            List containing import-year and import-month tags
        """
        now = datetime.now()
        return [
            f'import-year:{now.year}',
            f'import-month:{now.strftime("%B")}'
        ]

