from django.db import models


class PlaceMetadata(models.Model):
    """Extension table: per-place metadata for sort (updated_at, last_navigated_at)."""

    feature = models.OneToOneField(
        'api.FeatureStore',
        on_delete=models.CASCADE,
        primary_key=True,
        related_name='place_metadata',
    )
    updated_at = models.DateTimeField(null=True, blank=True)
    last_navigated_at = models.DateTimeField(null=True, blank=True)
