"""
Infrastructure/environment checks: Python version, database, PostGIS, required tables,
spatial columns, Redis connectivity, and writable data directories.
"""
import grp
import os
import pwd
import sys
from pathlib import Path

from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer
from django.db import connection

from geo_lib.logging.console import get_tagged_logger
from website.settings_utils import get_required_setting

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


def _check_directory_writable(label: str, directory: Path) -> bool:
    """Create `directory` if needed and verify it's writable; logs success/failure for `label`."""
    try:
        current_uid = os.getuid()
        current_gid = os.getgid()
        current_user = pwd.getpwuid(current_uid).pw_name
        current_group = grp.getgrgid(current_gid).gr_name
        user_group = f"{current_user}:{current_group}"
    except (KeyError, OSError):
        # Fallback if we can't determine user/group
        user_group = "USER:GROUP"

    try:
        directory.mkdir(parents=True, exist_ok=True)
        test_file = directory / '.startup_test'
        try:
            test_file.write_text('test')
            test_file.unlink()
            _logger.info(f"✓ {label} directory is writable: {directory}")
            return True
        except PermissionError:
            _logger.error(f"✗ {label} directory is not writable: {directory}")
            _logger.error(f"  Permission denied. Fix with: sudo chown -R {user_group} {directory}")
            return False
        except Exception as e:
            _logger.error(f"✗ {label} directory is not writable: {directory} - {e}")
            return False
    except PermissionError:
        _logger.error(f"✗ Failed to create/access {label.lower()} directory {directory}")
        _logger.error(f"  Permission denied. Fix with: sudo chown -R {user_group} {directory}")
        return False
    except Exception as e:
        _logger.error(f"✗ Failed to create/access {label.lower()} directory {directory}: {e}")
        return False


def check_writable_directories():
    """
    Check if required directories (tile cache, icon storage) exist and are writable.
    Creates directories if they don't exist. Skips a directory when its feature is disabled.

    Returns:
        bool: True if all enabled directories are writable, False otherwise
    """
    try:
        directories = [
            ("Tile cache", get_required_setting('TILE_CACHE_DIR'), get_required_setting('TILE_CACHE_ENABLED')),
            ("Icon storage", get_required_setting('ICON_STORAGE_DIR'), get_required_setting('ICON_PROCESSING_ENABLED')),
        ]

        all_ok = True
        for label, directory, enabled in directories:
            if not enabled:
                _logger.info(f"  {label} directory check skipped ({label.lower()} is disabled)")
                continue
            if not _check_directory_writable(label, Path(directory)):
                all_ok = False
        return all_ok

    except Exception as e:
        _logger.error(f"✗ Directory check failed: {e}")
        return False
