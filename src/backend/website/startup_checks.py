"""
Startup checks for the GeoVault Django application.

This module performs essential checks when the server starts up:
1. Database connection
2. Required tables exist
3. PostGIS extension is installed
4. Redis connection
5. Writable directories (tile cache, icon storage)
6. Frontend static files are built
7. togeojson Node.js converter is installed

Warning checks (don't fail startup):
- Configuration file exists
- Secret key security
- MaxMind database availability
- Email configuration
"""

import sys
import os
from pathlib import Path
from django.db import connection
from django.core.exceptions import ImproperlyConfigured
from django.conf import settings
from channels.layers import get_channel_layer
from asgiref.sync import async_to_sync
from geo_lib.logging.console import get_startup_logger

logger = get_startup_logger()


def check_database_connection():
    """
    Check if the database connection is working.
    
    Returns:
        bool: True if connection is successful, False otherwise
    """
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT 1")
            result = cursor.fetchone()
            if result and result[0] == 1:
                logger.info("✓ Database connection successful")
                return True
            else:
                logger.error("✗ Database connection test failed - unexpected result")
                return False
    except Exception as e:
        logger.error(f"✗ Database connection failed: {e}")
        return False


def check_postgis_installation():
    """
    Check if PostGIS extension is installed and available.
    
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
                logger.info("✓ PostGIS extension is installed")
                
                # Check PostGIS version for additional verification
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


def check_redis_connection():
    """
    Check if Redis connection is working.
    
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
            except Exception:
                # Other exceptions (like channel errors) are fine - Redis is reachable
                return True
        
        try:
            result = async_to_sync(test_redis)()
            if result:
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
        if getattr(settings, 'TILE_CACHE_ENABLED', True):
            tile_cache_dir = Path(getattr(settings, 'TILE_CACHE_DIR', '/tmp/geovault-tiles'))
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
        if getattr(settings, 'ICON_PROCESSING_ENABLED', True):
            icon_storage_dir_value = getattr(settings, 'ICON_STORAGE_DIR', None)
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


def check_config_file():
    """
    Check if configuration file exists.
    This is a warning-only check.
    """
    try:
        from website.config_loader import get_config_loader
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
        current_secret = getattr(settings, 'SECRET_KEY', '')
        
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
        maxmind_path = getattr(settings, 'MAXMIND_DATABASE_PATH', None)
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
        email_host = getattr(settings, 'EMAIL_HOST', '')
        email_user = getattr(settings, 'EMAIL_HOST_USER', '')
        email_password = getattr(settings, 'EMAIL_HOST_PASSWORD', '')
        from_email = getattr(settings, 'DEFAULT_FROM_EMAIL', '')
        
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


def run_startup_checks():
    """
    Run all startup checks and exit if any fail.
    
    This function will:
    1. Check database connection
    2. Verify PostGIS installation
    3. Check required tables exist
    4. Verify spatial table configuration
    5. Check Redis connection
    6. Check writable directories (create if needed)
    7. Check frontend files are built
    8. Check togeojson installation
    
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
        ("Database Connection", check_database_connection),
        ("PostGIS Installation", check_postgis_installation),
        ("Required Tables", check_required_tables),
        ("Spatial Tables", check_spatial_tables),
        ("Redis Connection", check_redis_connection),
        ("Writable Directories", check_writable_directories),
        ("Frontend Files", check_frontend_files),
        ("togeojson Installation", check_togeojson_installation),
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
        logger.error("  - Ensure PostgreSQL is running")
        logger.error("  - Install PostGIS extension: CREATE EXTENSION postgis;")
        logger.error("  - Run migrations: python manage.py migrate")
        logger.error("  - Ensure Redis is running and accessible")
        logger.error("  - Build frontend: cd frontend && npm run build")
        logger.error("  - Install togeojson: cd src/backend/geo_lib/processing/togeojson && npm install")
        logger.error("  - Ensure directories are writable")
        logger.error("=" * 60)
        sys.exit(1)
    else:
        logger.info("=" * 60)
        logger.info("✓ ALL STARTUP CHECKS PASSED!")
        logger.info("✓ GeoVault is ready to start")
        logger.info("=" * 60)