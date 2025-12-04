import uuid

from django.conf import settings
from django.contrib.gis.db import models
from django.contrib.postgres.indexes import GinIndex, GistIndex
from django.db import models as django_models


class ImportQueue(django_models.Model):
    id = django_models.AutoField(primary_key=True)
    user = django_models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=django_models.CASCADE)
    imported = django_models.BooleanField(default=False)
    unparsable = django_models.BooleanField(default=False, help_text="True if the file failed to parse and should not be retried")
    geofeatures = django_models.JSONField(default=list)
    duplicate_features = django_models.JSONField(default=list, help_text="Features that are duplicates of existing features in the feature store")
    original_filename = django_models.TextField()
    raw_file = django_models.TextField(help_text="Raw file content (KML, KMZ, GPX, etc.)")
    file_hash = django_models.CharField(max_length=64, null=True, blank=True, help_text="SHA-256 hash of the raw uploaded file content (entire file, not individual features)")
    log_id = django_models.UUIDField(default=uuid.uuid4, unique=True, help_text="UUID to group related log entries", null=True)
    replacement = django_models.IntegerField(null=True, blank=True, help_text="ID of the existing feature being updated with this replacement upload")
    bulk_operations = django_models.JSONField(default=dict, null=True, blank=True, help_text="Bulk operations (tags, styling) to apply during import")
    skipped_feature_ids = django_models.JSONField(default=list, help_text="List of feature IDs that are skipped by the user")
    timestamp = django_models.DateTimeField(auto_now_add=True)

    class Meta:
        indexes = [
            # Compound index for user-specific import queue queries
            django_models.Index(fields=['user', 'imported', 'timestamp'], name='import_user_imported_time'),
            # Index for file hash lookups (raw file content hash for duplicate detection)
            django_models.Index(fields=['user', 'file_hash'], name='import_user_file_hash'),
            # Index for log grouping
            django_models.Index(fields=['log_id', 'timestamp'], name='import_log_id_time'),
        ]


class FeatureStore(models.Model):
    id = models.AutoField(primary_key=True)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE)
    source = models.ForeignKey(ImportQueue, on_delete=models.SET_NULL, null=True)
    geojson = models.JSONField(null=False)
    geojson_hash = models.CharField(max_length=64, null=True, blank=True, help_text="SHA-256 hash of this individual feature's GeoJSON content")
    geometry = models.GeometryField(null=True, blank=True, dim=3)  # Spatial field for efficient queries, supports 3D
    timestamp = models.DateTimeField(auto_now_add=True)

    class Meta:
        indexes = [
            # Original indexes
            models.Index(fields=['user', 'timestamp']),
            GistIndex(fields=['geometry'], name='featurestore_geometry_idx'),  # GIST spatial index
            models.Index(fields=['geojson_hash']),  # Index for hash-based lookups
            
            # NEW COMPOUND INDEXES FOR OPTIMIZED QUERIES (with short names)
            # NOTE: Removed compound indexes that include geometry fields to avoid PostgreSQL
            # btree index size limits. Geometry fields use GiST spatial indexes instead.
            
            # 1. User + Hash lookups (used in duplicate detection and hash-based queries)
            # Optimizes queries like: user_id=user_id, geojson_hash=hash
            models.Index(fields=['user', 'geojson_hash'], name='fs_user_hash'),
            
            # 2. User + Timestamp for chronological queries
            # Optimizes queries like: user_id=user_id ORDER BY timestamp
            models.Index(fields=['user', 'timestamp'], name='fs_user_time'),
            
            # 3. User + Source for import tracking
            # Optimizes queries like: user_id=user_id, source=import_queue
            models.Index(fields=['user', 'source'], name='fs_user_source'),
            
            # 4. Hash + Timestamp for hash-based chronological queries
            # Optimizes duplicate detection with temporal ordering
            models.Index(fields=['geojson_hash', 'timestamp'], name='fs_hash_time'),
            
            # 5. GIN index for user tags JSONB array (for efficient containment queries)
            # Optimizes queries like: geojson->'properties'->'tags' @> '["tag_name"]'
            GinIndex(fields=['geojson'], name='fs_tags_gin', opclasses=['jsonb_path_ops']),
            
            # 6. GIN index for system tags JSONB array (for efficient containment queries)
            # Optimizes queries like: geojson->'properties'->'system_tags' @> '["tag_name"]'
            # Note: Using same GIN index as above since jsonb_path_ops covers all JSONB paths
        ]
        constraints = [
            # Unique constraint: Each user can only have one feature with a given hash
            # This prevents race conditions during concurrent imports while allowing
            # different users to have the same features (e.g., public POIs)
            django_models.UniqueConstraint(
                fields=['user', 'geojson_hash'],
                name='unique_user_geojson_hash',
                violation_error_message='Feature with this hash already exists for this user'
            )
        ]


