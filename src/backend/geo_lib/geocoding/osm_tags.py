"""
Utilities for working with OpenStreetMap tags.
"""
from typing import Optional, Dict, Any


def get_name_from_tags(tags: Dict[str, Any]) -> Optional[str]:
    """
    Get name from OSM tags, preferring English names for consistency.

    With Unicode database support, we can accept names in any language,
    but prefer English when available for better user experience.

    Args:
        tags: OSM element tags dictionary

    Returns:
        Name string or None
    """
    # Prefer English name for consistency across international locations
    name = tags.get('name:en')
    if name:
        return name

    # Fall back to default name (may be in local language)
    name = tags.get('name')
    return name
