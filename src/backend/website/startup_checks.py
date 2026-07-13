"""
Startup checks for the GeoVault Django application.

This module performs essential checks when the server starts up:
1. Python version (requires 3.12 or 3.13)
2. Database connection
3. Required tables exist
4. PostGIS extension is installed
5. Redis connection
6. Writable directories (tile cache, icon storage)
7. Frontend static files are built
8. Site configuration (for email confirmation URLs)
9. Clear Redis cache (ensures fresh data on startup)
10. Recover interrupted jobs (redispatch jobs that were processing when server stopped)

Warning checks (don't fail startup):
- Configuration file exists
- MaxMind database availability
- Email configuration

Note: SECRET_KEY validation happens earlier, at settings-load time (see
website/secret_key_validation.py), since a missing/placeholder key must abort
startup entirely rather than just log a warning.
"""

import grp
import importlib.util
import os
import pwd
import sys
import time
import traceback
from pathlib import Path

from asgiref.sync import async_to_sync
from celery.exceptions import TimeoutError as CeleryTimeoutError
from channels.layers import get_channel_layer
from django.conf import settings
from django.contrib.sites.models import Site
from django.core.cache import cache
from django.db import connection

from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.file_types import FILE_TYPE_CONFIGS
from geo_lib.processing.job_recovery import recover_interrupted_jobs as do_job_recovery
from geo_lib.tile_sources.registry import get_tile_source
from geo_lib.utils.redis_connection import get_redis_connection
from website.celery_app import celery_app
from website.config_loader import get_config_loader
from website.settings_utils import get_required_setting
from website.extensions.extension_loader import get_extension_registry
from api.tasks import CELERY_BEAT_HEARTBEAT_KEY

_logger = get_tagged_logger('startup')


def check_python_version():
    """
    Check if Python version is 3.12 or 3.13.
    
    Returns:
        bool: True if Python 3.12 or 3.13 is being used, False otherwise
    """
    try:
        if sys.version_info[:2] not in ((3, 12), (3, 13)):
            current_version = f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}"
            _logger.error(f"✗ Python version check failed: requires Python 3.12 or 3.13, but found {current_version}")
            _logger.error(f"  Full version info: {sys.version}")
            _logger.error("  Please install Python 3.12 or 3.13 and ensure it's being used")
            return False
        else:
            _logger.info(f"✓ Python version check passed: {sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}")
            return True
    except Exception as e:
        _logger.error(f"✗ Python version check failed: {e}")
        return False


def check_database_connection(suppress_logging=False):
    """
    Check if the database connection is working.
    
    Args:
        suppress_logging: If True, suppress success log messages (errors still logged)
    
    Returns:
        bool: True if connection is successful, False otherwise
    """
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT 1")
            result = cursor.fetchone()
            if result and result[0] == 1:
                if not suppress_logging:
                    _logger.info("✓ Database connection successful")
                return True
            else:
                _logger.error("✗ Database connection test failed - unexpected result")
                return False
    except Exception as e:
        _logger.error(f"✗ Database connection failed: {e}")
        return False


def check_postgis_installation(suppress_logging=False):
    """
    Check if PostGIS extension is installed and available.
    
    Args:
        suppress_logging: If True, suppress success log messages (errors still logged)
    
    Returns:
        bool: True if PostGIS is installed, False otherwise
    """
    try:
        with connection.cursor() as cursor:
            # Check if PostGIS extension is installed
            cursor.execute("""
                           SELECT EXISTS(SELECT 1
                                         FROM pg_extension
                                         WHERE extname = 'postgis')
                           """)
            result = cursor.fetchone()

            if result and result[0]:
                if not suppress_logging:
                    _logger.info("✓ PostGIS extension is installed")

                # Check PostGIS version for additional verification
                if not suppress_logging:
                    cursor.execute("SELECT PostGIS_version()")
                    version = cursor.fetchone()
                    if version:
                        _logger.info(f"  PostGIS version: {version[0]}")

                return True
            else:
                _logger.error("✗ PostGIS extension is not installed")
                return False

    except Exception as e:
        _logger.error(f"✗ PostGIS check failed: {e}")
        return False


