from django.contrib.auth import get_user_model
from django.db import models


class ImportQueue(models.Model):
    id = models.AutoField(primary_key=True)
    user = models.ForeignKey(get_user_model(), on_delete=models.CASCADE)
    imported = models.BooleanField(default=False)
    geofeatures = models.JSONField(default=list)
    original_filename = models.TextField()
    raw_kml = models.TextField()
    raw_kml_hash = models.CharField(max_length=64, unique=True)
    log = models.JSONField(default=list)
    timestamp = models.DateTimeField(auto_now_add=True)


class FeatureStore(models.Model):
    id = models.AutoField(primary_key=True)
    user = models.ForeignKey(get_user_model(), on_delete=models.CASCADE)
    source = models.ForeignKey(ImportQueue, on_delete=models.SET_NULL, null=True)
    geojson = models.JSONField(null=False)
    timestamp = models.DateTimeField(auto_now_add=True)


class GeoLog(models.Model):
    id = models.AutoField(primary_key=True)
    user = models.ForeignKey(get_user_model(), on_delete=models.CASCADE)
    level = models.IntegerField()
    text = models.TextField()
    source = models.CharField(max_length=64)
    type = models.CharField(max_length=64)
    timestamp = models.DateTimeField(auto_now_add=True)
