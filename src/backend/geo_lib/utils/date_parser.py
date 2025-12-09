"""
Date parsing utility using dateparser for flexible date format support.
"""

from datetime import datetime, timezone
from typing import Optional, Any

import dateparser


def parse_date_string(date_string: str) -> Optional[datetime]:
    """
    Parse a date string using dateparser.
    
    Args:
        date_string: Date string in any format
        
    Returns:
        datetime object with timezone info, or None if parsing fails
    """
    if not date_string or not isinstance(date_string, str):
        return None

    parsed = dateparser.parse(date_string)
    if parsed:
        # Ensure timezone info is present (default to UTC if missing)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return parsed

    return None


def parse_date_field(value: Any) -> Optional[datetime]:
    """
    Parse a date field that may be a datetime object, string, or None.
    
    Args:
        value: datetime object, date string, or None
        
    Returns:
        datetime object with timezone info, or None
    """
    if value is None:
        return None

    if isinstance(value, datetime):
        # Ensure timezone info is present
        if value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value

    if isinstance(value, str):
        return parse_date_string(value)

    return None
