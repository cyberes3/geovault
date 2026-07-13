"""
Pydantic schema for GeoVault's config.yaml.

This is the single source of truth for every valid configuration key, its type, and its
default value. The nested models mirror config.yaml's structure 1:1. `website.config.loader`
loads the YAML file, overlays environment variable overrides declared on fields below (via
each field's `env` alias in `json_schema_extra`), and validates the result against
`GeoVaultConfig`.

Fields with an `env` alias support an environment variable override, applied by the loader
before validation. Grep for `json_schema_extra={"env"` to find every override in one place.
"""
from typing import Any, Optional

from pydantic import BaseModel, ConfigDict, Field, field_validator

from geo_lib.logging.console import get_tagged_logger
from geo_lib.search_geocoding.constants import GEOCODING_SEARCH_MODES

logger = get_tagged_logger('config')

_VALID_LOG_LEVELS = ('critical', 'error', 'warning', 'info', 'debug')


class SiteConfig(BaseModel):
    """Site Framework configuration (django.contrib.sites), also used in email templates."""
    domain: str = 'geovault.example.com'
    name: str = 'GeoVault'


class SecurityConfig(BaseModel):
    """Django security/auth-adjacent settings."""
    secret_key: Optional[str] = Field(default=None, json_schema_extra={'env': 'SECRET_KEY'})
    debug: bool = False
    secure_cookies: Optional[bool] = None
    trust_x_forwarded_proto: Optional[bool] = None
    additional_allowed_hosts: list[str] = Field(default_factory=list)
    additional_cors_origins: list[str] = Field(default_factory=list)
    additional_csrf_trusted_origins: list[str] = Field(default_factory=list)


class LoggingConfig(BaseModel):
    """Application-wide logging configuration."""
    log_level: str = 'info'

    @field_validator('log_level', mode='after')
    @classmethod
    def _normalize_log_level(cls, value: str) -> str:
        normalized = (value or '').strip().lower()
        if normalized not in _VALID_LOG_LEVELS:
            logger.warning(
                "logging.log_level has invalid value %r; defaulting to 'info'. Valid values: %s",
                value,
                ', '.join(_VALID_LOG_LEVELS),
            )
            return 'info'
        return normalized


class DatabasePoolConfig(BaseModel):
    """Postgres connection pool sizing. Omit the whole `database.pool` section to disable pooling."""
    min_size: int = 2
    max_size: int = 30
    timeout: int = 60


class DatabaseTestConfig(BaseModel):
    """Credentials for the dedicated test database used by src/tests/run-tests.sh."""
    name: str = 'gv_tests'
    user: str = 'gv_tests'
    password: str = Field(default='', json_schema_extra={'env': 'TEST_DB_PASSWORD'})
    host: str = '127.0.0.1'
    port: int = 5432


class DatabaseConfig(BaseModel):
    """PostgreSQL/PostGIS connection settings."""
    name: str = 'geovault'
    user: str = 'geovault'
    password: str = Field(default='', json_schema_extra={'env': 'DB_PASSWORD'})
    host: str = 'localhost'
    port: int = 5432
    pool: Optional[DatabasePoolConfig] = None
    test: DatabaseTestConfig = Field(default_factory=DatabaseTestConfig)


class RedisConfig(BaseModel):
    """Redis connection used for caching, Channels, Celery, and rate limiting."""
    host: str = '127.0.0.1'
    port: int = 6379


class TileSourcesConfig(BaseModel):
    """Tile proxy/cache behavior and basemap selector visibility."""
    cache_dir: str = Field(default='data/tile-cache', json_schema_extra={'env': 'TILE_CACHE_DIR'})
    cache_enabled: bool = Field(default=True, json_schema_extra={'env': 'TILE_CACHE_ENABLED'})
    cache_expiry_days: int = 30
    proxy_osm: bool = False
    proxy_sources: list[str] = Field(default_factory=list)
    hidden: list[str] = Field(default_factory=list)
    social_preview_raster_source: str = 'osm'


class MapTilerConfig(BaseModel):
    """MapTiler maps/terrain/hillshade tile sources and forward geocoding."""
    api_key: Optional[str] = Field(default=None, json_schema_extra={'env': 'MAPTILER_API_KEY'})
    proxy_tiles: bool = False
    maps: list[str] = Field(default_factory=list)
    hidden_maps: list[str] = Field(default_factory=list)


class GoogleGeocodingConfig(BaseModel):
    api_key: Optional[str] = Field(default=None, json_schema_extra={'env': 'GOOGLE_API_KEY'})


class GoogleConfig(BaseModel):
    """Google API services (currently only Geocoding)."""
    geocoding: GoogleGeocodingConfig = Field(default_factory=GoogleGeocodingConfig)


class IconsConfig(BaseModel):
    """Icon extraction/storage for KML/KMZ imports and user icon uploads."""
    processing_enabled: bool = Field(default=True, json_schema_extra={'env': 'ICON_PROCESSING_ENABLED'})
    storage_dir: str = Field(default='data/icons', json_schema_extra={'env': 'ICON_STORAGE_DIR'})
    fetch_timeout: float = 5.0
    max_size_bytes: int = 1048576
    upload_max_size_bytes: int = 512000
    upload_allowed_extensions: list[str] = Field(default_factory=lambda: ['.png', '.jpg', '.jpeg', '.webp'])


class AreasServerConfig(BaseModel):
    """Internal areas server (admin boundaries, protected areas, lakes, ocean)."""
    api_url: str = 'http://127.0.0.1:5001'
    request_timeout_seconds: int = 10
    verify_ssl: bool = True
    max_batch_size: int = 100
    city_radius_miles: float = 3.0


