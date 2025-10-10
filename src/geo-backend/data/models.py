import uuid

from django.contrib.auth import get_user_model
from django.contrib.gis.db import models
from django.db import models as django_models


class ImportQueue(django_models.Model):
    id = django_models.AutoField(primary_key=True)
    user = django_models.ForeignKey(get_user_model(), on_delete=django_models.CASCADE)
    imported = django_models.BooleanField(default=False)
    unparsable = django_models.BooleanField(default=False, help_text="True if the file failed to parse and should not be retried")
    geofeatures = django_models.JSONField(default=list)
    duplicate_features = django_models.JSONField(default=list, help_text="Features that are duplicates of existing features in the feature store")
    original_filename = django_models.TextField()
    raw_kml = django_models.TextField()
    geojson_hash = django_models.CharField(max_length=64, null=True, blank=True, help_text="SHA-256 hash of the normalized GeoJSON FeatureCollection content")
    log_id = django_models.UUIDField(default=uuid.uuid4, unique=True, help_text="UUID to group related log entries", null=True)
    timestamp = django_models.DateTimeField(auto_now_add=True)

    class Meta:
        indexes = [
            # Compound index for user-specific import queue queries
            django_models.Index(fields=['user', 'imported', 'timestamp'], name='import_user_imported_time'),
            # Index for geojson hash lookups
            django_models.Index(fields=['user', 'geojson_hash'], name='import_user_geojson_hash'),
            # Index for log grouping
            django_models.Index(fields=['log_id', 'timestamp'], name='import_log_id_time'),
        ]


class FeatureStore(models.Model):
    id = models.AutoField(primary_key=True)
    user = models.ForeignKey(get_user_model(), on_delete=models.CASCADE)
    source = models.ForeignKey(ImportQueue, on_delete=models.SET_NULL, null=True)
    geojson = models.JSONField(null=False)
    geojson_hash = models.CharField(max_length=64, unique=True, null=True, blank=True, help_text="SHA-256 hash of the feature's GeoJSON content")
    geometry = models.GeometryField(null=True, blank=True, dim=3)  # Spatial field for efficient queries, supports 3D
    timestamp = models.DateTimeField(auto_now_add=True)

    class Meta:
        indexes = [
            # Original indexes
            models.Index(fields=['user', 'timestamp']),
            models.Index(fields=['geometry']),  # Spatial index
            models.Index(fields=['geojson_hash']),  # Index for hash-based lookups
            
            # NEW COMPOUND INDEXES FOR OPTIMIZED QUERIES (with short names)
            
            # 1. Most critical: User + Spatial queries (used in get_geojson_data)
            # This is the most common query pattern: user_id + geometry__intersects
            models.Index(fields=['user', 'geometry'], name='fs_user_geom'),
            
            # 2. User + Hash lookups (used in duplicate detection and hash-based queries)
            # Optimizes queries like: user_id=user_id, geojson_hash=hash
            models.Index(fields=['user', 'geojson_hash'], name='fs_user_hash'),
            
            # 3. User + Timestamp for chronological queries
            # Optimizes queries like: user_id=user_id ORDER BY timestamp
            models.Index(fields=['user', 'timestamp'], name='fs_user_time'),
            
            # 4. User + Source for import tracking
            # Optimizes queries like: user_id=user_id, source=import_queue
            models.Index(fields=['user', 'source'], name='fs_user_source'),
            
            # 5. Spatial + Timestamp for time-based spatial queries
            # Optimizes queries that combine spatial and temporal filtering
            models.Index(fields=['geometry', 'timestamp'], name='fs_geom_time'),
            
            # 6. Hash + Timestamp for hash-based chronological queries
            # Optimizes duplicate detection with temporal ordering
            models.Index(fields=['geojson_hash', 'timestamp'], name='fs_hash_time'),
        ]


class DatabaseLogging(django_models.Model):
    id = django_models.AutoField(primary_key=True)
    user = django_models.ForeignKey(get_user_model(), on_delete=django_models.CASCADE)
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
