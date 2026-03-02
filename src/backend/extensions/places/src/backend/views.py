import json

from django.contrib.gis.geos import GEOSGeometry
from django.db import IntegrityError
from django.db.models import F
from django.db.models.functions import Coalesce, Greatest
from django.http import HttpResponse
from django.utils import timezone
from django.views.decorators.http import require_http_methods
from pydantic import ValidationError as PydanticValidationError

from api.models import FeatureStore
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response, success_response
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.types.feature import PointFeature
from geo_lib.validation.geojson.geojson_whitelist import validate_and_normalize_geojson_feature
from geo_lib.validation.geometry_validation import GeometryValidationError
from geo_lib.website.auth import api_or_login_required_401
from .models import PlaceMetadata
from .validation import PlaceFeaturePayload

VALID_SORT = {'created', 'modified', 'navigated', 'composite'}


def _feature_to_geometry_and_hash(normalized_feature):
    """Build GEOSGeometry and geojson_hash from a normalized GeoJSON feature. Returns (geometry, geojson_hash) or (None, None) if no geometry."""
    geom_dict = normalized_feature.get('geometry')
    if not geom_dict:
        return None, None
    coords = geom_dict.get('coordinates')
    if geom_dict.get('type') == 'Point' and coords is not None and len(coords) == 2:
        geom_dict = {**geom_dict, 'coordinates': [*coords, 0]}
    geometry = GEOSGeometry(json.dumps(geom_dict))
    geojson_hash = generate_geojson_hash(normalized_feature)
    return geometry, geojson_hash


@api_or_login_required_401()
@require_http_methods(["GET", "POST"])
def places_list(request):
    """
    GET: List all places (features with scope='places')
    POST: Create a new place (feature with scope='places')
    """
    if request.method == "GET":
        # Read sort from query string (frontend sends ?sort=created|modified|navigated|composite)
        sort = (request.GET.get('sort') or 'composite').strip().lower()
        if sort not in VALID_SORT:
            sort = 'composite'

        qs = FeatureStore.objects.filter(user=request.user, scope='places')
        if sort == 'created':
            qs = qs.order_by('-timestamp')
        elif sort == 'modified':
            qs = qs.order_by(F('place_metadata__updated_at').desc(nulls_last=True))
        elif sort == 'navigated':
            qs = qs.order_by(F('place_metadata__last_navigated_at').desc(nulls_last=True))
        else:
            # composite: sort by "most recently touched" = latest of (created, modified, navigated)
            # Coalesce so NULL updated_at/last_navigated_at is treated as timestamp (no activity)
            most_recent = Greatest(
                F('timestamp'),
                Coalesce(F('place_metadata__updated_at'), F('timestamp')),
                Coalesce(F('place_metadata__last_navigated_at'), F('timestamp')),
            )
            qs = qs.order_by(most_recent.desc(nulls_last=True))

        # Build list from ordered queryset (iteration preserves order)
        data = []
        for f in qs:
            geojson = f.geojson
            if geojson and 'properties' in geojson:
                geojson['properties']['database_id'] = f.id
                if f.timestamp:
                    geojson['properties']['created_at'] = f.timestamp.isoformat()
            data.append(geojson)

        response = success_response({
            'type': 'FeatureCollection',
            'features': data
        })
        # Prevent caching so changing sort in the UI always gets fresh order
        response['Cache-Control'] = 'no-store, no-cache, must-revalidate'
        return response

    elif request.method == "POST":
        try:
            data = json.loads(request.body)
            try:
                PlaceFeaturePayload.model_validate(data)
            except PydanticValidationError as e:
                err_msgs = [f"{'.'.join(str(loc) for loc in err['loc'])}: {err['msg']}" for err in e.errors()]
                return error_response(
                    'Invalid payload: only Point features are allowed' + (f' — {"; ".join(err_msgs)}' if err_msgs else ''),
                    400,
                )

            # Normalize and validate
            try:
                normalized_feature = validate_and_normalize_geojson_feature(
                    data, preserve_system_tags=None, preserve_geojson_hash=False
                )
            except GeometryValidationError:
                return error_response('Invalid geometry', 400)

            geometry, geojson_hash = _feature_to_geometry_and_hash(normalized_feature)
            if geometry is None:
                return error_response('Geometry is required', 400)

            normalized_feature.setdefault('properties', {})['geojson_hash'] = geojson_hash
            PointFeature(**normalized_feature)  # validation only

            # Create
            try:
                feature = FeatureStore.objects.create(
                    user=request.user,
                    scope='places',
                    geojson=normalized_feature,
                    geometry=geometry,
                    geojson_hash=geojson_hash
                )
            except IntegrityError as e:
                if 'unique_user_geojson_hash' in str(e):
                    return error_response(
                        'A place with the same name and coordinates already exists.',
                        409,
                    )
                raise
            PlaceMetadata.objects.create(feature=feature, updated_at=timezone.now())

            normalized_feature['properties']['database_id'] = feature.id
            return success_response(normalized_feature, status=201)

        except json.JSONDecodeError:
            return error_response('Invalid JSON', 400)


