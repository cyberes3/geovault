"""
API views for feature creation.
"""
from typing import Optional

from django.views.decorators.http import require_http_methods

from api.services.feature_service import FeatureService, FeatureValidationError
from api.utils.responses import error_response, success_response
from api.validation.decorators import validate_payload
from api.views.features.payload import QuickPointCreatePayload
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.reverse_geocoding.background_geocoding import reverse_geocode_feature_async
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.elevation_service import _fetch_elevation_batch_with_retry
from geo_lib.processing.logging import ImportLog
from geo_lib.processing.tagging.generate import generate_auto_tags
from geo_lib.tags.tag_writer import SystemTagWriter
from geo_lib.types.feature import PointFeature
from geo_lib.validation.geojson.geojson_whitelist import validate_and_normalize_geojson_feature
from geo_lib.validation.geometry_validation import GeometryValidationError
from website.auth_decorators import api_or_login_required_401
from website.settings_utils import get_required_setting

_logger = get_tagged_logger('features')


@api_or_login_required_401()
@require_http_methods(["POST"])
@validate_payload(QuickPointCreatePayload)
def create_quick_point(request, validated_data):
    """
    Create a new point feature with automatic elevation fetching.
    
    POST body:
    - latitude: float (required, -90 to 90)
    - longitude: float (required, -180 to 180)
    - name: string (required)
    - description: string (optional)
    - tags: array of strings (optional)
    - marker_color: string (optional, default: "#ff0000")
    - icon: string (optional)
    
    Returns:
    - feature: Created feature data
    """
    latitude = validated_data['latitude']
    longitude = validated_data['longitude']
    name = validated_data['name'].strip()
    description = validated_data.get('description', '').strip()
    tags = validated_data.get('tags', [])
    marker_color = validated_data.get('marker_color', '#ff0000')
    icon = validated_data.get('icon')

    try:
        user_tags = FeatureService.validate_user_tags(tags)
    except FeatureValidationError as exc:
        return error_response(str(exc), 400)

    # Fetch elevation data
    elevation = _fetch_elevation_for_point(longitude, latitude)
    if elevation is None:
        # Default to 0.0 if elevation fetch failed
        elevation = 0.0

    # Create GeoJSON feature
    coordinates = [longitude, latitude, elevation]

    properties = {
        'name': name,
        'description': description,  # Always include description (empty string if not provided)
        'marker-color': marker_color,
        'tags': user_tags
    }

    if icon:
        properties['icon'] = icon

    feature = {
        'type': 'Feature',
        'geometry': {
            'type': 'Point',
            'coordinates': coordinates
        },
        'properties': properties
    }

    # Validate and normalize the feature
    try:
        normalized_feature = validate_and_normalize_geojson_feature(
            feature,
            preserve_system_tags=None,
            preserve_geojson_hash=False
        )
    except GeometryValidationError:
        return error_response('Feature validation failed', 400)

    # Generate hash first (needed for PointFeature type)
    geojson_hash = generate_geojson_hash(normalized_feature)

    # Add geojson_hash to properties for PointFeature type validation
    if 'properties' not in normalized_feature:
        normalized_feature['properties'] = {}
    normalized_feature['properties']['geojson_hash'] = geojson_hash

    point_feature = PointFeature(**normalized_feature)
    system_tags = generate_auto_tags(point_feature, import_log=ImportLog(), filename='quick-point', skip_reverse_geocoding=True)
    del normalized_feature['properties']['geojson_hash']

    feature_store = FeatureService.create(
        request.user,
        normalized_feature,
        geojson_hash=geojson_hash,
    )
    SystemTagWriter.write_creation_tags(feature_store, system_tags, extra=['quick-point'])
    feature_store.refresh_from_db()
    normalized_feature = feature_store.geojson

    # Start background reverse geocoding (non-blocking)
    reverse_geocode_feature_async(feature_store.id)

    # Add database_id to properties for response
    normalized_feature['properties']['database_id'] = feature_store.id

    return success_response({
        'feature': normalized_feature
    }, status=201)


def _fetch_elevation_for_point(longitude: float, latitude: float) -> Optional[float]:
    """
    Fetch elevation from external elevation API for a single point.

    Args:
        longitude: Longitude coordinate
        latitude: Latitude coordinate

    Returns:
        Elevation value in meters, or None if fetch failed
    """
    # Check if elevation API is enabled
    if not get_required_setting('ELEVATION_API_ENABLED'):
        _logger.info("Elevation API is disabled")
        return None

    api_url = get_required_setting('ELEVATION_API_URL')
    api_timeout = get_required_setting('ELEVATION_API_TIMEOUT')

    # API expects [lat, lon] format
    elevations = _fetch_elevation_batch_with_retry(
        api_url,
        [[latitude, longitude]],
        api_timeout,
        _logger,
        f"point ({longitude}, {latitude})"
    )

    if elevations is None or len(elevations) == 0:
        return None

    elevation = elevations[0]
    if elevation is not None:
        return elevation

    return None