def check_required_tables():
    """
    Check if all required database tables exist.
    
    Returns:
        bool: True if all tables exist, False otherwise
    """
    required_tables = [
        'api_importqueue',
        'api_featurestore',
        'api_databaselogging',
        'auth_user',
        'django_migrations'
    ]

    try:
        with connection.cursor() as cursor:
            # Get all table names in the current database
            cursor.execute("""
                           SELECT table_name
                           FROM information_schema.tables
                           WHERE table_schema = 'public'
                           """)
            existing_tables = {row[0] for row in cursor.fetchall()}

            missing_tables = []
            for table in required_tables:
                if table not in existing_tables:
                    missing_tables.append(table)
                else:
                    _logger.info(f"✓ Table '{table}' exists")

            if missing_tables:
                _logger.error(f"✗ Missing required tables: {', '.join(missing_tables)}")
                return False
            else:
                _logger.info("✓ All required tables are present")
                return True

    except Exception as e:
        _logger.error(f"✗ Table check failed: {e}")
        return False


def check_spatial_tables():
    """
    Check if spatial tables have proper geometry columns.
    
    Returns:
        bool: True if spatial tables are properly configured, False otherwise
    """
    try:
        with connection.cursor() as cursor:
            # Check if FeatureStore table has geometry column
            cursor.execute("""
                           SELECT column_name, data_type
                           FROM information_schema.columns
                           WHERE table_name = 'api_featurestore'
                             AND column_name = 'geometry'
                           """)
            result = cursor.fetchone()

            if result:
                _logger.info(f"✓ FeatureStore geometry column exists (type: {result[1]})")
                return True
            else:
                _logger.error("✗ FeatureStore geometry column is missing")
                return False

    except Exception as e:
        _logger.error(f"✗ Spatial table check failed: {e}")
        return False


def check_redis_connection(suppress_logging=False):
    """
    Check if Redis connection is working.
    
    Args:
        suppress_logging: If True, suppress success log messages (errors still logged)
    
    Returns:
        bool: True if connection is successful, False otherwise
    """
    try:
        channel_layer = get_channel_layer()
        if channel_layer is None:
            _logger.error("✗ Redis connection failed: Channel layer not configured")
            return False

        # Test Redis connectivity by performing a simple async operation
        # This will fail immediately if Redis is not accessible
        async def test_redis():
            try:
                # Try to send a message to a test channel
                # This operation requires Redis to be available and will raise
                # a connection error if Redis is down
                await channel_layer.send('test_startup_check_channel', {'type': 'test'})
                return True
            except (ConnectionError, OSError, TimeoutError):
                # These indicate Redis connection issues
                raise
            except Exception as e:
                # Other exceptions (like channel errors) are fine - Redis is reachable
                _logger.debug(f"Redis channel layer test raised non-connection exception (expected): {type(e).__name__}: {str(e)}")
                return True

        try:
            result = async_to_sync(test_redis)()
            if result:
                if not suppress_logging:
                    _logger.info("✓ Redis connection successful")
                return True
            else:
                _logger.error("✗ Redis connection test failed")
                return False
        except (ConnectionError, OSError, TimeoutError) as e:
            _logger.error(f"✗ Redis connection failed: {e}")
            return False

    except Exception as e:
        _logger.error(f"✗ Redis connection failed: {e}")
        return False


