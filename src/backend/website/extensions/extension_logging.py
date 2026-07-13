"""
Extension logging filter registration system for GeoVault extensions.

This module provides a way for extensions to register logging filters that will
be applied to Django's logging handlers. Filters are registered during extension
initialization and applied to all handlers of the root logger.

Example usage:
    from website.extensions.extension_logging import register_logging_filter
    from my_extension.logging_filters import MyFilter
    
    # In extension_ready() method:
    register_logging_filter(MyFilter())
"""
import logging
from typing import List

logger = logging.getLogger('website.extension_logging')

# Registry of filters registered by extensions
_registered_filters: List[logging.Filter] = []


def register_logging_filter(filter_instance: logging.Filter) -> None:
    """
    Register a logging filter to be applied to all root logger handlers.
    
    This function should be called during extension initialization (in extension_ready()).
    The filter will be added to all existing handlers of the root logger, and will
    also be added to any handlers created after registration.
    
    Args:
        filter_instance: An instance of a logging.Filter subclass
    """
    if not isinstance(filter_instance, logging.Filter):
        raise TypeError("filter_instance must be an instance of logging.Filter")
    
    root_logger = logging.getLogger()
    
    # Add filter to all existing handlers
    for handler in root_logger.handlers:
        handler.addFilter(filter_instance)
    
    # Also add filter to the root logger itself (catches messages before handlers)
    root_logger.addFilter(filter_instance)
    
    # Store the filter so we can add it to new handlers later
    _registered_filters.append(filter_instance)
    
    logger.debug(f"Registered logging filter: {filter_instance.__class__.__name__}")
