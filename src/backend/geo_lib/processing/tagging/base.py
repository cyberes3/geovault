"""
Base class for tag generator modules.
"""
from abc import ABC, abstractmethod
from typing import List, Optional, Any

from geo_lib.types.feature import GeoFeatureSupported


class TagGenerator(ABC):
    """
    Abstract base class for tag generator modules.
    
    Each tag generator module should inherit from this class and implement
    the process() method to generate tags for a feature.
    """
    
    # Priority for execution order (lower numbers execute first)
    # Default priority is 100, adjust as needed
    priority: int = 100
    
    @abstractmethod
    def process(
        self,
        feature: GeoFeatureSupported,
        import_log=None,
        **kwargs
    ) -> List[str]:
        """
        Process a feature and return a list of tags.
        
        Args:
            feature: The feature to generate tags for
            import_log: Optional ImportLog for database logging
            **kwargs: Additional keyword arguments (e.g., filename)
            
        Returns:
            List of tag strings
        """
        pass

