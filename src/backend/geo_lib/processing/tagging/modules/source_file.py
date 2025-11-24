"""
Source file tag generator.
Generates source-file:* tags from filename.
"""
import os
from typing import List, Optional

from geo_lib.types.feature import GeoFeatureSupported
from geo_lib.processing.tagging.base import TagGenerator


class SourceFileTagGenerator(TagGenerator):
    """Generates source-file:* tags from filename."""
    
    priority = 50  # Execute after track detection
    
    def process(
        self,
        feature: GeoFeatureSupported,
        import_log=None,
        filename: Optional[str] = None,
        **kwargs
    ) -> List[str]:
        """
        Generate source-file tag if filename is provided.
        
        Args:
            feature: The feature to generate tags for
            import_log: Optional ImportLog (not used here)
            filename: Optional original filename to add as source-file tag
            **kwargs: Additional keyword arguments
            
        Returns:
            List containing source-file tag if filename provided, empty list otherwise
        """
        tags = []
        
        if filename:
            # Extract just the filename (not full path) if needed
            basename = os.path.basename(filename)
            tags.append(f'source-file:{basename}')
        
        return tags