class DatabaseLogging(django_models.Model):
    id = django_models.AutoField(primary_key=True)
    user = django_models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=django_models.CASCADE)
    log_id = django_models.UUIDField(null=True, blank=True, db_index=True, help_text="UUID to group related log entries")
    level = django_models.IntegerField()
    text = django_models.TextField()
    source = django_models.CharField(max_length=64)
    attributes = django_models.JSONField(default=dict, help_text="Key:value pairs for arbitrary attributes")
    timestamp = django_models.DateTimeField()

    class Meta:
        indexes = [
            # Original indexes
            django_models.Index(fields=['user', 'timestamp']),
            django_models.Index(fields=['source']),
            django_models.Index(fields=['level']),
            django_models.Index(fields=['log_id', 'timestamp']),
            
            # NEW COMPOUND INDEXES FOR OPTIMIZED LOGGING QUERIES (with short names)
            
            # 1. User + Level + Timestamp for filtered log queries
            # Optimizes queries like: user_id=user_id, level=ERROR ORDER BY timestamp
            django_models.Index(fields=['user', 'level', 'timestamp'], name='log_user_level_time'),
            
            # 2. User + Source + Timestamp for source-specific log queries
            # Optimizes queries like: user_id=user_id, source='import' ORDER BY timestamp
            django_models.Index(fields=['user', 'source', 'timestamp'], name='log_user_source_time'),
            
            # 3. Log ID + Level for log analysis
            # Optimizes queries like: log_id=uuid, level=ERROR
            django_models.Index(fields=['log_id', 'level'], name='log_logid_level'),
            
            # 4. Source + Level + Timestamp for system-wide log analysis
            # Optimizes queries like: source='import', level=ERROR ORDER BY timestamp
            django_models.Index(fields=['source', 'level', 'timestamp'], name='log_source_level_time'),
        ]


class TagShare(django_models.Model):
    share_id = django_models.CharField(max_length=255, unique=True, db_index=True, help_text="UUID4")
    tag = django_models.CharField(max_length=255, help_text="The tag being shared")
    user = django_models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=django_models.CASCADE)
    created_at = django_models.DateTimeField(auto_now_add=True)
    access_count = django_models.IntegerField(default=0, help_text="Number of times this share has been accessed")
    allow_downloads = django_models.BooleanField(default=False, help_text="Whether viewers can download features as KMZ")

    class Meta:
        indexes = [
            django_models.Index(fields=['user', 'created_at'], name='tagshare_user_created'),
            django_models.Index(fields=['share_id'], name='tagshare_share_id'),
            django_models.Index(fields=['tag', 'user'], name='tagshare_tag_user'),
        ]


class CollectionShare(django_models.Model):
    share_id = django_models.CharField(max_length=255, unique=True, db_index=True, help_text="UUID4 share identifier")
    collection = django_models.ForeignKey('Collection', on_delete=django_models.CASCADE, help_text="The collection being shared")
    user = django_models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=django_models.CASCADE)
    created_at = django_models.DateTimeField(auto_now_add=True)
    access_count = django_models.IntegerField(default=0, help_text="Number of times this share has been accessed")
    include_tags = django_models.BooleanField(default=False, help_text="Whether to include tags in the shared features")
    allow_downloads = django_models.BooleanField(default=False, help_text="Whether viewers can download features as KMZ")

    class Meta:
        indexes = [
            django_models.Index(fields=['user', 'created_at'], name='colshare_user_created'),
            django_models.Index(fields=['share_id'], name='colshare_share_id'),
            django_models.Index(fields=['collection', 'user'], name='colshare_coll_user'),
        ]


class Collection(django_models.Model):
    id = django_models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = django_models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=django_models.CASCADE)
    name = django_models.CharField(max_length=255)
    description = django_models.TextField(blank=True, null=True)
    tags = django_models.JSONField(default=list, help_text="Array of tag strings")
    feature_ids = django_models.JSONField(default=list, help_text="Array of feature IDs")
    created_at = django_models.DateTimeField(auto_now_add=True)
    updated_at = django_models.DateTimeField(auto_now=True)

    class Meta:
        indexes = [
            django_models.Index(fields=['user', 'created_at'], name='collection_user_created'),
        ]


class UserSettings(django_models.Model):
    user = django_models.OneToOneField(settings.AUTH_USER_MODEL, on_delete=django_models.CASCADE, primary_key=True)
    settings = django_models.JSONField(default=dict, help_text="Key-value pairs for user settings")
    hidden_features = django_models.JSONField(default=list, help_text="List of feature IDs hidden on the main map page")
    created_at = django_models.DateTimeField(auto_now_add=True)
    updated_at = django_models.DateTimeField(auto_now=True)

    class Meta:
        indexes = [
            django_models.Index(fields=['user'], name='usersettings_user'),
        ]