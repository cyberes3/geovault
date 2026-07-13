"""
Core Django app wiring (INSTALLED_APPS, MIDDLEWARE, templates, WSGI/ASGI) plus every
domain-specific setting that doesn't belong in one of the other settings/ submodules
(tile sources, geocoding, icons, reverse geocoding, elevation, import processing, MaxMind).
"""
import os
from pathlib import Path

from website.config.loader import get_config
from website.settings.paths import BASE_DIR, EXTENSIONS_DIR

_config = get_config()

# Raw per-extension config sections, keyed by extension name (e.g. EXTENSIONS_CONFIG['live_track']),
# for extension code that needs settings beyond the enabled/disabled flag (which extension_loader
# reads directly from the config loader before Django settings exist; see extension_loader.py).
# Extensions define their own arbitrary settings shape, so unlike the core config sections above
# this isn't validated by a dedicated Pydantic model per extension.
EXTENSIONS_CONFIG: dict = {name: dict(section) for name, section in (_config.extensions.model_extra or {}).items()}

# pwa_mint.pwa_builder_url is the one extension-specific setting with an env var override
# (PWA_BUILDER_URL, for pointing at a locally-run pwabuilder-google-play container in dev).
if 'PWA_BUILDER_URL' in os.environ:
    EXTENSIONS_CONFIG.setdefault('pwa_mint', {})['pwa_builder_url'] = os.environ['PWA_BUILDER_URL']

from website.extensions.extension_loader import discover_extensions
_extension_apps = discover_extensions(EXTENSIONS_DIR)

INSTALLED_APPS = [
    'daphne',
    'channels',
    'users',
    'api',
    'django.contrib.admin',
    'django.contrib.auth',
    'django.contrib.contenttypes',
    'django.contrib.sessions',
    'django.contrib.messages',
    'django.contrib.staticfiles',
    'django.contrib.gis',
    'django.contrib.postgres',
    'django.contrib.sites',  # Required by allauth
    'allauth',
    'allauth.account',
    'allauth.socialaccount',
    'oauth2_provider',
] + _extension_apps

MIDDLEWARE = [
    'django.middleware.security.SecurityMiddleware',
    'website.middleware.LoggingMiddleware',  # Log BEFORE WhiteNoise to catch static file requests
    'website.middleware.FixRequestHostMiddleware',  # Fix request host for email URL generation (before allauth)
    'whitenoise.middleware.WhiteNoiseMiddleware',  # Serve static files in production
    'website.middleware.CustomSessionMiddleware',  # Custom SessionMiddleware that prevents cookies for tile requests
    'django.middleware.common.CommonMiddleware',
    'django.middleware.csrf.CsrfViewMiddleware',
    'django.contrib.auth.middleware.AuthenticationMiddleware',
    'website.middleware.APIKeyResolutionMiddleware',  # Resolve API key for /api/ so logs show username
    'website.middleware.ActivityTrackingMiddleware',  # Track user activity (after auth)
    'django.contrib.messages.middleware.MessageMiddleware',
    'django.middleware.clickjacking.XFrameOptionsMiddleware',
    'website.middleware.CustomHeaderMiddleware',
    'allauth.account.middleware.AccountMiddleware',
]

ROOT_URLCONF = 'website.urls'

TEMPLATES = [
    {
        'BACKEND': 'django.template.backends.django.DjangoTemplates',
        'DIRS': [
            BASE_DIR / '../frontend/dist',
            BASE_DIR / '../allauth templates',
            BASE_DIR / 'website' / 'templates',
        ],
        'APP_DIRS': True,
        'OPTIONS': {
            'context_processors': [
                'django.template.context_processors.debug',
                'django.template.context_processors.request',
                'django.contrib.auth.context_processors.auth',
                'django.contrib.messages.context_processors.messages',
            ],
        },
    },
]

WSGI_APPLICATION = 'website.wsgi.application'
ASGI_APPLICATION = 'website.asgi.application'

AUTH_PASSWORD_VALIDATORS = [
    {'NAME': 'django.contrib.auth.password_validation.UserAttributeSimilarityValidator'},
    {'NAME': 'django.contrib.auth.password_validation.MinimumLengthValidator'},
    {'NAME': 'django.contrib.auth.password_validation.CommonPasswordValidator'},
    {'NAME': 'django.contrib.auth.password_validation.NumericPasswordValidator'},
]

# Internationalization
LANGUAGE_CODE = 'en-us'
TIME_ZONE = 'UTC'
USE_I18N = True
USE_TZ = True

DEFAULT_AUTO_FIELD = 'django.db.models.BigAutoField'

APPEND_SLASH = True

# GeoJSON API Configuration
# Maximum number of features to return in a single API request.
# Set to -1 for no limit (default), or any positive integer to limit features.
# This is intentionally configured only in Django settings (not in config.yaml) to keep
# request-limiting behavior centralized here.
MAX_FEATURES_PER_REQUEST = -1

# Tag validation is intentionally configured only in Django settings (not in config.yaml)
# to keep tag validation logic centralized here.
TAG_MAX_LENGTH = 255

