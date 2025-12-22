from geo_lib.tile_sources.global_imagery import GlobalImageryTileSource
from geo_lib.tile_sources.google_maps import GoogleMapsTileSource
from geo_lib.tile_sources.google_terrain import GoogleTerrainTileSource
from geo_lib.tile_sources.herestreets import HereStreetsTileSource
from geo_lib.tile_sources.maptiler import generate_maptiler_sources
from geo_lib.tile_sources.maptiler_hillshade import MapTilerHillshadeTileSource
from geo_lib.tile_sources.maptiler_terrain import MapTilerTerrainTileSource
from geo_lib.tile_sources.mb_topo import MapbuilderTopoTileSource
from geo_lib.tile_sources.openhikingmap import OpenHikingMapTileSource
from geo_lib.tile_sources.opentopomap import OpenTopoMapTileSource
from geo_lib.tile_sources.osm import OSMTileSource
from website.config_loader import get_config_loader

_tile_sources = {}
_registered = False


def _initialize_tile_sources():
    """
    Initialize and register all tile sources.
    This function is called automatically on first access to tile sources.
    """
    global _registered
    if _registered:
        return

    # Register single tile sources
    single_sources = [
        OSMTileSource(),
        OpenTopoMapTileSource(),
        OpenHikingMapTileSource(),
        MapbuilderTopoTileSource(),
        GlobalImageryTileSource(),
        MapTilerHillshadeTileSource(),
        MapTilerTerrainTileSource(),
        HereStreetsTileSource(),
        GoogleMapsTileSource(),
        GoogleTerrainTileSource(),
    ]
    single_sources.extend(generate_maptiler_sources())

    # Get list of tile sources that should be proxied from config
    config_loader = get_config_loader()
    proxy_sources = config_loader.get('tilesources.proxy_sources', [])
    if not isinstance(proxy_sources, list):
        proxy_sources = []

    for source in single_sources:
        config = source.to_dict()
        if config:
            source_id = config['id']
            
            # Override requires_proxy if this source is in the proxy_sources config list
            if source_id in proxy_sources:
                config['requires_proxy'] = True
                # Update client_config URL to use proxy endpoint if it's currently a direct URL
                client_config = config.get('client_config', {})
                url = client_config.get('url', '')
                # If URL is a direct external URL, change it to proxy endpoint
                if url and (url.startswith('https://') or url.startswith('http://')):
                    client_config['url'] = f'/api/tiles/{source_id}/{{z}}/{{x}}/{{y}}'
                    config['client_config'] = client_config
            
            _tile_sources[source_id] = config

    _registered = True


def get_tile_source(source_id):
    """
    Get a tile source configuration by ID.

    Args:
        source_id: Unique identifier for the tile source

    Returns:
        Dictionary containing tile source configuration, or None if not found
    """
    _initialize_tile_sources()
    return _tile_sources.get(source_id)


def get_all_tile_sources():
    """
    Get all registered tile sources.

    Returns:
        Dictionary mapping source IDs to their configurations
    """
    _initialize_tile_sources()
    return _tile_sources.copy()


def get_tile_sources_for_client():
    """
    Get tile source configurations formatted for client consumption.
    Only includes information needed by the frontend.
    Includes all sources (even hidden utility sources like terrain/hillshade).

    Returns:
        List of dictionaries with client-safe tile source configurations
    """
    _initialize_tile_sources()
    sources = []
    for source_id, config in _tile_sources.items():
        client_config = {
            'id': source_id,
            'name': config.get('name', source_id),
            'type': config.get('type', 'xyz'),
            'requires_proxy': config.get('requires_proxy', False),
            'needs_hillshade': config.get('needs_hillshade', False),
            'hidden': config.get('hidden', False),
            'client_config': config.get('client_config', {})
        }

        # Include additional properties for hidden sources (terrain/hillshade)
        if config.get('hidden'):
            if 'exaggeration' in config:
                client_config['exaggeration'] = config['exaggeration']
            if 'opacity' in config:
                client_config['opacity'] = config['opacity']

        sources.append(client_config)
    return sources
