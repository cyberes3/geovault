"""Tag regeneration operations"""
import traceback
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response, handle_404
from api.views.features.updates.shared import _validate_and_preserve_feature
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.logging import ImportLog
from geo_lib.processing.tagging.generate import generate_auto_tags
from geo_lib.types.feature import (
    PointFeature,
    LineStringFeature,
    MultiLineStringFeature,
    PolygonFeature,
    GeoFeatureSupported
)
from geo_lib.validation.geometry_validation import GeometryValidationError
from geo_lib.website.auth import api_or_login_required_401

logger = get_tagged_logger()


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
def regenerate_feature_tags(request, feature_id):
    """
    API endpoint to regenerate automatic tags for a feature based on its current geometry.
    Preserves existing non-auto tags (user-generated tags that don't match auto tag patterns).

    URL parameter:
    - feature_id: ID of the feature to regenerate tags for
    """
    # Get the feature from database
    feature = get_object_or_404_for_user(FeatureStore, request.user, id=feature_id)

    # Get the feature's GeoJSON data
    geojson_data = feature.geojson

    # Convert to feature class instance for tag generation
    geom_type = geojson_data.get('geometry', {}).get('type', '').lower()
    feature_class = None

    match geom_type:
        case 'point' | 'multipoint':
            feature_class = PointFeature
        case 'linestring':
            feature_class = LineStringFeature
        case 'multilinestring':
            feature_class = MultiLineStringFeature
        case 'polygon' | 'multipolygon':
            feature_class = PolygonFeature
        case _:
            return error_response(f'Unsupported geometry type: {geom_type}', 400)

    if feature_class is None:
        return error_response('Could not determine feature class', 400)

    # Ensure geojson_hash is present for Pydantic validation
    geojson_data.setdefault('properties', {})['geojson_hash'] = generate_geojson_hash(geojson_data)

    # Create feature instance
    try:
        feature_instance: GeoFeatureSupported = feature_class(**geojson_data)
    except Exception:
        logger.error("Error creating feature instance for tag regeneration %s:\n%s", feature_id, traceback.format_exc())
        return error_response('Invalid feature structure', 400)

    # Get existing user tags (preserve them)
    existing_user_tags = geojson_data.get('properties', {}).get('tags', [])
    if not isinstance(existing_user_tags, list):
        existing_user_tags = []

    # Generate new system tags
    new_system_tags = generate_auto_tags(feature_instance, import_log=ImportLog())

    # Update the feature's tags - preserve user tags, regenerate system tags
    if 'properties' not in geojson_data:
        geojson_data['properties'] = {}
    geojson_data['properties']['tags'] = existing_user_tags
    geojson_data['properties']['system_tags'] = new_system_tags

    # Validate and normalize the feature after tag regeneration
    try:
        normalized_feature = _validate_and_preserve_feature(geojson_data)
    except GeometryValidationError as e:
        logger.error(f"Feature validation failed for feature {feature_id} during tag regeneration: {str(e)}")
        return error_response(f'Feature validation failed: {str(e)}', 400)

    # Update the feature
    feature.geojson = normalized_feature
    feature.save()

    return JsonResponse({
        'message': 'Feature tags regenerated successfully',
        'feature_id': feature.id,
        'tags': existing_user_tags,
        'system_tags': new_system_tags
    })