class ReverseGeocodingConfig(BaseModel):
    """Reverse geocoding (proximity tags, admin boundaries) for imported features."""
    enabled: bool = True
    city_proximity_miles: float = 5.0
    lake_proximity_miles: float = 1.0
    areas_server: AreasServerConfig = Field(default_factory=AreasServerConfig)
    linestring_geocode_points: int = 4


class ElevationConfig(BaseModel):
    """Elevation API used to fill missing elevation data on imported features."""
    api_url: str = 'https://elevation.racemap.com/api'
    enabled: bool = True
    timeout_seconds: int = 30


class ProcessingConfig(BaseModel):
    """Import/processing pipeline tuning (threads, timeouts, batching, job lifecycle)."""
    import_threads: int = 10
    timeout_base_seconds: int = 30
    timeout_per_mb_seconds: int = 2
    timeout_job_ceiling_multiplier: int = 6
    duplicate_detection_batch_size: int = 100
    duplicate_detection_batch_threshold: int = 1000
    bulk_create_batch_size: int = 1000
    job_cleanup_interval_seconds: int = 3600
    max_job_age_seconds: int = 7200
    show_detailed_error_messages: bool = True


class CeleryConfig(BaseModel):
    """
    Celery broker/backend and task behavior.

    This section is optional: deployments without a `celery:` block in config.yaml get the
    defaults below (Redis-backed broker/backend are derived separately in
    website.settings.celery from `redis`, when broker_url/result_backend are left unset).
    """
    broker_url: Optional[str] = Field(default=None, json_schema_extra={'env': 'CELERY_BROKER_URL'})
    result_backend: Optional[str] = Field(default=None, json_schema_extra={'env': 'CELERY_RESULT_BACKEND'})
    default_queue: str = 'default'
    task_always_eager: bool = Field(default=False, json_schema_extra={'env': 'CELERY_TASK_ALWAYS_EAGER'})
    task_eager_propagates: bool = True
    worker_startup_timeout_seconds: int = 5
    beat_heartbeat_max_age_seconds: int = 20
    beat_startup_wait_seconds: int = 10


class EmailSmtpConfig(BaseModel):
    host: str = 'smtp.gmail.com'
    port: int = 587
    use_tls: bool = True
    use_ssl: bool = False
    username: str = ''
    password: str = Field(default='', json_schema_extra={'env': 'EMAIL_HOST_PASSWORD'})


class EmailConfig(BaseModel):
    """Outbound email (password reset, notifications, error emails to admins)."""
    smtp: EmailSmtpConfig = Field(default_factory=EmailSmtpConfig)
    from_email: str = 'noreply@example.com'
    from_name: str = ''
    subject_prefix: str = ''


class MaxmindConfig(BaseModel):
    """MaxMind GeoIP2 database used for IP-based geolocation."""
    database_path: Optional[str] = '/var/lib/GeoIP/GeoLite2-City.mmdb'


class ExtensionsConfig(BaseModel):
    """
    Per-extension configuration, keyed by the extension's manifest `name`.

    Extensions are plugins and define their own arbitrary settings shape under their own key
    (e.g. `extensions.pwa_mint.pwa_builder_url`), so this model only validates that the
    top-level container itself is a mapping; each extension's own section is accepted as-is
    (`extra='allow'`) rather than hardcoded here. Access an extension's raw section via
    `model_extra` (see `website.settings.app_config.EXTENSIONS_CONFIG`).
    """
    model_config = ConfigDict(extra='allow')


class GeoVaultConfig(BaseModel):
    """Root model for config.yaml. The single source of truth for every valid config key."""
    site: SiteConfig = Field(default_factory=SiteConfig)
    security: SecurityConfig = Field(default_factory=SecurityConfig)
    logging: LoggingConfig = Field(default_factory=LoggingConfig)
    database: DatabaseConfig = Field(default_factory=DatabaseConfig)
    redis: RedisConfig = Field(default_factory=RedisConfig)
    tilesources: TileSourcesConfig = Field(default_factory=TileSourcesConfig)
    maptiler: MapTilerConfig = Field(default_factory=MapTilerConfig)
    google: GoogleConfig = Field(default_factory=GoogleConfig)
    geocoding_search_mode: Optional[str] = None
    icons: IconsConfig = Field(default_factory=IconsConfig)
    reverse_geocoding: ReverseGeocodingConfig = Field(default_factory=ReverseGeocodingConfig)
    elevation: ElevationConfig = Field(default_factory=ElevationConfig)
    processing: ProcessingConfig = Field(default_factory=ProcessingConfig)
    celery: CeleryConfig = Field(default_factory=CeleryConfig)
    email: EmailConfig = Field(default_factory=EmailConfig)
    maxmind: MaxmindConfig = Field(default_factory=MaxmindConfig)
    extensions: ExtensionsConfig = Field(default_factory=ExtensionsConfig)

    @field_validator('geocoding_search_mode', mode='after')
    @classmethod
    def _normalize_geocoding_search_mode(cls, value: Optional[str]) -> Optional[str]:
        """
        Which provider backs /api/geocoding/search/ (place search). Invalid/unset values
        disable forward geocoding (with a warning) rather than failing startup, since forward
        geocoding is an optional feature.
        """
        if value is None or not value.strip():
            return None
        normalized = value.strip().lower()
        if normalized not in GEOCODING_SEARCH_MODES:
            logger.warning(
                "geocoding_search_mode has invalid value %r; expected one of %s. Forward geocoding is disabled.",
                value,
                ', '.join(repr(m) for m in GEOCODING_SEARCH_MODES),
            )
            return None
        return normalized

    def extension_settings(self, name: str) -> dict[str, Any]:
        """Raw config section for one extension (e.g. `extension_settings('live_track')`), or {} if unset."""
        extra = self.extensions.model_extra or {}
        section = extra.get(name)
        return section if isinstance(section, dict) else {}
