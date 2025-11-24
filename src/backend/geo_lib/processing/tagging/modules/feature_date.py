"""
Feature date tag generator.
Generates feature-year:* and feature-month:* tags based on feature created date.
Also provides update_feature_date_tags() helper function.
"""
from datetime import datetime
from typing import List, Optional

from geo_lib.types.feature import GeoFeatureSupported
from geo_lib.processing.tagging.base import TagGenerator
from geo_lib.logging.console import get_import_logger

logger = get_import_logger()


class FeatureDateTagGenerator(TagGenerator):
    """Generates feature-year:* and feature-month:* tags from feature created date."""
    
    priority = 30  # Execute after import date
    
    def process(
        self,
        feature: GeoFeatureSupported,
        import_log=None,
        **kwargs
    ) -> List[str]:
        """
        Generate feature date tags if created date exists.
        
        Args:
            feature: The feature to generate tags for
            import_log: Optional ImportLog (not used here)
            **kwargs: Additional keyword arguments (not used)
            
        Returns:
            List containing feature-year and feature-month tags if created date exists
        """
        tags = []
        
        if feature.properties.created:
            try:
                created_date = feature.properties.created
                if isinstance(created_date, datetime):
                    tags.append(f'feature-year:{created_date.year}')
                    tags.append(f'feature-month:{created_date.strftime("%B")}')
                elif isinstance(created_date, str):
                    # Parse ISO format string
                    parsed_date = datetime.fromisoformat(created_date.replace('Z', '+00:00'))
                    tags.append(f'feature-year:{parsed_date.year}')
                    tags.append(f'feature-month:{parsed_date.strftime("%B")}')
            except (ValueError, AttributeError) as e:
                logger.warning(f"Failed to parse created date for feature-year/feature-month tags: {e}")
        
        return tags


def update_feature_date_tags(system_tags: List[str], created_date: Optional[str]) -> List[str]:
    """
    Update feature-year and feature-month system tags based on created date.
    Removes existing feature-year and feature-month tags and adds new ones if created_date is provided.
    
    Args:
        system_tags: Current list of system tags
        created_date: ISO format datetime string or None
        
    Returns:
        Updated list of system tags
    """
    if not isinstance(system_tags, list):
        system_tags = []
    
    # Remove existing feature-year and feature-month tags
    updated_tags = [tag for tag in system_tags if not (tag.startswith('feature-year:') or tag.startswith('feature-month:'))]
    
    # Add new feature-year and feature-month tags if created_date exists
    if created_date:
        try:
            # Parse ISO format string
            if isinstance(created_date, str):
                parsed_date = datetime.fromisoformat(created_date.replace('Z', '+00:00'))
                updated_tags.append(f'feature-year:{parsed_date.year}')
                updated_tags.append(f'feature-month:{parsed_date.strftime("%B")}')
            elif isinstance(created_date, datetime):
                updated_tags.append(f'feature-year:{created_date.year}')
                updated_tags.append(f'feature-month:{created_date.strftime("%B")}')
        except (ValueError, AttributeError) as e:
            logger.warning(f"Failed to parse created date for feature-year/feature-month tags: {e}")
    
    return updated_tags

