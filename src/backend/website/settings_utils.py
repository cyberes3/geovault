"""
Utility functions for accessing Django settings with validation.
"""

from typing import Any

from django.conf import settings


def get_required_setting(attr_name: str) -> Any:
    """
    Get a required setting from Django settings.
    
    This function retrieves a setting value and asserts that it is not None.
    Use this instead of getattr() with default values when a setting must be present.
    
    Args:
        attr_name: Name of the setting attribute
        
    Returns:
        The setting value (guaranteed to be not None)
        
    Raises:
        AssertionError: If the setting is None or not found
    """
    value = getattr(settings, attr_name, None)
    assert value is not None, f"Required setting '{attr_name}' is None or not set"
    return value

