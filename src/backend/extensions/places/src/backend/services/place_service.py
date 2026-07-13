import json

from django.contrib.gis.geos import GEOSGeometry
from django.db import IntegrityError
from django.db.models import F
from django.db.models.functions import Coalesce, Greatest
from django.utils import timezone

from api.models import FeatureStore
from api.services.feature_service import FeatureService
from api.utils.responses import error_response
from extensions.places.src.backend.constants import DEFAULT_SORT, PLACES_SCOPE, VALID_SORT
from extensions.places.src.backend.models import PlaceMetadata
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.types.feature import PointFeature
from geo_lib.validation.geojson.geojson_whitelist import validate_and_normalize_geojson_feature
from geo_lib.validation.geometry_validation import GeometryValidationError

DUPLICATE_HASH_CONSTRAINT = 'unique_user_geojson_hash'


class PlaceServiceError(Exception):
    def __init__(self, message, status_code=400):
        super().__init__(message)
        self.message = message
        self.status_code = status_code


class PlaceService:
    def list_places(self, user, sort=DEFAULT_SORT):
        sort_key = (sort or DEFAULT_SORT).strip().lower()
        if sort_key not in VALID_SORT:
            sort_key = DEFAULT_SORT

        qs = FeatureStore.objects.owned_by(user).in_scope(PLACES_SCOPE).select_related('place_metadata')
        if sort_key == 'created':
            qs = qs.order_by('-timestamp')
        elif sort_key == 'modified':
            qs = qs.order_by(F('place_metadata__updated_at').desc(nulls_last=True))
        elif sort_key == 'navigated':
            qs = qs.order_by(F('place_metadata__last_navigated_at').desc(nulls_last=True))
        else:
            most_recent = Greatest(
                F('timestamp'),
                Coalesce(F('place_metadata__updated_at'), F('timestamp')),
                Coalesce(F('place_metadata__last_navigated_at'), F('timestamp')),
            )
            qs = qs.order_by(most_recent.desc(nulls_last=True))

        return [self._to_response(feature) for feature in qs]

    def get_place(self, user, feature_id):
        feature = self._get_place_feature(user, feature_id)
        return self._to_response(feature)

    def create_place(self, user, payload_dict):
        normalized_feature, geometry, geojson_hash = self._normalize_payload(payload_dict)
        try:
            feature = FeatureStore.objects.create(
                user=user,
                scope=PLACES_SCOPE,
                geojson=normalized_feature,
                geometry=geometry,
                geojson_hash=geojson_hash,
            )
        except IntegrityError as exc:
            if DUPLICATE_HASH_CONSTRAINT in str(exc):
                raise PlaceServiceError(
                    'A place with the same name and coordinates already exists.',
                    status_code=409,
                ) from exc
            raise
        PlaceMetadata.objects.create(feature=feature, updated_at=timezone.now())
        return self._to_response(feature)

    def update_place(self, user, feature_id, payload_dict):
        feature = self._get_place_feature(user, feature_id)
        normalized_feature, geometry, geojson_hash = self._normalize_payload(payload_dict)
        feature.geojson = normalized_feature
        feature.geometry = geometry
        feature.geojson_hash = geojson_hash
        try:
            feature.save()
        except IntegrityError as exc:
            if DUPLICATE_HASH_CONSTRAINT in str(exc):
                raise PlaceServiceError(
                    'A place with the same name and coordinates already exists.',
                    status_code=409,
                ) from exc
            raise

        meta, _ = PlaceMetadata.objects.get_or_create(feature=feature, defaults={})
        meta.updated_at = timezone.now()
        meta.save(update_fields=['updated_at'])
        return self._to_response(feature)

    def delete_place(self, user, feature_id):
        feature = self._get_place_feature(user, feature_id)
        feature.delete()

    def record_navigation(self, user, feature_id):
        feature = self._get_place_feature(user, feature_id)
        meta, _ = PlaceMetadata.objects.get_or_create(feature=feature, defaults={})
        meta.last_navigated_at = timezone.now()
        meta.save(update_fields=['last_navigated_at'])

    def _get_place_feature(self, user, feature_id):
        return FeatureService.get_owned_feature_or_404(user, feature_id, scope=PLACES_SCOPE)

    def _normalize_payload(self, payload_dict):
        try:
            normalized_feature = validate_and_normalize_geojson_feature(
                payload_dict,
                preserve_system_tags=None,
                preserve_geojson_hash=False,
            )
        except GeometryValidationError as exc:
            raise PlaceServiceError('Invalid geometry', status_code=400) from exc

        geometry, geojson_hash = self._feature_to_geometry_and_hash(normalized_feature)
        if geometry is None:
            raise PlaceServiceError('Geometry is required', status_code=400)

        normalized_feature.setdefault('properties', {})['geojson_hash'] = geojson_hash
        PointFeature(**normalized_feature)
        del normalized_feature['properties']['geojson_hash']
        return normalized_feature, geometry, geojson_hash

    def _feature_to_geometry_and_hash(self, normalized_feature):
        geom_dict = normalized_feature.get('geometry')
        if not geom_dict:
            return None, None
        coords = geom_dict.get('coordinates')
        if geom_dict.get('type') == 'Point' and coords is not None and len(coords) == 2:
            geom_dict = {**geom_dict, 'coordinates': [*coords, 0]}
        geometry = GEOSGeometry(json.dumps(geom_dict))
        geojson_hash = generate_geojson_hash(normalized_feature)
        return geometry, geojson_hash

    def _to_response(self, feature):
        geojson = feature.geojson
        if geojson and 'properties' in geojson:
            geojson = dict(geojson)
            geojson['properties'] = dict(geojson['properties'])
            geojson['properties']['database_id'] = feature.id
            if feature.timestamp:
                geojson['properties']['created_at'] = feature.timestamp.isoformat()
        return geojson


place_service = PlaceService()


def place_service_error_response(exc: PlaceServiceError):
    return error_response(exc.message, exc.status_code)
