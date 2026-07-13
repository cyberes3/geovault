from django.conf import settings

from geo_lib.tile_sources.forest_service_topo_2016 import ForestServiceTopo2016TileSource
from geo_lib.tile_sources.global_imagery import GlobalImageryTileSource
from geo_lib.tile_sources.google_maps import GoogleMapsTileSource
from geo_lib.tile_sources.google_satellite_hybrid import GoogleSatelliteHybridTileSource
from geo_lib.tile_sources.google_terrain import GoogleTerrainTileSource
from geo_lib.tile_sources.herestreets import HereStreetsTileSource
from geo_lib.tile_sources.maptiler import generate_maptiler_sources
from geo_lib.tile_sources.maptiler_hillshade import MapTilerHillshadeTileSource
from geo_lib.tile_sources.maptiler_terrain import MapTilerTerrainTileSource
from geo_lib.tile_sources.mb_topo import MapbuilderTopoTileSource
from geo_lib.tile_sources.openhikingmap import OpenHikingMapTileSource
from geo_lib.tile_sources.opentopomap import OpenTopoMapTileSource
from geo_lib.tile_sources.osm import OSMTileSource

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
        ForestServiceTopo2016TileSource(),
        GlobalImageryTileSource(),
        MapTilerHillshadeTileSource(),
        MapTilerTerrainTileSource(),
        HereStreetsTileSource(),
        GoogleMapsTileSource(),
        GoogleSatelliteHybridTileSource(),
        GoogleTerrainTileSource(),
    ]
    single_sources.extend(generate_maptiler_sources())

    # Get list of tile sources that should be proxied from config
    proxy_sources = list(settings.TILESOURCES_PROXY_SOURCES)
    # When proxy_osm is true, proxy OSM-related sources (osm, opentopomap, openhikingmap) for caching and valid headers
    if settings.TILESOURCES_PROXY_OSM:
        proxy_sources = list(set(proxy_sources) | {'osm', 'opentopomap', 'openhikingmap'})

    # Filter out MapTiler sources - maptiler.proxy_tiles controls MapTiler proxying
    proxy_sources = [source_id for source_id in proxy_sources if not source_id.startswith('maptiler-')]

    hidden_set = {s for s in settings.TILESOURCES_HIDDEN if s and isinstance(s, str)}

    for source in single_sources:
        config = source.to_dict()
        if config:
            source_id = config['id']

            # Hide from basemap selector if in tilesources.hidden (or source's own hidden, e.g. maptiler.hidden_maps)
            if source_id in hidden_set:
                config['hidden'] = True

            # Override requires_proxy if this source is in the proxy_sources config list
            # But skip MapTiler sources - they're controlled by maptiler.proxy_tiles
            if source_id in proxy_sources and not source_id.startswith('maptiler-'):
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
