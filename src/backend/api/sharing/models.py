from django.conf import settings
from django.db import models
from django.db.models import Q

from geo_lib.sharing.constants import (
    AUDIENCE_AUTHENTICATED,
    AUDIENCE_WORLD,
    DOMAIN_LIVE_TRACK,
    DOMAIN_MAP,
    KIND_COLLECTION,
    KIND_FEATURE,
    KIND_LIVE_TRACK,
    KIND_LIVE_TRACK_GROUP,
    KIND_TAG,
)
from geo_lib.sharing.subject_ref import canonicalize_subject_ref


class ShareLinkQuerySet(models.QuerySet):
    def active(self) -> "ShareLinkQuerySet":
        return self.filter(revoked_at__isnull=True)

    def owned_by(self, user) -> "ShareLinkQuerySet":
        return self.filter(owner=user)

    def for_kind(self, subject_kind: str) -> "ShareLinkQuerySet":
        return self.filter(subject_kind=subject_kind)

    def for_subject(self, subject_kind: str, subject_ref) -> "ShareLinkQuerySet":
        return self.filter(subject_kind=subject_kind, subject_ref=canonicalize_subject_ref(subject_kind, subject_ref))

    def for_feature(self, feature_id: int) -> "ShareLinkQuerySet":
        return self.for_subject(KIND_FEATURE, feature_id)

    def for_track(self, track_id) -> "ShareLinkQuerySet":
        return self.for_subject(KIND_LIVE_TRACK, track_id)

    def for_group(self, group_id) -> "ShareLinkQuerySet":
        return self.for_subject(KIND_LIVE_TRACK_GROUP, group_id)

    def world(self) -> "ShareLinkQuerySet":
        return self.filter(audience=AUDIENCE_WORLD)

    def authenticated(self) -> "ShareLinkQuerySet":
        return self.filter(audience=AUDIENCE_AUTHENTICATED)

    def map_domain(self) -> "ShareLinkQuerySet":
        return self.filter(domain=DOMAIN_MAP)


class ShareLink(models.Model):
    token = models.CharField(max_length=36, unique=True, db_index=True)
    owner = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="share_links",
    )
    domain = models.CharField(max_length=32)
    subject_kind = models.CharField(max_length=32)
    subject_ref = models.JSONField()
    audience = models.CharField(max_length=32)
    capabilities = models.JSONField(default=dict)
    created_at = models.DateTimeField(auto_now_add=True)
    revoked_at = models.DateTimeField(null=True, blank=True)
    access_count = models.IntegerField(default=0)
    last_accessed_at = models.DateTimeField(null=True, blank=True)

    objects = ShareLinkQuerySet.as_manager()

    class Meta:
        app_label = "api"
        indexes = [
            models.Index(fields=["owner", "created_at"], name="sharelink_owner_created"),
            models.Index(fields=["subject_kind", "audience"], name="sharelink_kind_audience"),
            models.Index(fields=["domain", "audience"], name="sharelink_domain_audience"),
        ]
        constraints = [
            models.UniqueConstraint(
                fields=["owner", "subject_kind", "subject_ref"],
                condition=Q(subject_kind=KIND_FEATURE),
                name="sharelink_unique_feature",
            ),
            models.UniqueConstraint(
                fields=["subject_kind", "subject_ref", "audience"],
                condition=Q(subject_kind__in=[KIND_LIVE_TRACK, KIND_LIVE_TRACK_GROUP]),
                name="sharelink_unique_tracker_audience",
            ),
        ]

    def save(self, *args, **kwargs):
        self.token = self.token.lower()
        self.subject_ref = canonicalize_subject_ref(self.subject_kind, self.subject_ref)
        if self.capabilities is None:
            self.capabilities = {}
        super().save(*args, **kwargs)

    @property
    def allow_downloads(self) -> bool:
        return bool(self.capabilities.get("allow_downloads"))

    @property
    def include_tags(self) -> bool:
        return bool(self.capabilities.get("include_tags"))


class ShareGrantQuerySet(models.QuerySet):
    def for_resource(self, resource_kind: str, resource_id: str) -> "ShareGrantQuerySet":
        return self.filter(resource_kind=resource_kind, resource_id=str(resource_id))

    def for_track(self, track_id) -> "ShareGrantQuerySet":
        return self.for_resource(KIND_LIVE_TRACK, str(track_id))

    def for_group(self, group_id) -> "ShareGrantQuerySet":
        return self.for_resource(KIND_LIVE_TRACK_GROUP, str(group_id))

    def for_grantee(self, user) -> "ShareGrantQuerySet":
        return self.filter(grantee_user=user)


class ShareGrant(models.Model):
    resource_kind = models.CharField(max_length=32)
    resource_id = models.CharField(max_length=36)
    grantee_user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="share_grants",
    )

    objects = ShareGrantQuerySet.as_manager()

    class Meta:
        app_label = "api"
        constraints = [
            models.UniqueConstraint(
                fields=["resource_kind", "resource_id", "grantee_user"],
                name="sharegrant_unique_resource_grantee",
            ),
        ]
        indexes = [
            models.Index(fields=["resource_kind", "resource_id"], name="sharegrant_resource"),
            models.Index(fields=["grantee_user"], name="sharegrant_grantee"),
        ]
