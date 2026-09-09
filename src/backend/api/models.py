import uuid

from django.conf import settings
from django.contrib.gis.db import models
from django.contrib.postgres.indexes import GinIndex, GistIndex
from django.db import models as django_models

from api.sharing.models import ShareGrant, ShareLink


class ImportQueue(django_models.Model):
    STATUS_PROCESSING = 'processing'
    STATUS_READY = 'ready'
    STATUS_CANCELED = 'canceled'
    STATUS_FAILED = 'failed'
    STATUS_IMPORTED = 'imported'
    STATUS_CHOICES = (
        (STATUS_PROCESSING, 'Processing'),
        (STATUS_READY, 'Ready'),
        (STATUS_CANCELED, 'Canceled'),
        (STATUS_FAILED, 'Failed'),
        (STATUS_IMPORTED, 'Imported'),
    )
    ENCODING_UTF8 = 'utf8'
    ENCODING_BASE64 = 'base64'
    ENCODING_CHOICES = (
        (ENCODING_UTF8, 'UTF-8'),
        (ENCODING_BASE64, 'Base64'),
    )
    TERMINAL_STATUSES = (STATUS_CANCELED, STATUS_FAILED, STATUS_READY, STATUS_IMPORTED)

    id = django_models.AutoField(primary_key=True)
    user = django_models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=django_models.CASCADE)
    imported = django_models.BooleanField(default=False)
    unparsable = django_models.BooleanField(default=False, help_text="True if the file failed to parse and should not be retried")
    queue_status = django_models.CharField(
        max_length=16,
        choices=STATUS_CHOICES,
        default=STATUS_READY,
        db_index=True,
        help_text="processing|ready|canceled|failed|imported",
    )
    original_filename = django_models.TextField()
    raw_file = django_models.TextField(help_text="Raw file content (KML, KMZ, GPX, etc.)")
    raw_file_encoding = django_models.CharField(
        max_length=8,
        choices=ENCODING_CHOICES,
        default=ENCODING_UTF8,
        help_text="Discriminant for raw_file: utf8 text or base64 bytes",
    )
    file_hash = django_models.CharField(max_length=64, null=True, blank=True, help_text="SHA-256 hash of the raw uploaded file content (entire file, not individual features)")
    log_id = django_models.UUIDField(default=uuid.uuid4, unique=True, help_text="UUID to group related log entries", null=True)
    replacement = django_models.IntegerField(null=True, blank=True, help_text="ID of the existing feature being updated with this replacement upload")
    bulk_operations = django_models.JSONField(default=dict, null=True, blank=True, help_text="Bulk operations (tags, styling) to apply during import")
    skip_intent = django_models.JSONField(
        default=dict,
        help_text="SkipIntent snapshot: blocked, auto_skipped_geometry, user_skipped, user_restored_geometry",
    )
    duplicate_counts = django_models.JSONField(
        default=dict,
        help_text="File-wide duplicate counts: {hash, geometry}",
    )
    timestamp = django_models.DateTimeField(auto_now_add=True)

    class Meta:
        indexes = [
            # Compound index for user-specific import queue queries
            django_models.Index(fields=['user', 'imported', 'timestamp'], name='import_user_imported_time'),
            django_models.Index(fields=['user', 'queue_status', 'timestamp'], name='import_user_qstatus_time'),
            # Index for file hash lookups (raw file content hash for duplicate detection)
            django_models.Index(fields=['user', 'file_hash'], name='import_user_file_hash'),
            # Index for log grouping
            django_models.Index(fields=['log_id', 'timestamp'], name='import_log_id_time'),
        ]


class ImportDraftFeature(models.Model):
    VERDICT_NONE = 'none'
    VERDICT_HASH = 'hash'
    VERDICT_GEOMETRY = 'geometry'
    VERDICT_KIND_CHOICES = (
        (VERDICT_NONE, 'None'),
        (VERDICT_HASH, 'Hash'),
        (VERDICT_GEOMETRY, 'Geometry'),
    )
    SCOPE_NONE = 'none'
    SCOPE_LIBRARY = 'library'
    SCOPE_DRAFT_QUEUE = 'draft_queue'
    VERDICT_SCOPE_CHOICES = (
        (SCOPE_NONE, 'None'),
        (SCOPE_LIBRARY, 'Library'),
        (SCOPE_DRAFT_QUEUE, 'Draft queue'),
    )

    id = models.AutoField(primary_key=True)
    queue = models.ForeignKey(ImportQueue, on_delete=models.CASCADE, related_name='draft_features')
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE)
    geojson = models.JSONField()
    geojson_hash = models.CharField(max_length=64, db_index=True)
    geometry = models.GeometryField(null=True, blank=True, dim=3)
    name = models.TextField(blank=True, default='')
    geometry_type = models.CharField(max_length=32, blank=True, default='')
    sort_lat = models.FloatField(null=True, blank=True)
    sort_lon = models.FloatField(null=True, blank=True)
    spatial_index = models.IntegerField(default=0)
    file_index = models.IntegerField(default=0)
    verdict_kind = models.CharField(max_length=16, choices=VERDICT_KIND_CHOICES, default=VERDICT_NONE)
    verdict_scope = models.CharField(max_length=16, choices=VERDICT_SCOPE_CHOICES, default=SCOPE_NONE)
    match_feature_store_id = models.IntegerField(null=True, blank=True)
    match_queue_id = models.IntegerField(null=True, blank=True)
    match_spatial_index = models.IntegerField(null=True, blank=True)
    match_name = models.TextField(blank=True, default='')
    match_geometry_type = models.CharField(max_length=32, blank=True, default='')

    class Meta:
        indexes = [
            models.Index(fields=['queue', 'spatial_index'], name='draft_queue_spatial'),
            models.Index(fields=['queue', '-sort_lat', 'sort_lon'], name='draft_queue_sort'),
            models.Index(fields=['queue', 'geojson_hash'], name='draft_queue_hash'),
            models.Index(fields=['user', 'geojson_hash'], name='draft_user_hash'),
            models.Index(fields=['queue', 'verdict_kind'], name='draft_queue_verdict'),
            GistIndex(fields=['geometry'], name='draft_geometry_idx'),
        ]


