"""
Base class for tag generator modules.
"""
from abc import ABC, abstractmethod
from typing import List, Union

from geo_lib.types.feature import GeoFeatureSupported


class TagGenerator(ABC):
    """
    Abstract base class for tag generator modules.
    
    Each tag generator module should inherit from this class and implement
    the process() method to generate tags for a feature.
    
    Tag generators must declare their tag name(s) during initialization,
    which are used to automatically build the list of system/internal tags.
    """

    # Priority for execution order (lower numbers execute first)
    # Default priority is 100, adjust as needed
    priority: int = 100

    def __init__(self, tag_name: Union[str, List[str]]):
        """
        Initialize the tag generator with its tag name(s).
        
        Args:
            tag_name: The tag prefix(es) this generator produces (e.g., 'type', 
                     'elevation', or ['import-year', 'import-month'] for multiple).
                     These are the prefixes used in tags like 'type:point', 
                     'elevation:high', etc.
        """
        if isinstance(tag_name, str):
            self.tag_names = [tag_name]
        else:
            self.tag_names = list(tag_name)

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