def check_writable_directories():
    """
    Check if required directories exist and are writable.
    Creates directories if they don't exist.
    
    Returns:
        bool: True if all required directories are writable, False otherwise
    """
    try:
        all_ok = True

        # Get the root data directory
        data_dir = Path(get_required_setting('BASE_DIR')) / 'data'

        # Get current user and group names
        try:
            current_uid = os.getuid()
            current_gid = os.getgid()
            current_user = pwd.getpwuid(current_uid).pw_name
            current_group = grp.getgrgid(current_gid).gr_name
            user_group = f"{current_user}:{current_group}"
        except (KeyError, OSError):
            # Fallback if we can't determine user/group
            user_group = "USER:GROUP"

        # Check data directory if caching is enabled
        try:
            # Create directory if it doesn't exist
            data_dir.mkdir(parents=True, exist_ok=True)

            # Test write permissions by creating a test file
            test_file = data_dir / '.startup_test'
            try:
                test_file.write_text('test')
                test_file.unlink()
                _logger.info(f"✓ Data directory is writable: {data_dir}")
            except PermissionError:
                _logger.error(f"✗ Data directory is not writable: {data_dir}")
                _logger.error(f"  Permission denied. Fix with: sudo chown -R {user_group} {data_dir}")
                all_ok = False
            except Exception as e:
                _logger.error(f"✗ Data directory is not writable: {data_dir} - {e}")
                all_ok = False
        except PermissionError:
            _logger.error(f"✗ Failed to create/access data directory {data_dir}")
            _logger.error(f"  Permission denied. Fix with: sudo chown -R {user_group} {data_dir}")
            all_ok = False
        except Exception as e:
            _logger.error(f"✗ Failed to create/access data directory {data_dir}: {e}")
            all_ok = False
        return all_ok

    except Exception as e:
        _logger.error(f"✗ Directory check failed: {e}")
        return False


def check_frontend_files():
    """
    Check if frontend static files have been built.
    
    Returns:
        bool: True if frontend files exist, False otherwise
    """
    try:
        # Frontend dist directory is relative to BASE_DIR (backend directory)
        frontend_dist = Path(get_required_setting('BASE_DIR')).parent / 'frontend' / 'dist'

        # Check if dist directory exists
        if not frontend_dist.exists():
            _logger.error(f"✗ Frontend dist directory not found: {frontend_dist}")
            _logger.error("  Please build the frontend: cd frontend && npm run build")
            return False

        # Check for index.html (main entry point)
        index_html = frontend_dist / 'index.html'
        if not index_html.exists():
            _logger.error(f"✗ Frontend index.html not found: {index_html}")
            _logger.error("  Please build the frontend: cd frontend && npm run build")
            return False

        # Check for static directory with built assets
        static_dir = frontend_dist / 'static'
        if not static_dir.exists() or not static_dir.is_dir():
            _logger.warning(f"⚠ Frontend static directory not found: {static_dir}")
            _logger.warning("  Frontend may not be fully built")
        else:
            # Check if static directory has any files
            static_files = list(static_dir.iterdir())
            if not static_files:
                _logger.warning(f"⚠ Frontend static directory is empty: {static_dir}")
            else:
                _logger.info(f"✓ Frontend static files found ({len(static_files)} items)")

        _logger.info(f"✓ Frontend files are present: {frontend_dist}")
        return True

    except Exception as e:
        _logger.error(f"✗ Frontend files check failed: {e}")
        return False


def check_social_preview_tilesource():
    """
    Validate tilesources.social_preview_raster_source points to a registered raster (xyz) source.

    Uses the internal tile registry (get_tile_source), not only client-facing URLs, so
    tilesources.proxy_osm rewriting the client URL to /api/tiles/... does not invalidate
    the check when url_template remains a direct raster template.

    Returns:
        bool: True when valid, False otherwise.
    """
    try:
        config_loader = get_config_loader()
        source_id = config_loader.get_str("tilesources.social_preview_raster_source", "osm").strip() or "osm"
        cfg = get_tile_source(source_id)

        if not cfg:
            _logger.error(
                "✗ Social preview tile source '%s' is not registered. "
                "Set tilesources.social_preview_raster_source to a valid raster basemap source id (default: osm).",
                source_id,
            )
            return False

        if cfg.get("type") != "xyz":
            _logger.error(
                "✗ Social preview tile source '%s' is not a raster xyz layer (type=%r). "
                "Please configure a raster basemap (example: osm).",
                source_id,
                cfg.get("type"),
            )
            return False

        client_config = cfg.get("client_config", {})
        source_type = client_config.get("type")
        tile_url = client_config.get("url")
        url_template = cfg.get("url_template")
        has_client_template = (
            isinstance(tile_url, str)
            and "{z}" in tile_url
            and "{x}" in tile_url
            and "{y}" in tile_url
        )
        has_upstream_template = (
            isinstance(url_template, str)
            and "{z}" in url_template
            and "{x}" in url_template
            and "{y}" in url_template
        )
        if source_type != "xyz" or (not has_client_template and not has_upstream_template):
            _logger.error(
                "✗ Social preview tile source '%s' is not a raster xyz layer with a tile URL template. "
                "Found client_config.type=%r. Please configure a raster basemap (example: osm).",
                source_id,
                source_type,
            )
            return False

        _logger.info("✓ Social preview tile source is valid: %s", source_id)
        return True
    except Exception as e:
        _logger.error(f"✗ Social preview tile source check failed: {e}")
        return False


