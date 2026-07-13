"""Runs every startup check and startup operation, in order, when the server boots."""
import sys

from geo_lib.logging.console import get_tagged_logger
from website.startup_checks.assets import check_font_glyphs, check_frontend_files
from website.startup_checks.celery import check_celery_beat, check_celery_worker
from website.startup_checks.config import (
    check_config_file,
    check_email_config,
    check_file_type_max_size,
    check_maxmind_database,
    check_site_configuration,
    check_social_preview_tilesource,
)
from website.startup_checks.environment import (
    check_database_connection,
    check_postgis_installation,
    check_python_version,
    check_redis_connection,
    check_required_tables,
    check_spatial_tables,
    check_writable_directories,
)
from website.startup_operations import clear_default_cache, recover_interrupted_jobs

_logger = get_tagged_logger('startup')


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
    9. Check font glyphs are generated
    10. Validate the social-preview tile source
    11. Validate file type max_size values (< 200MB)
    12. Verify Site configuration (for email confirmation URLs)
    13. Verify Celery worker and beat are reachable

    Then, regardless of the above, run non-critical startup operations:
    14. Clear the default cache (ensures fresh data on startup)
    15. Recover interrupted jobs (redispatch jobs that were processing when server stopped)

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

    # Clear default cache on startup (non-critical)
    _logger.info("Clearing default cache...")
    clear_default_cache()

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
