import uuid

from django.contrib.auth import get_user_model
from django.db import models

User = get_user_model()


class LiveTrack(models.Model):
    """
    A single live track: metadata plus geometry (LineString with [lon, lat, timestamp_ms] per point)
    and point_params (one object per coordinate). Identified for ingress by Basic Auth (tracker_secret).
    """

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    tracker_secret = models.CharField(max_length=64, unique=True)
    name = models.CharField(max_length=255)
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name="live_tracks")
    color = models.CharField(max_length=7, default="#3388ff")
    geometry = models.JSONField(default=dict)
    point_params = models.JSONField(default=list)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        app_label = "live_track"
        constraints = [
            models.UniqueConstraint(fields=["user", "name"], name="live_track_unique_name_per_user")
        ]
        ordering = ["name"]

    def __str__(self):
        return f"LiveTrack({self.name})"
