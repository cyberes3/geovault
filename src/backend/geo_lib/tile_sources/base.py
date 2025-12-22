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
