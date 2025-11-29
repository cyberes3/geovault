#!/usr/bin/env python
"""
Prepare test database by dropping all tables and running migrations.
This ensures a clean database state for each test run.
"""
import os
import sys
from pathlib import Path
import psycopg2

# Get paths
script_dir = Path(__file__).parent
backend_dir = script_dir.parent / 'backend'

# Add backend to path
sys.path.insert(0, str(backend_dir))

# Set up Django environment
os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'website.settings')

import django
django.setup()

from django.conf import settings
from django.core.management import call_command
from django.db import connection, connections

def drop_all_tables():
    """Drop all tables in the test database."""
    # Close all existing database connections first
    connections.close_all()
    
    # Force use of test database settings
    if 'TEST' in settings.DATABASES['default']:
        test_config = settings.DATABASES['default']['TEST'].copy()
        settings.DATABASES['default'].update({
            'NAME': test_config['NAME'],
            'USER': test_config['USER'],
            'PASSWORD': test_config['PASSWORD'],
            'HOST': test_config['HOST'],
            'PORT': test_config['PORT'],
        })
    
    db_config = settings.DATABASES['default']
    
    try:
        # Connect to the test database
        conn = psycopg2.connect(
            host=db_config['HOST'],
            port=db_config['PORT'],
            database=db_config['NAME'],
            user=db_config['USER'],
            password=db_config['PASSWORD']
        )
        conn.autocommit = True
        cursor = conn.cursor()
        
        # Drop all tables in the public schema, excluding PostGIS system tables
        cursor.execute("""
            DO $$ DECLARE
                r RECORD;
            BEGIN
                FOR r IN (
                    SELECT tablename 
                    FROM pg_tables 
                    WHERE schemaname = 'public' 
                    AND tablename NOT IN ('spatial_ref_sys', 'geometry_columns', 'geography_columns')
                ) LOOP
                    EXECUTE 'DROP TABLE IF EXISTS ' || quote_ident(r.tablename) || ' CASCADE';
                END LOOP;
            END $$;
        """)
        
        print(f"✓ Dropped all tables in database {db_config['NAME']}")
        cursor.close()
        conn.close()
        return True
        
    except Exception as e:
        print(f"✗ Error dropping tables: {e}")
        return False

def run_migrations():
    """Run migrations to recreate tables as the test user."""
    # Close all existing database connections
    connections.close_all()
    
    # Force use of test database settings - update before any connection
    if 'TEST' in settings.DATABASES['default']:
        test_config = settings.DATABASES['default']['TEST'].copy()
        # Update the default database config to use test credentials
        settings.DATABASES['default'].update({
            'NAME': test_config['NAME'],
            'USER': test_config['USER'],
            'PASSWORD': test_config['PASSWORD'],
            'HOST': test_config['HOST'],
            'PORT': test_config['PORT'],
        })
    
    try:
        print(f"Running migrations as user: {settings.DATABASES['default']['USER']}")
        call_command('migrate', '--noinput', verbosity=0)
        print("✓ Migrations applied")
        return True
    except Exception as e:
        print(f"✗ Error running migrations: {e}")
        return False

if __name__ == '__main__':
    # Drop all tables
    if not drop_all_tables():
        sys.exit(1)
    
    # Run migrations to recreate tables
    if not run_migrations():
        sys.exit(1)
    
    print("✓ Test database prepared successfully")
    sys.exit(0)

