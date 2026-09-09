import uuid

from django.contrib.auth import get_user_model
from django.db import models
from django.utils import timezone

User = get_user_model()


VISIBILITY_PRIVATE = "private"
VISIBILITY_SHARED = "shared"
VISIBILITY_PUBLIC = "public"
VISIBILITY_CHOICES = [
    (VISIBILITY_PRIVATE, "Private"),
    (VISIBILITY_SHARED, "Shared with specific users"),
    (VISIBILITY_PUBLIC, "Public"),
]


class LiveTrack(models.Model):
    """
    A single live track: metadata plus point_rows (one LiveTrackPoint per coordinate).
    Identified for ingress by Basic Auth (tracker_secret).
    """

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    tracker_secret = models.CharField(max_length=64, unique=True)
    hauk_password = models.CharField(max_length=64, default="", blank=True)
    name = models.CharField(max_length=255)
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name="live_tracks")
    settings = models.JSONField(default=dict)
    visibility = models.CharField(
        max_length=20,
        choices=VISIBILITY_CHOICES,
        default=VISIBILITY_PRIVATE,
    )
    share_params_with_recipients = models.BooleanField(default=False)
    share_params_with_world = models.BooleanField(default=False)
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


class LiveTrackPoint(models.Model):
    """One live point. Wire projection is coordinates[i] + point_params[i] in (timestamp_ms, seq) order."""

    track = models.ForeignKey(LiveTrack, on_delete=models.CASCADE, related_name="point_rows")
    seq = models.PositiveIntegerField()
    timestamp_ms = models.BigIntegerField()
    lon = models.FloatField()
    lat = models.FloatField()
    params = models.JSONField(default=dict)

    class Meta:
        app_label = "live_track"
        db_table = "live_track_point"
        constraints = [
            models.UniqueConstraint(fields=["track", "seq"], name="live_track_point_unique_track_seq"),
        ]
        indexes = [
            models.Index(fields=["track", "timestamp_ms", "seq"], name="live_track_point_latest_idx"),
        ]
        ordering = ["timestamp_ms", "seq"]


class LiveTrackSubscription(models.Model):
    """User has added this track to their list (own or someone else's)."""

    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name="live_track_subscriptions")
    track = models.ForeignKey(LiveTrack, on_delete=models.CASCADE, related_name="subscribers")
    created_at = models.DateTimeField(default=timezone.now)

    class Meta:
        app_label = "live_track"
        constraints = [
            models.UniqueConstraint(fields=["user", "track"], name="live_track_subscription_unique")
        ]


class LiveTrackGroup(models.Model):
    """Named group of trackers; owner can add tracks and share via visibility/shared_with."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=255)
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name="live_track_groups")
    hidden = models.BooleanField(default=False)
    visibility = models.CharField(
        max_length=20,
        choices=VISIBILITY_CHOICES,
        default=VISIBILITY_PRIVATE,
    )
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        app_label = "live_track"
        constraints = [
            models.UniqueConstraint(fields=["user", "name"], name="live_track_group_unique_name_per_user")
        ]
        ordering = ["name"]


class LiveTrackGroupSubscription(models.Model):
    """User has explicitly accepted a shared group."""

    user = models.ForeignKey(
        User,
        on_delete=models.CASCADE,
        related_name="live_track_group_subscriptions",
    )
    group = models.ForeignKey(
        LiveTrackGroup,
        on_delete=models.CASCADE,
        related_name="accepted_subscriptions",
    )
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        app_label = "live_track"
        constraints = [
            models.UniqueConstraint(fields=["user", "group"], name="live_track_group_subscription_unique")
        ]


class LiveTrackGroupMember(models.Model):
    """Which tracks are in which group."""

    group = models.ForeignKey(LiveTrackGroup, on_delete=models.CASCADE, related_name="track_members")
    track = models.ForeignKey(LiveTrack, on_delete=models.CASCADE, related_name="group_memberships")

    class Meta:
        app_label = "live_track"
        constraints = [
            models.UniqueConstraint(fields=["group", "track"], name="live_track_group_member_unique")
        ]


class LiveTrackMapVisibilityPrefs(models.Model):
    """Per-user preference for which tracks/groups are hidden on the map (eye toggle)."""

    user = models.OneToOneField(User, on_delete=models.CASCADE, related_name="live_track_map_visibility_prefs")
    hidden_track_ids = models.JSONField(default=list)  # list of UUID strings
    hidden_group_ids = models.JSONField(default=list)  # list of UUID strings

    class Meta:
        app_label = "live_track"