class FeatureStoreQuerySet(models.QuerySet):
    """
    Chainable, self-documenting query methods for `FeatureStore`.

    `scope` distinguishes main-map features (`scope=None`) from features owned by an
    extension (e.g. `places`). Every read/write site should build its query through these
    methods rather than hand-rolling `.filter(scope=...)`/`.filter(scope__isnull=...)` --
    that makes the "did this endpoint forget to exclude extension-scoped features"
    class of bug structurally impossible to reintroduce instead of something that has to
    be remembered at each of the 19+ call sites across the codebase.
    """

    def owned_by(self, user) -> "FeatureStoreQuerySet":
        """Restrict to features owned by `user`."""
        return self.filter(user=user)

    def main_map(self) -> "FeatureStoreQuerySet":
        """Restrict to main-map features (i.e. not owned by an extension scope)."""
        return self.filter(scope__isnull=True)

    def in_scope(self, scope: str) -> "FeatureStoreQuerySet":
        """Restrict to features owned by the given extension scope (e.g. 'places')."""
        return self.filter(scope=scope)

    def with_geometry(self) -> "FeatureStoreQuerySet":
        """Exclude features with no geometry (e.g. failed/partial imports)."""
        return self.exclude(geometry__isnull=True)


class FeatureStoreManager(models.Manager.from_queryset(FeatureStoreQuerySet)):
    pass


class FeatureStore(models.Model):
    id = models.AutoField(primary_key=True)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE)
    source = models.ForeignKey(ImportQueue, on_delete=models.SET_NULL, null=True)
    geojson = models.JSONField(null=False)
    geojson_hash = models.CharField(max_length=64, null=True, blank=True, help_text="SHA-256 hash of this individual feature's GeoJSON content")
    geometry = models.GeometryField(null=True, blank=True, dim=3)  # Spatial field for efficient queries, supports 3D
    scope = models.CharField(max_length=255, null=True, blank=True, default=None, db_index=True, help_text="Scope of the feature (e.g., 'places'). Null means global/standard feature.")
    timestamp = models.DateTimeField(auto_now_add=True)

    objects = FeatureStoreManager()

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
                fields=['user', 'scope', 'geojson_hash'],
                name='unique_user_geojson_hash',
                nulls_distinct=False,
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


class FeatureTag(django_models.Model):
    user = django_models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=django_models.CASCADE)
    feature = django_models.ForeignKey(FeatureStore, on_delete=django_models.CASCADE, related_name='feature_tags')
    tag_key = django_models.CharField(max_length=255)
    namespace = django_models.CharField(max_length=16)
    scope = django_models.CharField(max_length=255, null=True, blank=True, default=None)

    class Meta:
        constraints = [
            django_models.UniqueConstraint(
                fields=['user', 'feature', 'tag_key', 'namespace', 'scope'],
                name='uniq_featuretag_user_feat_key_ns_sc',
                nulls_distinct=False,
            )
        ]
        indexes = [
            django_models.Index(fields=['user', 'scope', 'tag_key'], name='ftag_user_scope_key'),
            django_models.Index(fields=['user', 'scope', 'namespace', 'tag_key'], name='ftag_user_scope_ns_key'),
            django_models.Index(fields=['feature'], name='ftag_feature'),
            django_models.Index(fields=['user', 'tag_key'], name='ftag_user_key'),
        ]


class Collection(django_models.Model):
    id = django_models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = django_models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=django_models.CASCADE)
    name = django_models.CharField(max_length=255)
    description = django_models.TextField(blank=True, null=True)
    created_at = django_models.DateTimeField(auto_now_add=True)
    updated_at = django_models.DateTimeField(auto_now=True)

    class Meta:
        indexes = [
            django_models.Index(fields=['user', 'created_at'], name='collection_user_created'),
        ]


class CollectionTagRule(django_models.Model):
    collection = django_models.ForeignKey(Collection, on_delete=django_models.CASCADE, related_name='tag_rules')
    tag = django_models.CharField(max_length=255)

    class Meta:
        constraints = [
            django_models.UniqueConstraint(fields=['collection', 'tag'], name='uniq_col_tag_rule'),
        ]
        indexes = [
            django_models.Index(fields=['tag'], name='col_tag_rule_tag'),
        ]


class CollectionFeatureMembership(django_models.Model):
    collection = django_models.ForeignKey(Collection, on_delete=django_models.CASCADE, related_name='feature_pins')
    feature = django_models.ForeignKey(FeatureStore, on_delete=django_models.CASCADE, related_name='collection_pins')

    class Meta:
        constraints = [
            django_models.UniqueConstraint(fields=['collection', 'feature'], name='uniq_col_feat_pin'),
        ]
        indexes = [
            django_models.Index(fields=['feature'], name='col_feat_pin_feat'),
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