def check_font_glyphs():
    """
    Check if MapLibre font glyphs have been generated.
    
    Returns:
        bool: True if fonts are present, False otherwise
    """
    try:
        # Get assets fonts directory path
        assets_fonts_dir = Path(get_required_setting('BASE_DIR')) / 'assets' / 'fonts'

        # Check if fonts directory exists
        if not assets_fonts_dir.exists():
            _logger.error(f"✗ Fonts directory not found: {assets_fonts_dir}")
            _logger.error("  Please run: cd src/backend && ./generate-map-fonts.sh")
            return False

        if not assets_fonts_dir.is_dir():
            _logger.error(f"✗ Fonts path is not a directory: {assets_fonts_dir}")
            return False

        # Check for font stack directories
        font_stacks = [d for d in assets_fonts_dir.iterdir() if d.is_dir()]
        if not font_stacks:
            _logger.error(f"✗ No font stacks found in: {assets_fonts_dir}")
            _logger.error("  Please run: cd src/backend && ./generate-map-fonts.sh")
            return False

        # Check that at least one font stack has PBF files
        found_pbf_files = False
        common_fonts = ['Noto Sans Regular', 'Open Sans Regular', 'Roboto Regular']
        found_common_fonts = []

        for font_stack in font_stacks:
            # Check for PBF files in this font stack
            pbf_files = list(font_stack.glob('*.pbf'))
            if pbf_files:
                found_pbf_files = True
                if font_stack.name in common_fonts:
                    found_common_fonts.append(font_stack.name)

        if not found_pbf_files:
            _logger.error(f"✗ No PBF font files found in any font stack")
            _logger.error("  Please run: cd src/backend && ./generate-map-fonts.sh")
            return False

        # Check for the first range file (0-255.pbf) in at least one common font
        has_base_range = False
        for font_name in common_fonts:
            font_dir = assets_fonts_dir / font_name
            if font_dir.exists() and (font_dir / '0-255.pbf').exists():
                has_base_range = True
                break

        if not has_base_range:
            _logger.warning(f"⚠ Common font base range (0-255.pbf) not found")
            _logger.warning("  Fonts may be incomplete. Consider re-running: cd src/backend && ./generate-map-fonts.sh")

        _logger.info(f"✓ Font glyphs found: {len(font_stacks)} font stack(s)")
        if found_common_fonts:
            _logger.info(f"  Common fonts available: {', '.join(found_common_fonts)}")
        return True

    except Exception as e:
        _logger.error(f"✗ Font glyphs check failed: {e}")
        return False


def check_file_type_max_size():
    """
    Check that all FILE_TYPE_CONFIGS max_size values are less than 200MB.
    This is critical because values >= 200MB will break the database.
    
    Returns:
        bool: True if all max_size values are valid, False otherwise
    """
    try:
        MAX_ALLOWED_SIZE_BYTES = 200 * 1024 * 1024  # 200MB in bytes
        invalid_configs = []

        for file_type, config in FILE_TYPE_CONFIGS.items():
            if config.max_size >= MAX_ALLOWED_SIZE_BYTES:
                max_size_mb = config.max_size / (1024 * 1024)
                invalid_configs.append((file_type.value, config.max_size, max_size_mb))

        if invalid_configs:
            _logger.error("✗ File type max_size validation failed!")
            _logger.error(f"  The following file types have max_size >= 200MB (which will break the database):")
            for file_type_name, max_size_bytes, max_size_mb in invalid_configs:
                _logger.error(f"    - {file_type_name}: max_size = {max_size_bytes} bytes ({max_size_mb:.1f}MB)")
            _logger.error(f"  All max_size values must be less than 200MB ({MAX_ALLOWED_SIZE_BYTES} bytes)")
            _logger.error("  Please fix FILE_TYPE_CONFIGS in geo_lib/processing/file_types.py")
            return False

        _logger.info("✓ File type max_size validation passed (all values < 200MB)")
        return True

    except Exception as e:
        _logger.error(f"✗ File type max_size check failed: {e}")
        return False


