"""
Startup checks for the GeoVault Django application.

This module performs essential checks when the server starts up:
1. Python version (requires 3.12)
2. Database connection
3. Required tables exist
4. PostGIS extension is installed
5. Redis connection
6. Writable directories (tile cache, icon storage)
7. Frontend static files are built
8. togeojson Node.js converter is installed
9. Site configuration (for email confirmation URLs)
10. Clean up stale Redis queues and job status data
11. Clear Redis cache (ensures fresh data on startup)
12. Preload ski resorts database (for reverse geocoding)

Warning checks (don't fail startup):
- Configuration file exists
- Secret key security
- MaxMind database availability
- Email configuration
"""

import sys
from pathlib import Path

from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer
from django.conf import settings
from django.contrib.sites.models import Site
from django.db import connection

from geo_lib.geocoding.reverse_geocode import load_ski_resorts
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.file_types import FILE_TYPE_CONFIGS
from geo_lib.utils.redis_connection import get_redis_connection
from website.config_loader import get_config_loader
from website.settings_utils import get_required_setting
from django.core.cache import cache

logger = get_tagged_logger('startup')


def check_python_version():
    """
    Check if Python version is 3.12.
    
    Returns:
        bool: True if Python 3.12 is being used, False otherwise
    """
    try:
        if sys.version_info[:2] != (3, 12):
            current_version = f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}"
            logger.error(f"✗ Python version check failed: requires Python 3.12, but found {current_version}")
            logger.error(f"  Full version info: {sys.version}")
            logger.error("  Please install Python 3.12 and ensure it's being used")
            return False
        else:
            logger.info(f"✓ Python version check passed: {sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}")
            return True
    except Exception as e:
        logger.error(f"✗ Python version check failed: {e}")
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
                    logger.info("✓ Database connection successful")
                return True
            else:
                logger.error("✗ Database connection test failed - unexpected result")
                return False
    except Exception as e:
        logger.error(f"✗ Database connection failed: {e}")
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
                SELECT EXISTS(
                    SELECT 1 FROM pg_extension 
                    WHERE extname = 'postgis'
                )
            """)
            result = cursor.fetchone()
            
            if result and result[0]:
                if not suppress_logging:
                    logger.info("✓ PostGIS extension is installed")
                
                # Check PostGIS version for additional verification
                if not suppress_logging:
                    cursor.execute("SELECT PostGIS_version()")
                    version = cursor.fetchone()
                    if version:
                        logger.info(f"  PostGIS version: {version[0]}")
                
                return True
            else:
                logger.error("✗ PostGIS extension is not installed")
                return False
                
    except Exception as e:
        logger.error(f"✗ PostGIS check failed: {e}")
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
                    logger.info(f"✓ Table '{table}' exists")
            
            if missing_tables:
                logger.error(f"✗ Missing required tables: {', '.join(missing_tables)}")
                return False
            else:
                logger.info("✓ All required tables are present")
                return True
                
    except Exception as e:
        logger.error(f"✗ Table check failed: {e}")
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
                logger.info(f"✓ FeatureStore geometry column exists (type: {result[1]})")
                return True
            else:
                logger.error("✗ FeatureStore geometry column is missing")
                return False
                
    except Exception as e:
        logger.error(f"✗ Spatial table check failed: {e}")
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
            logger.error("✗ Redis connection failed: Channel layer not configured")
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
                logger.debug(f"Redis channel layer test raised non-connection exception (expected): {type(e).__name__}: {str(e)}")
                return True
        
        try:
            result = async_to_sync(test_redis)()
            if result:
                if not suppress_logging:
                    logger.info("✓ Redis connection successful")
                return True
            else:
                logger.error("✗ Redis connection test failed")
                return False
        except (ConnectionError, OSError, TimeoutError) as e:
            logger.error(f"✗ Redis connection failed: {e}")
            return False
                
    except Exception as e:
        logger.error(f"✗ Redis connection failed: {e}")
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
        
        # Check tile cache directory if caching is enabled
        if get_required_setting('TILE_CACHE_ENABLED'):
            tile_cache_dir = Path(get_required_setting('TILE_CACHE_DIR'))
            try:
                # Create directory if it doesn't exist
                tile_cache_dir.mkdir(parents=True, exist_ok=True)
                
                # Test write permissions by creating a test file
                test_file = tile_cache_dir / '.startup_test'
                try:
                    test_file.write_text('test')
                    test_file.unlink()
                    logger.info(f"✓ Tile cache directory is writable: {tile_cache_dir}")
                except Exception as e:
                    logger.error(f"✗ Tile cache directory is not writable: {tile_cache_dir} - {e}")
                    all_ok = False
            except Exception as e:
                logger.error(f"✗ Failed to create/access tile cache directory {tile_cache_dir}: {e}")
                all_ok = False
        
        # Check icon storage directory if icon processing is enabled
        if get_required_setting('ICON_PROCESSING_ENABLED'):
            icon_storage_dir_value = get_required_setting('ICON_STORAGE_DIR')
            if icon_storage_dir_value is None:
                icon_storage_dir = settings.BASE_DIR / 'data' / 'icons'
            elif isinstance(icon_storage_dir_value, Path):
                icon_storage_dir = icon_storage_dir_value
            else:
                icon_storage_dir = Path(icon_storage_dir_value)
            try:
                # Create directory if it doesn't exist
                icon_storage_dir.mkdir(parents=True, exist_ok=True)
                
                # Test write permissions by creating a test file
                test_file = icon_storage_dir / '.startup_test'
                try:
                    test_file.write_text('test')
                    test_file.unlink()
                    logger.info(f"✓ Icon storage directory is writable: {icon_storage_dir}")
                except Exception as e:
                    logger.error(f"✗ Icon storage directory is not writable: {icon_storage_dir} - {e}")
                    all_ok = False
            except Exception as e:
                logger.error(f"✗ Failed to create/access icon storage directory {icon_storage_dir}: {e}")
                all_ok = False
        
        return all_ok
        
    except Exception as e:
        logger.error(f"✗ Directory check failed: {e}")
        return False


def check_frontend_files():
    """
    Check if frontend static files have been built.
    
    Returns:
        bool: True if frontend files exist, False otherwise
    """
    try:
        # Frontend dist directory is relative to BASE_DIR (backend directory)
        frontend_dist = settings.BASE_DIR.parent / 'frontend' / 'dist'
        
        # Check if dist directory exists
        if not frontend_dist.exists():
            logger.error(f"✗ Frontend dist directory not found: {frontend_dist}")
            logger.error("  Please build the frontend: cd frontend && npm run build")
            return False
        
        # Check for index.html (main entry point)
        index_html = frontend_dist / 'index.html'
        if not index_html.exists():
            logger.error(f"✗ Frontend index.html not found: {index_html}")
            logger.error("  Please build the frontend: cd frontend && npm run build")
            return False
        
        # Check for static directory with built assets
        static_dir = frontend_dist / 'static'
        if not static_dir.exists() or not static_dir.is_dir():
            logger.warning(f"⚠ Frontend static directory not found: {static_dir}")
            logger.warning("  Frontend may not be fully built")
        else:
            # Check if static directory has any files
            static_files = list(static_dir.iterdir())
            if not static_files:
                logger.warning(f"⚠ Frontend static directory is empty: {static_dir}")
            else:
                logger.info(f"✓ Frontend static files found ({len(static_files)} items)")
        
        logger.info(f"✓ Frontend files are present: {frontend_dist}")
        return True
        
    except Exception as e:
        logger.error(f"✗ Frontend files check failed: {e}")
        return False


def check_font_glyphs():
    """
    Check if MapLibre font glyphs have been generated.
    
    Returns:
        bool: True if fonts are present, False otherwise
    """
    try:
        # Get assets fonts directory path
        assets_fonts_dir = Path(settings.BASE_DIR) / 'assets' / 'fonts'
        
        # Check if fonts directory exists
        if not assets_fonts_dir.exists():
            logger.error(f"✗ Fonts directory not found: {assets_fonts_dir}")
            logger.error("  Please run: cd src/backend && ./generate-map-fonts.sh")
            return False
        
        if not assets_fonts_dir.is_dir():
            logger.error(f"✗ Fonts path is not a directory: {assets_fonts_dir}")
            return False
        
        # Check for font stack directories
        font_stacks = [d for d in assets_fonts_dir.iterdir() if d.is_dir()]
        if not font_stacks:
            logger.error(f"✗ No font stacks found in: {assets_fonts_dir}")
            logger.error("  Please run: cd src/backend && ./generate-map-fonts.sh")
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
            logger.error(f"✗ No PBF font files found in any font stack")
            logger.error("  Please run: cd src/backend && ./generate-map-fonts.sh")
            return False
        
        # Check for the first range file (0-255.pbf) in at least one common font
        has_base_range = False
        for font_name in common_fonts:
            font_dir = assets_fonts_dir / font_name
            if font_dir.exists() and (font_dir / '0-255.pbf').exists():
                has_base_range = True
                break
        
        if not has_base_range:
            logger.warning(f"⚠ Common font base range (0-255.pbf) not found")
            logger.warning("  Fonts may be incomplete. Consider re-running: cd src/backend && ./generate-map-fonts.sh")
        
        logger.info(f"✓ Font glyphs found: {len(font_stacks)} font stack(s)")
        if found_common_fonts:
            logger.info(f"  Common fonts available: {', '.join(found_common_fonts)}")
        return True
        
    except Exception as e:
        logger.error(f"✗ Font glyphs check failed: {e}")
        return False


def check_togeojson_installation():
    """
    Check if togeojson Node.js converter is installed and available.
    
    Returns:
        bool: True if togeojson is properly installed, False otherwise
    """
    try:
        # Path to togeojson directory relative to BASE_DIR
        togeojson_dir = settings.BASE_DIR / 'geo_lib' / 'processing' / 'togeojson'
        
        # Check if togeojson directory exists
        if not togeojson_dir.exists():
            logger.error(f"✗ togeojson directory not found: {togeojson_dir}")
            logger.error("  Please ensure togeojson is installed in src/backend/geo_lib/processing/togeojson")
            return False
        
        # Check if index.js exists
        index_js = togeojson_dir / 'index.js'
        if not index_js.exists():
            logger.error(f"✗ togeojson index.js not found: {index_js}")
            logger.error("  Please ensure togeojson index.js is present")
            return False
        
        # Check if node_modules exists (indicating npm packages are installed)
        node_modules = togeojson_dir / 'node_modules'
        if not node_modules.exists() or not node_modules.is_dir():
            logger.error(f"✗ togeojson node_modules not found: {node_modules}")
            logger.error("  Please install dependencies: cd src/backend/geo_lib/processing/togeojson && npm install")
            return False
        
        # Check if required dependencies are installed
        required_deps = ['@tmcw/togeojson', '@xmldom/xmldom', 'adm-zip']
        missing_deps = []
        for dep in required_deps:
            # Check if the dependency directory exists in node_modules
            dep_path = node_modules / dep
            if not dep_path.exists():
                missing_deps.append(dep)
        
        if missing_deps:
            logger.error(f"✗ Missing required togeojson dependencies: {', '.join(missing_deps)}")
            logger.error("  Please install dependencies: cd src/backend/geo_lib/processing/togeojson && npm install")
            return False
        
        logger.info(f"✓ togeojson is installed: {togeojson_dir}")
        return True
        
    except Exception as e:
        logger.error(f"✗ togeojson installation check failed: {e}")
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
            logger.error("✗ File type max_size validation failed!")
            logger.error(f"  The following file types have max_size >= 200MB (which will break the database):")
            for file_type_name, max_size_bytes, max_size_mb in invalid_configs:
                logger.error(f"    - {file_type_name}: max_size = {max_size_bytes} bytes ({max_size_mb:.1f}MB)")
            logger.error(f"  All max_size values must be less than 200MB ({MAX_ALLOWED_SIZE_BYTES} bytes)")
            logger.error("  Please fix FILE_TYPE_CONFIGS in geo_lib/processing/file_types.py")
            return False
        
        logger.info("✓ File type max_size validation passed (all values < 200MB)")
        return True
        
    except Exception as e:
        logger.error(f"✗ File type max_size check failed: {e}")
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
            logger.warning(f"⚠ Configuration file not found: {config_path}")
            logger.warning("  Using default configuration values. Create config.yaml for custom settings.")
        else:
            logger.info(f"✓ Configuration file found: {config_path}")
            
    except Exception as e:
        logger.warning(f"⚠ Could not check configuration file: {e}")


def check_secret_key():
    """
    Check if SECRET_KEY is using the default insecure value.
    This is a warning-only check (always warns regardless of DEBUG mode).
    """
    try:
        default_secret = 'django-insecure-f(1zo%f)wm*rl97q0^3!9exd%(s8mz92nagf4q7c2cno&bmyx='
        current_secret = get_required_setting('SECRET_KEY')
        
        if current_secret == default_secret:
            logger.warning("⚠ SECRET_KEY is using the default insecure value!")
            logger.warning("  This is a security risk. Set a secure SECRET_KEY in config.yaml or SECRET_KEY environment variable.")
        else:
            logger.info("✓ SECRET_KEY is configured (not using default)")
            
    except Exception as e:
        logger.warning(f"⚠ Could not check SECRET_KEY: {e}")


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
                logger.warning(f"⚠ MaxMind database file not found: {maxmind_path}")
                logger.warning("  IP geolocation features may not work. This is optional.")
            else:
                logger.info(f"✓ MaxMind database found: {maxmind_path}")
        else:
            logger.info("  MaxMind database path not configured (optional)")
            
    except Exception as e:
        logger.warning(f"⚠ Could not check MaxMind database: {e}")


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
            logger.warning("⚠ Email configuration appears to be using default/unconfigured values:")
            for issue in issues:
                logger.warning(f"  - {issue}")
            logger.warning("  Email features (password reset, notifications) may not work.")
        else:
            logger.info("✓ Email configuration appears to be configured")
            
    except Exception as e:
        logger.warning(f"⚠ Could not check email configuration: {e}")


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
            logger.error("✗ Site configuration missing: SITE_DOMAIN and SITE_NAME must be defined in settings")
            logger.error("  Email confirmation links will not work without proper Site configuration")
            return False
        
        if not hasattr(settings, 'SITE_ID'):
            logger.error("✗ Site configuration missing: SITE_ID must be defined in settings")
            return False
        
        # Check if site.domain is explicitly set (not using default)
        config_loader = get_config_loader()
        default_domain = 'geovault.example.com'
        
        # Check if site.domain is set in the config
        site_domain_config = config_loader.get('site.domain', None)
        if site_domain_config is None or site_domain_config == default_domain:
            logger.error("✗ Site domain is not configured: 'site.domain' must be explicitly set in config.yaml")
            logger.error(f"  Current value: {site_domain_config if site_domain_config else 'not set (using default: ' + default_domain + ')'}")
            logger.error("  Please set 'site.domain' in your config.yaml file to your actual domain name")
            logger.error("  Example: site.domain: mydomain.com")
            return False
        
        site_domain = settings.SITE_DOMAIN
        site_name = settings.SITE_NAME
        site_id = settings.SITE_ID
        
        # Verify we can create/update the Site object
        # This is the same logic used in NoUsernameAccountAdapter.get_email_confirmation_url()
        try:
            site, created = Site.objects.get_or_create(id=site_id)
            
            if created:
                logger.info(f"✓ Created Site object with ID {site_id}")
            
            # Update if needed
            if site.domain != site_domain or site.name != site_name:
                site.domain = site_domain
                site.name = site_name
                site.save()
                logger.info(f"✓ Updated Site object: {site_name} ({site_domain})")
            else:
                logger.info(f"✓ Site configuration is valid: {site_name} ({site_domain})")
            
            # Verify we can retrieve it
            Site.objects.get(id=site_id)
            
            return True
            
        except Exception as e:
            logger.error(f"✗ Failed to create/update Site object: {e}")
            return False
            
    except Exception as e:
        logger.error(f"✗ Site configuration check failed: {e}")
        return False


def cleanup_redis_queues():
    """
    Clean up Redis processing queues, job status data, and old locks on startup.
    
    This clears any stale queues from previous server instances and removes
    old processing locks (for migration from lock-based to queue-based system).
    Also clears job status data to ensure a clean slate on server restart.
    """
    try:
        redis_client = get_redis_connection()
        
        # Find all processing queue keys
        queue_keys = redis_client.keys('processing_queue:user:*')
        
        # Find all old processing lock keys (for migration)
        lock_keys = redis_client.keys('processing_lock:*')
        
        # Find all job status keys
        job_keys = redis_client.keys('job:*')
        
        # Find all user jobs set keys
        user_jobs_keys = redis_client.keys('user_jobs:*')
        
        total_deleted = 0
        
        if queue_keys:
            # Delete all processing queues
            deleted_count = redis_client.delete(*queue_keys)
            total_deleted += deleted_count
            logger.info(f"✓ Cleaned up {deleted_count} stale Redis processing queue(s)")
        else:
            logger.info("✓ No stale Redis processing queues found")
        
        if lock_keys:
            # Delete all old processing locks (migration cleanup)
            deleted_count = redis_client.delete(*lock_keys)
            total_deleted += deleted_count
            logger.info(f"✓ Cleaned up {deleted_count} old Redis processing lock(s)")
        else:
            logger.info("✓ No old Redis processing locks found")
        
        if job_keys:
            # Delete all job status data
            deleted_count = redis_client.delete(*job_keys)
            total_deleted += deleted_count
            logger.info(f"✓ Cleaned up {deleted_count} stale Redis job status record(s)")
        else:
            logger.info("✓ No stale Redis job status records found")
        
        if user_jobs_keys:
            # Delete all user jobs sets
            deleted_count = redis_client.delete(*user_jobs_keys)
            total_deleted += deleted_count
            logger.info(f"✓ Cleaned up {deleted_count} stale Redis user jobs set(s)")
        else:
            logger.info("✓ No stale Redis user jobs sets found")
        
        if total_deleted == 0:
            logger.info("✓ Redis is clean (no stale queues, jobs, or locks)")
        
        return True
    except Exception as e:
        logger.warning(f"⚠ Failed to cleanup Redis queues: {e}")
        # This is not critical
        return True


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
        logger.info("✓ Cleared Redis cache (ensures fresh data on startup)")
        
        return True
    except Exception as e:
        logger.warning(f"⚠ Failed to clear Redis cache: {e}")
        # This is not critical - server can still start
        return True


def preload_ski_resorts():
    """
    Preload the ski resorts database during startup.
    
    This ensures the ski resorts are loaded once during startup rather than
    during the first reverse geocoding operation. This avoids race conditions
    in multi-threaded processing and provides better startup diagnostics.
    
    Returns:
        bool: True if ski resorts loaded successfully, False otherwise
    """
    ski_resorts = load_ski_resorts()

    if ski_resorts:
        logger.info(f"✓ Preloaded {len(ski_resorts)} ski resorts for reverse geocoding")
    else:
        logger.warning("⚠ Ski resorts database is empty or failed to load")


def run_startup_checks():
    """
    Run all startup checks and exit if any fail.
    
    This function will:
    1. Check Python version (requires 3.12)
    2. Check database connection
    3. Verify PostGIS installation
    4. Check required tables exist
    5. Verify spatial table configuration
    6. Check Redis connection
    7. Check writable directories (create if needed)
    8. Check frontend files are built
    9. Check togeojson installation
    10. Validate file type max_size values (< 200MB)
    11. Verify Site configuration (for email confirmation URLs)
    12. Clean up stale Redis processing queues and job status data
    13. Clear Redis cache (ensures fresh data on startup)
    14. Preload ski resorts database (for reverse geocoding)
    
    Warning checks (don't fail startup):
    - Configuration file
    - Secret key security
    - MaxMind database
    - Email configuration
    
    Raises:
        SystemExit: If any critical check fails
    """
    logger.info("Starting GeoVault startup checks...")
    
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
        ("togeojson Installation", check_togeojson_installation),
        ("File Type Max Size", check_file_type_max_size),
        ("Site Configuration", check_site_configuration),
    ]
    
    failed_checks = []
    
    for check_name, check_func in critical_checks:
        logger.info(f"Running {check_name} check...")
        if not check_func():
            failed_checks.append(check_name)
    
    # Run warning checks (don't fail startup, but log warnings)
    logger.info("Running warning checks...")
    check_config_file()
    check_secret_key()
    check_maxmind_database()
    check_email_config()
    
    # Cleanup stale Redis queues and locks (non-critical)
    logger.info("Cleaning up stale Redis queues...")
    cleanup_redis_queues()
    
    # Clear Redis cache on startup (non-critical)
    logger.info("Clearing Redis cache...")
    clear_redis_cache()
    
    # Preload ski resorts database (non-critical, improves first-time performance)
    logger.info("Preloading ski resorts database...")
    preload_ski_resorts()
    
    if failed_checks:
        logger.error("=" * 60)
        logger.error("STARTUP CHECKS FAILED!")
        logger.error("=" * 60)
        logger.error("The following checks failed:")
        for check in failed_checks:
            logger.error(f"  - {check}")
        logger.error("")
        logger.error("Please fix the issues above before starting the server.")
        logger.error("Common solutions:")
        logger.error("  - Install Python 3.12 and ensure it's being used")
        logger.error("  - Ensure PostgreSQL is running")
        logger.error("  - Install PostGIS extension: CREATE EXTENSION postgis;")
        logger.error("  - Run migrations: python manage.py migrate")
        logger.error("  - Ensure Redis is running and accessible")
        logger.error("  - Build frontend: cd frontend && npm run build")
        logger.error("  - Generate fonts: cd src/backend && ./generate-map-fonts.sh")
        logger.error("  - Install togeojson: cd src/backend/geo_lib/processing/togeojson && npm install")
        logger.error("  - Ensure directories are writable")
        logger.error("=" * 60)
        sys.exit(1)
    else:
        logger.info("=" * 60)
        logger.info("✓ ALL STARTUP CHECKS PASSED!")
        logger.info("✓ GeoVault is ready to start")
        logger.info("=" * 60)