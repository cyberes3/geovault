"""
Utility functions for accessing Django settings with validation.
"""

from typing import Any

from django.conf import settings
from django.core.exceptions import ImproperlyConfigured


def get_required_setting(attr_name: str) -> Any:
    """
    Get a required setting from Django settings.
    
    Use this instead of getattr() with default values when a setting must be present.
    
    Args:
        attr_name: Name of the setting attribute
        
    Returns:
        The setting value (guaranteed to be not None)
        
    Raises:
        ImproperlyConfigured: If the setting is None or not found
    """
    value = getattr(settings, attr_name, None)
    if value is None:
        raise ImproperlyConfigured(f"Required setting '{attr_name}' is None or not set")
    return value


def get_setting(attr_name: str, default: Any = None) -> Any:
    """
    Get an optional setting from Django settings.
    Use this instead of getattr(settings, attr_name, default).
    """
    return getattr(settings, attr_name, default)