def check_config_file():
    """
    Check if configuration file exists.
    This is a warning-only check.
    """
    try:
        config_loader = get_config_loader()
        config_path = config_loader.config_path

        if not config_path.exists():
            _logger.warning(f"⚠ Configuration file not found: {config_path}")
            _logger.warning("  Using default configuration values. Create config.yaml for custom settings.")
        else:
            _logger.info(f"✓ Configuration file found: {config_path}")

    except Exception as e:
        _logger.warning(f"⚠ Could not check configuration file: {e}")


def check_maxmind_database():
    """
    Check if MaxMind database file exists.
    This is a warning-only check (optional feature).
    """
    try:
        maxmind_path = get_required_setting('MAXMIND_DATABASE_PATH')
        if maxmind_path:
            maxmind_file = Path(maxmind_path)
            if not maxmind_file.exists():
                _logger.warning(f"⚠ MaxMind database file not found: {maxmind_path}")
                _logger.warning("  IP geolocation features may not work. This is optional.")
            else:
                _logger.info(f"✓ MaxMind database found: {maxmind_path}")
        else:
            _logger.info("  MaxMind database path not configured (optional)")

    except Exception as e:
        _logger.warning(f"⚠ Could not check MaxMind database: {e}")


def check_email_config():
    """
    Check if email configuration is using default/unconfigured values.
    This is a warning-only check (optional feature).
    """
    try:
        email_host = get_required_setting('EMAIL_HOST')
        email_user = get_required_setting('EMAIL_HOST_USER')
        email_password = get_required_setting('EMAIL_HOST_PASSWORD')
        from_email = get_required_setting('DEFAULT_FROM_EMAIL')

        # Check for default/unconfigured values
        is_default = False
        issues = []

        if email_host == 'smtp.gmail.com' and not email_user:
            is_default = True
            issues.append("using default SMTP host without username")

        if not email_user:
            is_default = True
            issues.append("EMAIL_HOST_USER is not set")

        if not email_password:
            is_default = True
            issues.append("EMAIL_HOST_PASSWORD is not set")

        if from_email == 'noreply@example.com':
            is_default = True
            issues.append("using default from_email (noreply@example.com)")

        if is_default:
            _logger.warning("⚠ Email configuration appears to be using default/unconfigured values:")
            for issue in issues:
                _logger.warning(f"  - {issue}")
            _logger.warning("  Email features (password reset, notifications) may not work.")
        else:
            _logger.info("✓ Email configuration appears to be configured")

    except Exception as e:
        _logger.warning(f"⚠ Could not check email configuration: {e}")


def check_site_configuration():
    """
    Check if Django Sites framework is properly configured.
    Verifies that Site object can be created/updated from settings.
    This is a critical check as it's required for email confirmation URLs.
    Requires that site.domain is explicitly set (not using the default value).
    
    Returns:
        bool: True if Site configuration is valid, False otherwise
    """
    try:
        # Check if required settings exist
        if not hasattr(settings, 'SITE_DOMAIN') or not hasattr(settings, 'SITE_NAME'):
            _logger.error("✗ Site configuration missing: SITE_DOMAIN and SITE_NAME must be defined in settings")
            _logger.error("  Email confirmation links will not work without proper Site configuration")
            return False

        if not hasattr(settings, 'SITE_ID'):
            _logger.error("✗ Site configuration missing: SITE_ID must be defined in settings")
            return False

        # Check if site.domain is explicitly set (not using default)
        config_loader = get_config_loader()
        default_domain = 'geovault.example.com'

        # Check if site.domain is set in the config
        site_domain_config = config_loader.get('site.domain', None)
        if site_domain_config is None or site_domain_config == default_domain:
            _logger.error("✗ Site domain is not configured: 'site.domain' must be explicitly set in config.yaml")
            _logger.error(f"  Current value: {site_domain_config if site_domain_config else 'not set (using default: ' + default_domain + ')'}")
            _logger.error("  Please set 'site.domain' in your config.yaml file to your actual domain name")
            _logger.error("  Example: site.domain: mydomain.com")
            return False

        site_domain = get_required_setting('SITE_DOMAIN')
        site_name = get_required_setting('SITE_NAME')
        site_id = get_required_setting('SITE_ID')

        # Verify we can create/update the Site object
        # This is the same logic used in NoUsernameAccountAdapter.get_email_confirmation_url()
        try:
            site, created = Site.objects.get_or_create(id=site_id)

            if created:
                _logger.info(f"✓ Created Site object with ID {site_id}")

            # Update if needed
            if site.domain != site_domain or site.name != site_name:
                site.domain = site_domain
                site.name = site_name
                site.save()
                _logger.info(f"✓ Updated Site object: {site_name} ({site_domain})")
            else:
                _logger.info(f"✓ Site configuration is valid: {site_name} ({site_domain})")

            # Verify we can retrieve it
            Site.objects.get(id=site_id)

            return True

        except Exception as e:
            _logger.error(f"✗ Failed to create/update Site object: {e}")
            return False

    except Exception as e:
        _logger.error(f"✗ Site configuration check failed: {e}")
        return False


