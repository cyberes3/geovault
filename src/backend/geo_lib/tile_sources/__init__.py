"""
Tile sources module registry.

This module provides a registry system for tile sources, allowing dynamic
registration and retrieval of tile source configurations.
"""

# Registry to store all tile sources
_tile_sources = {}


def register_tile_source(source_id, config):
    """
    Register a tile source configuration.
    
    Args:
        source_id: Unique identifier for the tile source
        config: Dictionary containing tile source configuration
    """
    _tile_sources[source_id] = config


def get_tile_source(source_id):
    """
    Get a tile source configuration by ID.
    
    Args:
        source_id: Unique identifier for the tile source
        
    Returns:
        Dictionary containing tile source configuration, or None if not found
    """
    return _tile_sources.get(source_id)


def get_all_tile_sources():
    """
    Get all registered tile sources.
    
    Returns:
        Dictionary mapping source IDs to their configurations
    """
    return _tile_sources.copy()


def get_tile_sources_for_client():
    """
    Get tile source configurations formatted for client consumption.
    Only includes information needed by the frontend.
    
    Returns:
        List of dictionaries with client-safe tile source configurations
    """
    sources = []
    for source_id, config in _tile_sources.items():
        client_config = {
            'id': source_id,
            'name': config.get('name', source_id),
            'type': config.get('type', 'xyz'),
            'requires_proxy': config.get('requires_proxy', False),
            'client_config': config.get('client_config', {})
        }
        sources.append(client_config)
    return sources


# Import all tile source modules to trigger registration
from . import osm
from . import mb_topo

