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
    
    Treats 1970-01-01 00:00:00 UTC (Unix epoch) as None (no date provided).
    
    Args:
        value: datetime object, date string, or None
        
    Returns:
        datetime object with timezone info, or None
    """
    if value is None:
        return None

    # Unix epoch timestamp (1970-01-01 00:00:00 UTC)
    EPOCH = datetime(1970, 1, 1, 0, 0, 0, tzinfo=timezone.utc)

    def is_epoch_date(dt: datetime) -> bool:
        """Check if datetime represents Unix epoch (1970-01-01 00:00:00 UTC)."""
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        else:
            dt = dt.astimezone(timezone.utc)
        # Compare date components (year, month, day, hour, minute, second)
        return (dt.year == 1970 and dt.month == 1 and dt.day == 1 and
                dt.hour == 0 and dt.minute == 0 and dt.second == 0 and dt.microsecond == 0)

    if isinstance(value, datetime):
        # Ensure timezone info is present
        if value.tzinfo is None:
            dt = value.replace(tzinfo=timezone.utc)
        else:
            dt = value
        
        # Check if this is the Unix epoch (treat as no date provided)
        if is_epoch_date(dt):
            return None
        
        return dt

    if isinstance(value, str):
        parsed = parse_date_string(value)
        if parsed and is_epoch_date(parsed):
            return None
        return parsed

    return None
