"""
Helper functions for feature export functionality.
"""

from typing import Optional


def parse_feature_id(raw_id: Optional[str]) -> Optional[int]:
    """
    Parse and validate feature id from query parameter.

    Args:
        raw_id: Raw feature ID string from query parameter

    Returns:
        A positive int or None if invalid.
    """
    if raw_id is None:
        return None
    try:
        feature_id = int(raw_id)
        if feature_id <= 0:
            return None
        return feature_id
    except (TypeError, ValueError):
        return None
