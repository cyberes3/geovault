"""
Startup checks for the GeoServer Django application.

This module performs essential checks when the server starts up:
1. Database connection
2. Required tables exist
3. PostGIS extension is installed
"""

import sys
from django.db import connection
from django.core.exceptions import ImproperlyConfigured
from django.conf import settings
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


def run_startup_checks():
    """
    Run all startup checks and exit if any fail.
    
    This function will:
    1. Check database connection
    2. Verify PostGIS installation
    3. Check required tables exist
    4. Verify spatial table configuration
    
    Raises:
        SystemExit: If any check fails
    """
    logger.info("Starting GeoServer startup checks...")
    
    checks = [
        ("Database Connection", check_database_connection),
        ("PostGIS Installation", check_postgis_installation),
        ("Required Tables", check_required_tables),
        ("Spatial Tables", check_spatial_tables),
    ]
    
    failed_checks = []
    
    for check_name, check_func in checks:
        logger.info(f"Running {check_name} check...")
        if not check_func():
            failed_checks.append(check_name)
    
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
        logger.error("=" * 60)
        sys.exit(1)
    else:
        logger.info("=" * 60)
        logger.info("✓ ALL STARTUP CHECKS PASSED!")
        logger.info("✓ GeoServer is ready to start")
        logger.info("=" * 60)