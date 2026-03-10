import uuid

from django.contrib.auth import get_user_model
from django.db import models

User = get_user_model()


VISIBILITY_PRIVATE = "private"
VISIBILITY_SHARED = "shared"
VISIBILITY_PUBLIC = "public"
VISIBILITY_CHOICES = [
    (VISIBILITY_PRIVATE, "Private"),
    (VISIBILITY_SHARED, "Shared with specific users"),
    (VISIBILITY_PUBLIC, "Public (all authenticated users)"),
]


class LiveTrack(models.Model):
    """
    A single live track: metadata plus geometry (LineString with [lon, lat, timestamp_ms] per point)
    and point_params (one object per coordinate). Identified for ingress by Basic Auth (tracker_secret).
    """

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    tracker_secret = models.CharField(max_length=64, unique=True)
    name = models.CharField(max_length=255)
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name="live_tracks")
    settings = models.JSONField(default=dict)
    visibility = models.CharField(
        max_length=20,
        choices=VISIBILITY_CHOICES,
        default=VISIBILITY_PRIVATE,
    )
    share_params_with_recipients = models.BooleanField(default=False)
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


class LiveTrackShare(models.Model):
    """Direct share with a user; only meaningful when track visibility is 'shared'."""

    track = models.ForeignKey(LiveTrack, on_delete=models.CASCADE, related_name="share_entries")
    shared_with = models.ForeignKey(User, on_delete=models.CASCADE, related_name="live_track_shares_received")

    class Meta:
        app_label = "live_track"
        constraints = [
            models.UniqueConstraint(fields=["track", "shared_with"], name="live_track_share_unique")
        ]


class LiveTrackPublicShare(models.Model):
    """World (unauthenticated) share link for a track; one per track. When enabled, anyone with the URL can view the track (read-only). Distinct from visibility=public (all authenticated users)."""

    share_id = models.CharField(max_length=36, unique=True, db_index=True)
    track = models.OneToOneField(LiveTrack, on_delete=models.CASCADE, related_name="public_share")
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        app_label = "live_track"


class LiveTrackSubscription(models.Model):
    """User has added this track to their list (own or someone else's)."""

    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name="live_track_subscriptions")
    track = models.ForeignKey(LiveTrack, on_delete=models.CASCADE, related_name="subscribers")

    class Meta:
        app_label = "live_track"
        constraints = [
            models.UniqueConstraint(fields=["user", "track"], name="live_track_subscription_unique")
        ]


class LiveTrackGroup(models.Model):
    """Named group of trackers; owner can add tracks and members."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=255)
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name="live_track_groups")
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        app_label = "live_track"
        constraints = [
            models.UniqueConstraint(fields=["user", "name"], name="live_track_group_unique_name_per_user")
        ]
        ordering = ["name"]


class LiveTrackGroupMember(models.Model):
    """Which tracks are in which group."""

    group = models.ForeignKey(LiveTrackGroup, on_delete=models.CASCADE, related_name="track_members")
    track = models.ForeignKey(LiveTrack, on_delete=models.CASCADE, related_name="group_memberships")

    class Meta:
        app_label = "live_track"
        constraints = [
            models.UniqueConstraint(fields=["group", "track"], name="live_track_group_member_unique")
        ]


class LiveTrackGroupMembership(models.Model):
    """Users who are in the group (besides the owner); can leave."""

    group = models.ForeignKey(LiveTrackGroup, on_delete=models.CASCADE, related_name="user_members")
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name="live_track_group_memberships")

    class Meta:
        app_label = "live_track"
        constraints = [
            models.UniqueConstraint(fields=["group", "user"], name="live_track_group_membership_unique")
        ]
