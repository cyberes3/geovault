"""
Central mutation/lookup pipeline for `FeatureStore`, enforcing scope by default.

`get_owned_feature_or_404` restricts a single-ID lookup to main-map features unless
`scope` is explicitly passed, so a lookup on the main-map API surface can never resolve
to an extension-scoped feature (e.g. `places`) by accident -- this is the fix for the
"extension-scoped features can leak into main-map bulk-ops/share/export/tag-regen
endpoints" class of bug (see `api/models.py`'s `FeatureStoreQuerySet` docstring for the
underlying queryset methods this builds on).
"""
import traceback
from typing import Optional

from django.http import Http404

from api.models import FeatureStore
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.import_operations.styling import apply_bulk_operations as apply_bulk_operations_to_features
from geo_lib.processing.logging import ImportLog
from geo_lib.processing.tagging.const_strings import CONST_INTERNAL_TAGS, is_protected_tag
from geo_lib.processing.tagging.generate import generate_auto_tags
from geo_lib.types.feature import (
    GeoFeatureSupported,
    LineStringFeature,
    MultiLineStringFeature,
    PointFeature,
    PolygonFeature,
)
from geo_lib.validation.geojson.geojson_whitelist import validate_and_normalize_geojson_feature
from geo_lib.validation.geometry_validation import GeometryValidationError
from website.settings_utils import get_required_setting

_logger = get_tagged_logger('FEATURE_SERVICE')

_GEOMETRY_TYPE_TO_FEATURE_CLASS = {
    'point': PointFeature,
    'multipoint': PointFeature,
    'linestring': LineStringFeature,
    'multilinestring': MultiLineStringFeature,
    'polygon': PolygonFeature,
    'multipolygon': PolygonFeature,
}


class UnsupportedFeatureGeometryError(ValueError):
    """Raised when a feature's geometry type can't be mapped to a feature class for tag regeneration."""


class FeatureValidationError(ValueError):
    """Raised when user-supplied feature data (tags, properties) fails a service-level validation check."""