def clear_redis_cache():
    """
    Clear the Redis cache on startup.
    
    This ensures fresh data after server restarts and prevents stale
    cached data (especially important for reverse geocoding which caches
    results for 30 days).
    """
    try:
        # Clear all cached data
        cache.clear()
        _logger.info("✓ Cleared Redis cache (ensures fresh data on startup)")

        return True
    except Exception as e:
        _logger.warning(f"⚠ Failed to clear Redis cache: {e}")
        # This is not critical - server can still start
        return True


def recover_interrupted_jobs():
    """
    Recover and redispatch jobs that were interrupted during processing.

    ImportQueue entries persist in the database independently of Celery, so this finds jobs
    that were being processed but didn't complete (e.g. a worker was killed mid-job) and
    redispatches them to the `imports` Celery queue.

    This is non-critical - if recovery fails, the server can still start.
    """
    try:
        result = do_job_recovery()

        if result['total_found'] == 0:
            _logger.info("✓ No interrupted jobs to recover")
        else:
            _logger.info(f"✓ Job recovery: {result['recovered']}/{result['total_found']} jobs recovered")
            if result['failed'] > 0:
                _logger.warning(f"⚠ Failed to recover {result['failed']} job(s)")
            if result['users_affected'] > 0:
                _logger.info(f"  Affected users: {result['users_affected']}")

    except Exception:
        _logger.warning(f"⚠ Failed to recover interrupted jobs: {traceback.format_exc()}")
        # This is not critical - server can still start


def check_extensions():
    """
    Check for loaded extensions, detect duplicates, and log them.
    
    Returns:
        bool: False if duplicate extension names are detected, True otherwise
    """
    try:
        from website.settings import EXTENSIONS_DIR
        
        # Scan extensions directory directly to detect duplicates
        # (registry overwrites duplicates, so we need to check before loading)
        extension_names = {}  # name -> list of folder names
        
        if EXTENSIONS_DIR.exists():
            for item in EXTENSIONS_DIR.iterdir():
                if item.is_dir():
                    manifest_path = item / 'manifest.py'
                    if manifest_path.exists():
                        try:
                            spec = importlib.util.spec_from_file_location("manifest", manifest_path)
                            if spec and spec.loader:
                                manifest = importlib.util.module_from_spec(spec)
                                spec.loader.exec_module(manifest)
                                if hasattr(manifest, 'name'):
                                    ext_name = manifest.name
                                    if ext_name not in extension_names:
                                        extension_names[ext_name] = []
                                    extension_names[ext_name].append(item.name)
                        except Exception:
                            # Skip invalid manifests - they'll be caught by extension loader
                            pass
        
        # Check for duplicates
        duplicates = {name: folders for name, folders in extension_names.items() if len(folders) > 1}
        
        if duplicates:
            _logger.error("=" * 60)
            _logger.error("DUPLICATE EXTENSION NAMES DETECTED!")
            _logger.error("=" * 60)
            _logger.error("Multiple extensions have the same 'name' in their manifest.py:")
            
            for dup_name, folders in duplicates.items():
                _logger.error(f"  - Extension name '{dup_name}' is used by multiple extensions")
                _logger.error(f"    Found in folders: {', '.join(folders)}")
            
            _logger.error("")
            _logger.error("Each extension must have a unique 'name' in manifest.py.")
            _logger.error("Please rename one of the conflicting extensions.")
            _logger.error("=" * 60)
            return False
        
        # Now log loaded extensions
        registry = get_extension_registry()
        active_exts = registry.get_loaded_extensions()
        
        if active_exts:
            ext_names = [ext['name'] for ext in active_exts]
            _logger.info(f"✓ Successfully loaded {len(ext_names)} extensions: {', '.join(ext_names)}")
            for ext in active_exts:
                suffix = " (No frontend module)" if not ext.get('frontend_entry') else ""
                _logger.info(f"  - {ext['name']} v{ext['version']}{suffix}")
        else:
            _logger.info("  No extensions loaded or enabled")
        return True
    except Exception as e:
        _logger.error(f"⚠ Extension check failed: {e}")
        _logger.error(traceback.format_exc())
        return False