@api_or_login_required_401()
@require_http_methods(["GET", "PUT", "DELETE"])
def place_detail(request, feature_id):
    """
    GET: Retrieve a place
    PUT: Update a place
    DELETE: Delete a place
    """
    feature = get_object_or_404_for_user(FeatureStore, request.user, id=feature_id)

    # Ensure it's a place
    if feature.scope != 'places':
        return error_response('Feature is not a place', 404)

    if request.method == "GET":
        geojson = feature.geojson
        if geojson and 'properties' in geojson:
            geojson['properties']['database_id'] = feature.id
        return success_response(geojson)

    elif request.method == "PUT":
        try:
            data = json.loads(request.body)
            try:
                PlaceFeaturePayload.model_validate(data)
            except PydanticValidationError as e:
                err_msgs = [f"{'.'.join(str(loc) for loc in err['loc'])}: {err['msg']}" for err in e.errors()]
                return error_response(
                    'Invalid payload: only Point features are allowed' + (f' — {"; ".join(err_msgs)}' if err_msgs else ''),
                    400,
                )

            # Normalize and validate
            try:
                normalized_feature = validate_and_normalize_geojson_feature(
                    data, preserve_system_tags=None, preserve_geojson_hash=False
                )
            except GeometryValidationError:
                return error_response('Invalid geometry', 400)

            geometry, geojson_hash = _feature_to_geometry_and_hash(normalized_feature)
            if geometry is None:
                return error_response('Geometry is required', 400)

            normalized_feature.setdefault('properties', {})['geojson_hash'] = geojson_hash
            PointFeature(**normalized_feature)  # validation only

            # Update
            feature.geojson = normalized_feature
            feature.geometry = geometry
            feature.geojson_hash = geojson_hash
            feature.save()

            meta, _ = PlaceMetadata.objects.get_or_create(feature=feature, defaults={})
            meta.updated_at = timezone.now()
            meta.save(update_fields=['updated_at'])

            normalized_feature['properties']['database_id'] = feature.id
            return success_response(normalized_feature)

        except json.JSONDecodeError:
            return error_response('Invalid JSON', 400)

    elif request.method == "DELETE":
        feature.delete()
        return success_response({'deleted': True})
    return None


@api_or_login_required_401()
@require_http_methods(["POST"])
def place_navigate(request, feature_id):
    """Record that the user opened this place in Google Maps (for sort by last navigated)."""
    feature = get_object_or_404_for_user(FeatureStore, request.user, id=feature_id)
    if feature.scope != 'places':
        return error_response('Feature is not a place', 404)
    meta, _ = PlaceMetadata.objects.get_or_create(feature=feature, defaults={})
    meta.last_navigated_at = timezone.now()
    meta.save(update_fields=['last_navigated_at'])
    return HttpResponse(status=204)
