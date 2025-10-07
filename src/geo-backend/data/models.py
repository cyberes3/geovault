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
    raw_kml_hash = django_models.CharField(max_length=64, unique=True)
    log_id = django_models.UUIDField(default=uuid.uuid4, unique=True, help_text="UUID to group related log entries", null=True)
    timestamp = django_models.DateTimeField(auto_now_add=True)


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
            models.Index(fields=['user', 'timestamp']),
            models.Index(fields=['geometry']),  # Spatial index
            models.Index(fields=['geojson_hash']),  # Index for hash-based lookups
        ]


class DatabaseLogging(django_models.Model):
    id = django_models.AutoField(primary_key=True)
    user = django_models.ForeignKey(get_user_model(), on_delete=django_models.CASCADE)
    level = django_models.IntegerField()
    text = django_models.TextField()
    source = django_models.CharField(max_length=64)
    attributes = django_models.JSONField(default=dict, help_text="Key:value pairs for arbitrary attributes")
    timestamp = django_models.DateTimeField()

    class Meta:
        indexes = [
            models.Index(fields=['user', 'timestamp']),
            models.Index(fields=['source']),
            models.Index(fields=['level']),
        ]