# Bounding Box Configuration (hardcoded - not user configurable)
BBOX_WORLD_WIDE_LON_THRESHOLD_1 = 280
BBOX_WORLD_WIDE_LON_THRESHOLD_2 = 270
BBOX_WORLD_WIDE_LAT_THRESHOLD = 170
BBOX_LARGE_EXTENT_LON_THRESHOLD = 200
BBOX_LARGE_EXTENT_LAT_THRESHOLD = 150
BBOX_SUSPICIOUS_RESULT_MIN_COUNT = 10

# Tile Proxy Cache Configuration
_tile_cache_dir = Path(_config.tilesources.cache_dir)
TILE_CACHE_DIR = _tile_cache_dir if _tile_cache_dir.is_absolute() else BASE_DIR / _tile_cache_dir
TILE_CACHE_ENABLED = _config.tilesources.cache_enabled
TILE_CACHE_EXPIRY_DAYS = _config.tilesources.cache_expiry_days
TILESOURCES_PROXY_OSM = _config.tilesources.proxy_osm
TILESOURCES_PROXY_SOURCES = _config.tilesources.proxy_sources
TILESOURCES_HIDDEN = _config.tilesources.hidden
TILESOURCES_SOCIAL_PREVIEW_RASTER_SOURCE = _config.tilesources.social_preview_raster_source

# MapTiler tile sources + forward geocoding
MAPTILER_API_KEY = _config.maptiler.api_key
MAPTILER_PROXY_TILES = _config.maptiler.proxy_tiles
MAPTILER_MAPS = _config.maptiler.maps
MAPTILER_HIDDEN_MAPS = _config.maptiler.hidden_maps

# Google Geocoding API
GOOGLE_GEOCODING_API_KEY = _config.google.geocoding.api_key

# Which provider backs /api/geocoding/search/ (place search): 'maptiler', 'google', or None
GEOCODING_SEARCH_MODE = _config.geocoding_search_mode

# Icon Processing Configuration
_icon_storage_dir = Path(_config.icons.storage_dir)
ICON_STORAGE_DIR = _icon_storage_dir if _icon_storage_dir.is_absolute() else BASE_DIR / _icon_storage_dir
ICON_PROCESSING_ENABLED = _config.icons.processing_enabled
ICON_MAX_SIZE_BYTES = _config.icons.max_size_bytes
ICON_UPLOAD_MAX_SIZE_BYTES = _config.icons.upload_max_size_bytes
ICON_UPLOAD_ALLOWED_EXTENSIONS = set(_config.icons.upload_allowed_extensions)
ICON_FETCH_TIMEOUT = _config.icons.fetch_timeout

# Reverse Geocoding Configuration
REVERSE_GEOCODING_ENABLED = _config.reverse_geocoding.enabled
CITY_PROXIMITY_MILES = _config.reverse_geocoding.city_proximity_miles
LAKE_PROXIMITY_MILES = _config.reverse_geocoding.lake_proximity_miles
AREAS_SERVER_URL = _config.reverse_geocoding.areas_server.api_url
AREAS_SERVER_TIMEOUT = _config.reverse_geocoding.areas_server.request_timeout_seconds
AREAS_SERVER_VERIFY_SSL = _config.reverse_geocoding.areas_server.verify_ssl
AREAS_SERVER_CITY_RADIUS_MILES = _config.reverse_geocoding.areas_server.city_radius_miles
AREAS_SERVER_MAX_BATCH_SIZE = _config.reverse_geocoding.areas_server.max_batch_size
# Number of points to sample along linestrings/multilinestrings, clamped to a sane range.
REVERSE_GEOCODING_LINESTRING_GEOCODE_POINTS = max(1, min(100, _config.reverse_geocoding.linestring_geocode_points))

# Elevation API Configuration
ELEVATION_API_URL = _config.elevation.api_url
ELEVATION_API_ENABLED = _config.elevation.enabled
ELEVATION_API_TIMEOUT = _config.elevation.timeout_seconds

# Import Processing Configuration
IMPORT_PROCESSING_THREADS = _config.processing.import_threads
PROCESSING_TIMEOUT_BASE_SECONDS = _config.processing.timeout_base_seconds
PROCESSING_TIMEOUT_PER_MB_SECONDS = _config.processing.timeout_per_mb_seconds
PROCESSING_TIMEOUT_JOB_CEILING_MULTIPLIER = _config.processing.timeout_job_ceiling_multiplier
DUPLICATE_DETECTION_BATCH_SIZE = _config.processing.duplicate_detection_batch_size
DUPLICATE_DETECTION_BATCH_THRESHOLD = _config.processing.duplicate_detection_batch_threshold
BULK_CREATE_BATCH_SIZE = _config.processing.bulk_create_batch_size
JOB_CLEANUP_INTERVAL_SECONDS = _config.processing.job_cleanup_interval_seconds
MAX_JOB_AGE_SECONDS = _config.processing.max_job_age_seconds
PROCESSING_SHOW_DETAILED_ERROR_MESSAGES = _config.processing.show_detailed_error_messages

# MaxMind IP Geolocation Configuration
MAXMIND_DATABASE_PATH = _config.maxmind.database_path