class FeatureService:
    """Mutation/lookup pipeline for `FeatureStore`."""

    @staticmethod
    def get_owned_feature_or_404(user, feature_id: int, *, scope: Optional[str] = None) -> FeatureStore:
        """
        Fetch a feature owned by `user`, restricted to `scope` (main-map / `scope IS NULL`
        when not given). Raises Http404 if missing, not owned, or in the wrong scope.
        """
        qs = FeatureStore.objects.owned_by(user)
        qs = qs.in_scope(scope) if scope is not None else qs.main_map()
        try:
            return qs.get(id=feature_id)
        except FeatureStore.DoesNotExist:
            raise Http404("Feature not found or access denied")

    @staticmethod
    def extract_system_tags(feature: dict) -> list:
        """Extract and normalize `system_tags` from a feature dict (or its `properties` sub-dict)."""
        if isinstance(feature, dict):
            properties = feature.get('properties', feature)
            system_tags = properties.get('system_tags', [])
            if isinstance(system_tags, list):
                return system_tags
        return []

    @staticmethod
    def validate_and_preserve_feature(feature: dict) -> dict:
        """
        Validate and normalize a feature, preserving its `system_tags` and `geojson_hash`.

        Raises:
            GeometryValidationError: if validation fails.
        """
        system_tags = FeatureService.extract_system_tags(feature)
        normalized_feature = validate_and_normalize_geojson_feature(
            feature,
            preserve_system_tags=system_tags,
            preserve_geojson_hash=True,
        )
        normalized_feature['properties']['system_tags'] = system_tags
        return normalized_feature

    @staticmethod
    def preserve_system_tags(properties: dict, original_system_tags: list) -> list:
        """
        Strip any client-supplied `system_tags` from `properties` (in place) and return the
        real system tags from the database -- clients can never set their own system tags.
        """
        if not isinstance(original_system_tags, list):
            original_system_tags = []
        properties.pop('system_tags', None)
        return original_system_tags

    @staticmethod
    def validate_user_tags(tags) -> list:
        """
        Validate a list of user-supplied tags: must be a list of non-empty, non-control-character
        strings within `TAG_MAX_LENGTH`, none of which are protected system tag names.

        Raises:
            FeatureValidationError: on the first invalid tag found, with a human-readable message.
        """
        if not isinstance(tags, list):
            raise FeatureValidationError('tags must be an array')

        tag_max_length = get_required_setting('TAG_MAX_LENGTH')
        for tag in tags:
            if not isinstance(tag, str):
                raise FeatureValidationError('all tags must be strings')
            if is_protected_tag(tag, CONST_INTERNAL_TAGS):
                raise FeatureValidationError(
                    'System tags (type, import-year, import-month, feature-year, feature-month, '
                    'source-file, track, elevation, reverse geocoding) cannot be added as user tags'
                )
            if len(tag) > tag_max_length:
                raise FeatureValidationError(
                    f'Tag "{tag[:50]}..." exceeds maximum length of {tag_max_length} characters'
                )
            if not tag.strip():
                raise FeatureValidationError('Tags cannot be empty or contain only whitespace')
            if any(ord(c) < 32 and c not in '\t\n\r' for c in tag):
                raise FeatureValidationError('Tags cannot contain control characters')
        return tags

    @staticmethod
    def apply_bulk_operations(queryset, bulk_ops: dict) -> int:
        """
        Apply bulk styling/tag operations to every feature in `queryset`, validating and
        saving each one. Returns the count of features actually updated (a feature is
        skipped, not counted, if validation fails after the operations are applied).

        Callers are responsible for scoping `queryset` appropriately (e.g.
        `FeatureStore.objects.owned_by(user).main_map()...`) -- this only applies the
        mutation, it doesn't decide which features are in scope.
        """
        updated_count = 0
        for feature in queryset.iterator(chunk_size=200):
            if FeatureService._apply_bulk_ops_and_save_feature(feature, bulk_ops):
                updated_count += 1
        return updated_count

    @staticmethod
    def _apply_bulk_ops_and_save_feature(feature: FeatureStore, bulk_ops: dict) -> bool:
        original_geojson = feature.geojson
        if not isinstance(original_geojson, dict):
            return False

        updated_features = apply_bulk_operations_to_features([original_geojson], bulk_ops)
        if not updated_features:
            return False

        updated_geojson = updated_features[0]

        try:
            normalized_feature = FeatureService.validate_and_preserve_feature(updated_geojson)
        except GeometryValidationError as e:
            _logger.warning(f"Feature validation failed for feature {feature.id} in bulk operations: {str(e)}")
            return False

        feature.geojson = normalized_feature
        feature.geojson_hash = generate_geojson_hash(normalized_feature)
        feature.save(update_fields=['geojson', 'geojson_hash'])
        return True

    @staticmethod
    def regenerate_tags(feature: FeatureStore) -> FeatureStore:
        """
        Regenerate a feature's system tags from its current geometry, preserving any
        existing user tags. Saves and returns the updated feature.

        Raises:
            UnsupportedFeatureGeometryError: geometry type has no mapped feature class,
                or the feature's GeoJSON doesn't parse into that feature class.
            GeometryValidationError: the regenerated feature fails validation.
        """
        geojson_data = feature.geojson
        geom_type = geojson_data.get('geometry', {}).get('type', '').lower()
        feature_class = _GEOMETRY_TYPE_TO_FEATURE_CLASS.get(geom_type)
        if feature_class is None:
            raise UnsupportedFeatureGeometryError(f"Unsupported geometry type: {geom_type}")

        geojson_data.setdefault('properties', {})['geojson_hash'] = generate_geojson_hash(geojson_data)

        try:
            feature_instance: GeoFeatureSupported = feature_class(**geojson_data)
        except Exception:
            _logger.error(f"Error creating feature instance for tag regeneration {feature.id}:\n{traceback.format_exc()}")
            raise UnsupportedFeatureGeometryError("Invalid feature structure")

        existing_user_tags = geojson_data.get('properties', {}).get('tags', [])
        if not isinstance(existing_user_tags, list):
            existing_user_tags = []

        new_system_tags = generate_auto_tags(feature_instance, import_log=ImportLog())

        geojson_data['properties']['tags'] = existing_user_tags
        geojson_data['properties']['system_tags'] = new_system_tags

        normalized_feature = FeatureService.validate_and_preserve_feature(geojson_data)

        feature.geojson = normalized_feature
        feature.save()
        return feature
