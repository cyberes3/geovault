# Migration to migrate data from old core api_caltopouser table to extension table
# and drop the old table

from django.db import migrations


def migrate_caltopo_data(apps, schema_editor):
    """
    Migrate data from old api_caltopouser table to new caltopo_extension_caltopouser table.
    
    This migration:
    1. Checks if the old table exists
    2. Copies all data from old table to new table (if old table exists)
    3. Drops the old table
    """
    db_alias = schema_editor.connection.alias
    
    # Check if old table exists
    with schema_editor.connection.cursor() as cursor:
        cursor.execute("""
            SELECT EXISTS (
                SELECT FROM information_schema.tables 
                WHERE table_schema = 'public' 
                AND table_name = 'api_caltopouser'
            );
        """)
        old_table_exists = cursor.fetchone()[0]
    
    if not old_table_exists:
        # Old table doesn't exist, nothing to migrate
        return
    
    # Migrate data from old table to new table
    # Use raw SQL to copy data, handling the case where records might already exist
    with schema_editor.connection.cursor() as cursor:
        # Insert data from old table, using ON CONFLICT to handle duplicates
        # (in case migration is run multiple times)
        cursor.execute("""
            INSERT INTO caltopo_extension_caltopouser (
                user_id, account_id, credential_id, credential_key, 
                imported_features, last_synced, created_at, updated_at
            )
            SELECT 
                user_id, account_id, credential_id, credential_key,
                imported_features, last_synced, created_at, updated_at
            FROM api_caltopouser
            ON CONFLICT (user_id) DO NOTHING;
        """)
    
    # Drop the old table
    with schema_editor.connection.cursor() as cursor:
        cursor.execute("DROP TABLE IF EXISTS api_caltopouser CASCADE;")


def reverse_migrate_caltopo_data(apps, schema_editor):
    """
    Reverse migration: copy data back to old table (if needed for rollback).
    
    Note: This won't recreate the old table structure, but will copy data
    if the old table still exists for some reason.
    """
    db_alias = schema_editor.connection.alias
    
    # Check if old table exists
    with schema_editor.connection.cursor() as cursor:
        cursor.execute("""
            SELECT EXISTS (
                SELECT FROM information_schema.tables 
                WHERE table_schema = 'public' 
                AND table_name = 'api_caltopouser'
            );
        """)
        old_table_exists = cursor.fetchone()[0]
    
    if old_table_exists:
        # If old table exists, copy data back
        with schema_editor.connection.cursor() as cursor:
            cursor.execute("""
                INSERT INTO api_caltopouser (
                    user_id, account_id, credential_id, credential_key,
                    imported_features, last_synced, created_at, updated_at
                )
                SELECT 
                    user_id, account_id, credential_id, credential_key,
                    imported_features, last_synced, created_at, updated_at
                FROM caltopo_extension_caltopouser
                ON CONFLICT (user_id) DO NOTHING;
            """)


class Migration(migrations.Migration):

    dependencies = [
        ('caltopo_extension', '0001_initial'),
    ]

    operations = [
        migrations.RunPython(
            migrate_caltopo_data,
            reverse_code=reverse_migrate_caltopo_data,
        ),
    ]
