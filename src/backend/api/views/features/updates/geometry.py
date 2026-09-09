"""Feature geometry update endpoint."""
import json

from django.views.decorators.http import require_http_methods

from api.services.feature_service import FeatureService, FeatureValidationError
from api.utils.responses import error_response, handle_404, success_response
from api.views.features.updates.feature_geometry import (
    update_feature_geometry_field,
    validate_feature_with_class,
)
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.tagging.modules.feature_date import update_feature_date_tags
from geo_lib.tags.tag_set import TagSet
from geo_lib.tags.tag_writer import TagWriter
from geo_lib.validation.geometry_validation import GeometryValidationError, normalize_and_validate_feature_update
from geo_lib.validation.styling_validation import is_valid_icon_url
from website.auth_decorators import api_or_login_required_401

_logger = get_tagged_logger()


@api_or_login_required_401()
@require_http_methods(["PUT"])
@handle_404
def update_feature(request, feature_id):
    """
    API endpoint to update a specific feature.

    URL parameter:
    - feature_id: ID of the feature to update

    Request body: GeoJSON feature object
    """
    feature = FeatureService.get_owned_feature_or_404(request.user, feature_id)

    try:
        feature_data = json.loads(request.body)
    except json.JSONDecodeError:
        return error_response('Invalid JSON in request body', 400)

    if not isinstance(feature_data, dict):
        return error_response('Request body must be a valid GeoJSON object', 400)

    original_geojson = feature.geojson
    original_properties = original_geojson.get('properties', {})

    try:
        feature_data = normalize_and_validate_feature_update(feature_data, original_properties)
    except GeometryValidationError as e:
        return error_response(str(e), 400)

    original_system_tags = FeatureService.extract_system_tags(original_geojson)

    try:
        feature_data = FeatureService.validate_and_preserve_feature(feature_data)
    except GeometryValidationError as e:
        return error_response(str(e), 400)

    new_properties = feature_data.get('properties', {})
    preserved_system_tags = FeatureService.preserve_system_tags(new_properties, original_system_tags)

    original_created = original_properties.get('created')
    new_created = new_properties.get('created')
    if new_created and new_created != original_created:
        if isinstance(new_created, str):
            preserved_system_tags = update_feature_date_tags(preserved_system_tags, new_created)
        elif hasattr(new_created, 'isoformat'):
            preserved_system_tags = update_feature_date_tags(preserved_system_tags, new_created.isoformat())

    new_tags = new_properties.get('tags', [])
    if not isinstance(new_tags, list):
        new_tags = []

    try:
        FeatureService.validate_user_tags(new_tags)
    except FeatureValidationError as e:
        return error_response(str(e), 400)

    user_tags = list(TagSet.normalize_user_tags(new_tags))

    new_properties['tags'] = user_tags
    new_properties['system_tags'] = preserved_system_tags

    icon_property_names = ['icon', 'icon-href', 'iconUrl', 'icon_url', 'marker-icon', 'marker-symbol', 'symbol']
    original_icon_url = None
    for prop_name in icon_property_names:
        if prop_name in original_properties and original_properties[prop_name]:
            icon_url = original_properties[prop_name]
            if isinstance(icon_url, str) and icon_url.strip() and is_valid_icon_url(icon_url):
                original_icon_url = icon_url
                break

    new_icon_url = new_properties.get('icon', '')

    if original_icon_url:
        if new_icon_url == '':
            for prop_name in icon_property_names:
                new_properties[prop_name] = ''
            if 'marker-color' not in new_properties or not new_properties.get('marker-color'):
                new_properties['marker-color'] = original_properties.get('marker-color', '#ff0000')
        elif isinstance(new_icon_url, str) and new_icon_url.strip():
            if (
                    new_icon_url == original_icon_url
                    or is_valid_icon_url(new_icon_url)
            ):
                for prop_name in icon_property_names:
                    if prop_name != 'icon' and prop_name in new_properties:
                        del new_properties[prop_name]
            else:
                new_properties['icon'] = original_icon_url
                for prop_name in icon_property_names:
                    if prop_name != 'icon':
                        new_properties[prop_name] = ''
                _logger.warning(f"Attempted to manually change icon URL for feature {feature_id}, restored original")
    else:
        if isinstance(new_icon_url, str) and new_icon_url.strip():
            if not is_valid_icon_url(new_icon_url):
                new_properties['icon'] = ''
                for prop_name in icon_property_names:
                    if prop_name != 'icon':
                        new_properties[prop_name] = ''
                _logger.warning(
                    f"Attempted to set external icon URL for feature {feature_id}, removed (only built-in and uploaded icons allowed)"
                )

    feature_data.setdefault('properties', {})['geojson_hash'] = generate_geojson_hash(feature_data)

    is_valid, validated_data, error_msg = validate_feature_with_class(feature_data, feature_id)
    if not is_valid:
        return error_response(error_msg, 400)
    feature_data = validated_data

    feature.geojson = feature_data
    feature.geojson_hash = generate_geojson_hash(feature_data)
    update_feature_geometry_field(feature, feature_data, feature_id)

    feature.save()
    TagWriter.reindex_feature(feature)

    return success_response({
        'message': 'Feature updated successfully',
        'feature_id': feature.id
    })
