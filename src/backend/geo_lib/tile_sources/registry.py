from abc import ABC, abstractmethod


class TileSource(ABC):
    """
    Abstract base class for tile sources.
    
    Each tile source subclass should implement properties to define its configuration.
    If a tile source should not be registered (e.g., missing API key), set enabled=False.
    """

    @property
    @abstractmethod
    def id(self):
        """Unique identifier for the tile source."""
        pass

    @property
    @abstractmethod
    def name(self):
        """Display name for the tile source."""
        pass

    @property
    @abstractmethod
    def type(self):
        """Type of tile source (e.g., 'xyz', 'maptiler', 'terrain', 'hillshade')."""
        pass

    @property
    def enabled(self):
        """Whether this tile source should be registered. Override to return False to skip registration."""
        return True

    @property
    def requires_proxy(self):
        """Whether this tile source requires server-side proxying."""
        return False

    @property
    def hidden(self):
        """Whether this tile source should be hidden from the basemap selector."""
        return False

    @property
    def url_template(self):
        """URL template for the tile source (for proxied sources)."""
        return None

    @property
    def proxy_config(self):
        """Proxy configuration (headers, etc.) for proxied sources."""
        return None

    @property
    def client_config(self):
        """Configuration object to send to the client."""
        return {}

    @property
    def opacity(self):
        """Opacity for overlay sources like hillshade."""
        return None

    @property
    def exaggeration(self):
        """Exaggeration factor for terrain sources."""
        return None

    @property
    def needs_hillshade(self):
        """Whether this tile source benefits from hillshade overlay."""
        return False

    def to_dict(self):
        """
        Convert tile source to configuration dictionary.
        
        Returns:
            Dictionary containing tile source configuration, or None if not enabled.
        """
        if not self.enabled:
            return None

        config = {
            'id': self.id,
            'name': self.name,
            'type': self.type,
            'requires_proxy': self.requires_proxy,
            'hidden': self.hidden,
            'client_config': self.client_config,
            'needs_hillshade': self.needs_hillshade,
        }

        # Add optional properties if they're set
        if self.url_template:
            config['url_template'] = self.url_template
        if self.proxy_config:
            config['proxy_config'] = self.proxy_config
        if self.opacity is not None:
            config['opacity'] = self.opacity
        if self.exaggeration is not None:
            config['exaggeration'] = self.exaggeration

        return config


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

    # Import tile source classes and generator functions
    from geo_lib.tile_sources.osm import OSMTileSource
    from geo_lib.tile_sources.opentopomap import OpenTopoMapTileSource
    from geo_lib.tile_sources.mb_topo import MapbuilderTopoTileSource
    from geo_lib.tile_sources.global_imagery import GlobalImageryTileSource
    from geo_lib.tile_sources.maptiler_hillshade import MapTilerHillshadeTileSource
    from geo_lib.tile_sources.maptiler_terrain import MapTilerTerrainTileSource
    from geo_lib.tile_sources.hereapi import HereApiTileSource
    from geo_lib.tile_sources.maptiler import generate_maptiler_sources

    # Register single tile sources
    single_sources = [
        OSMTileSource(),
        OpenTopoMapTileSource(),
        MapbuilderTopoTileSource(),
        GlobalImageryTileSource(),
        MapTilerHillshadeTileSource(),
        MapTilerTerrainTileSource(),
        HereApiTileSource(),
    ]
    single_sources.extend(generate_maptiler_sources())

    for source in single_sources:
        config = source.to_dict()
        if config:
            _tile_sources[config['id']] = config

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