def check_live_track_flusher():
    """
    Live Track flush runs via Celery; this startup check is intentionally a no-op log line.
    """
    _logger.info("✓ Live Track flusher check skipped (Celery-based flush enabled)")
    return True


def _log_celery_service_help(service_name: str, additional_hint: str = "") -> None:
    """Log concise operator guidance when a Celery startup check fails."""
    _logger.error("  Celery startup help:")
    _logger.error(f"  - Check status: sudo systemctl status {service_name}")
    _logger.error(f"  - View logs: sudo journalctl -u {service_name} -n 120 --no-pager")
    _logger.error("  - Verify Redis: sudo systemctl status redis redis-server")
    _logger.error("  - Restart: sudo systemctl restart geovault-celery geovault-celery-beat geovault")
    if additional_hint:
        _logger.error(f"  - Hint: {additional_hint}")


def check_celery_worker(suppress_logging=False):
    """
    Verify Celery worker availability by dispatching a lightweight task and awaiting result.

    Args:
        suppress_logging: If True, do not log success or failure (for health endpoint).
    """
    try:
        config_loader = get_config_loader()
        timeout_seconds = max(1, config_loader.get_int("celery.worker_startup_timeout_seconds", 5))
        result = celery_app.send_task("api.celery_health.ping_worker", queue="maintenance")
        value = result.get(timeout=timeout_seconds)
        if value != "pong":
            if not suppress_logging:
                _logger.error("✗ Celery worker check failed: unexpected response %r", value)
            return False
        if not suppress_logging:
            _logger.info("✓ Celery worker is reachable")
        return True
    except CeleryTimeoutError:
        if not suppress_logging:
            _logger.error(
                "✗ Celery worker check failed: timed out waiting for ping task result "
                "(worker did not respond before timeout)."
            )
            _log_celery_service_help(
                "geovault-celery",
                "Ensure the worker is running and subscribed to the 'maintenance' queue.",
            )
        return False
    except Exception as e:
        if not suppress_logging:
            _logger.error(f"✗ Celery worker check failed: {e}")
            _log_celery_service_help("geovault-celery")
        return False


def check_celery_beat(suppress_logging=False, wait_for_heartbeat=True):
    """
    Verify celery-beat scheduling by checking for a recent Redis heartbeat timestamp.

    Args:
        suppress_logging: If True, do not log success or failure (for health endpoint).
        wait_for_heartbeat: If True, loop until deadline waiting for a recent heartbeat.
            If False, check once and return immediately (for health endpoint).
    """
    try:
        config_loader = get_config_loader()
        max_age = max(5, config_loader.get_int("celery.beat_heartbeat_max_age_seconds", 20))
        wait_seconds = max(0, config_loader.get_int("celery.beat_startup_wait_seconds", 10))
        deadline = time.time() + wait_seconds
        redis_client = get_redis_connection()

        while True:
            raw = redis_client.get(CELERY_BEAT_HEARTBEAT_KEY)
            if raw:
                try:
                    heartbeat_ts = float(raw)
                    if (time.time() - heartbeat_ts) <= max_age:
                        if not suppress_logging:
                            _logger.info("✓ Celery beat heartbeat is recent")
                        return True
                except (TypeError, ValueError):
                    pass

            if not wait_for_heartbeat or time.time() >= deadline:
                break
            time.sleep(1)

        if not suppress_logging:
            _logger.error("✗ Celery beat check failed: heartbeat missing or stale.")
            _log_celery_service_help(
                "geovault-celery-beat",
                "Beat must be running so periodic tasks can update the heartbeat key.",
            )
        return False
    except Exception as e:
        if not suppress_logging:
            _logger.error(f"✗ Celery beat check failed: {e}")
            _log_celery_service_help("geovault-celery-beat")
        return False


def run_startup_checks():
    """
    Run all startup checks and exit if any fail.
    
    This function will:
    1. Check Python version (requires 3.12 or 3.13)
    2. Check database connection
    3. Verify PostGIS installation
    4. Check required tables exist
    5. Verify spatial table configuration
    6. Check Redis connection
    7. Check writable directories (create if needed)
    8. Check frontend files are built
    9. Validate file type max_size values (< 200MB)
    10. Verify Site configuration (for email confirmation URLs)
    11. Clear Redis cache (ensures fresh data on startup)
    12. Recover interrupted jobs (redispatch jobs that were processing when server stopped)
    13. Check for duplicate extension names
    
    Warning checks (don't fail startup):
    - Configuration file
    - MaxMind database
    - Email configuration
    
    Raises:
        SystemExit: If any critical check fails
    """
    _logger.info("Starting GeoVault startup checks...")

    # Critical checks that will fail startup
    critical_checks = [
        ("Python Version", check_python_version),
        ("Database Connection", check_database_connection),
        ("PostGIS Installation", check_postgis_installation),
        ("Required Tables", check_required_tables),
        ("Spatial Tables", check_spatial_tables),
        ("Redis Connection", check_redis_connection),
        ("Writable Directories", check_writable_directories),
        ("Frontend Files", check_frontend_files),
        ("Font Glyphs", check_font_glyphs),
        ("Social Preview Tile Source", check_social_preview_tilesource),
        ("File Type Max Size", check_file_type_max_size),
        ("Site Configuration", check_site_configuration),
        ("Extensions", check_extensions),
        ("Celery worker", check_celery_worker),
        ("Celery beat", check_celery_beat),
    ]

    failed_checks = []

    for check_name, check_func in critical_checks:
        _logger.info(f"Running {check_name} check...")
        if not check_func():
            failed_checks.append(check_name)

    # Run warning checks (don't fail startup, but log warnings)
    _logger.info("Running warning checks...")
    check_config_file()
    check_maxmind_database()
    check_email_config()

    # Clear Redis cache on startup (non-critical)
    _logger.info("Clearing Redis cache...")
    clear_redis_cache()

    # Recover interrupted jobs (non-critical, re-enqueues jobs that were processing when server stopped)
    _logger.info("Recovering interrupted jobs...")
    recover_interrupted_jobs()

    if failed_checks:
        _logger.error("=" * 60)
        _logger.error("STARTUP CHECKS FAILED!")
        _logger.error("=" * 60)
        _logger.error("The following checks failed:")
        for check in failed_checks:
            _logger.error(f"  - {check}")
        _logger.error("")
        _logger.error("Please fix the issues above before starting the server.")
        _logger.error("Common solutions:")
        _logger.error("  - Install Python 3.13 and ensure it's being used")
        _logger.error("  - Ensure PostgreSQL is running")
        _logger.error("  - Install PostGIS extension: CREATE EXTENSION postgis;")
        _logger.error("  - Run migrations: python manage.py migrate")
        _logger.error("  - Ensure Redis is running and accessible")
        _logger.error("  - Build frontend: cd frontend && npm run build")
        _logger.error("  - Generate fonts: cd src/backend && ./generate-map-fonts.sh")
        _logger.error("  - Ensure directories are writable")
        _logger.error("=" * 60)
        sys.exit(1)
    else:
        _logger.info("=" * 60)
        _logger.info("✓ ALL STARTUP CHECKS PASSED!")
        _logger.info("✓ GeoVault is ready to start")
        _logger.info("=" * 60)
