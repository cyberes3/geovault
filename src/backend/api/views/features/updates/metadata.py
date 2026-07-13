"""Feature metadata update endpoints"""
import copy
import traceback

from django.db import transaction
from django.http import Http404, JsonResponse
from django.views.decorators.http import require_http_methods

from api.services.feature_service import FeatureService, FeatureValidationError
from api.utils.responses import error_response, handle_404
from api.validation.decorators import validate_payload
from api.validation.payloads.features import FeatureMetadataUpdate, BulkFeatureUpdatePayload
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.tagging.modules.feature_date import update_feature_date_tags
from geo_lib.processing.tagging.const_strings import CONST_INTERNAL_TAGS, filter_protected_tags, prepare_user_tags
from geo_lib.validation.coordinate.coordinate_validation import validate_coordinates_for_geometry_type
from geo_lib.validation.coordinate.helpers import CoordinateValidationError
from geo_lib.validation.geometry_validation import GeometryValidationError
from website.auth_decorators import api_or_login_required_401

logger = get_tagged_logger()


@api_or_login_required_401()
@require_http_methods(["PUT"])
@handle_404
@validate_payload(FeatureMetadataUpdate)
def update_feature_metadata(request, feature_id, validated_data):
    """
    API endpoint to update only the metadata of a specific feature.
    Can also update coordinates while preserving elevation and other geometry properties.

    URL parameter:
    - feature_id: ID of the feature to update

    Request body: JSON object with optional fields:
    - name: string
    - description: string  
    - tags: array of strings
    - created: datetime string (ISO format)
    - icon: string (icon URL or empty string to remove)
    - marker-color: string (hex color for point markers)
    - stroke: string (hex color for lines/polygons)
    - coordinates: array (coordinates array to update geometry)
    """
    # Get the feature from database
    feature = FeatureService.get_owned_feature_or_404(request.user, feature_id)

    # Extract allowed metadata fields
    allowed_fields = {'name', 'description', 'created', 'tags', 'icon', 'marker-color', 'stroke', 'coordinates'}
    update_fields = {}
    updated_fields = []

    for field in allowed_fields:
        # Handle both 'marker-color' and 'marker_color'
        field_value = validated_data.get(field) or validated_data.get(field.replace('-', '_'))
        if field_value is not None:
            update_fields[field] = field_value
            updated_fields.append(field)

    if not updated_fields:
        return error_response('No valid fields to update. Supported fields: name, description, tags, created, icon, marker-color, stroke, coordinates', 400)

    # Create a deep copy of the original feature to merge updates into
    original_geojson = feature.geojson

    # Ensure original_geojson is a dict (it should be, but be defensive)
    if not isinstance(original_geojson, dict):
        return error_response('Invalid feature data in database', 500)

    merged_feature = copy.deepcopy(original_geojson)

    # Preserve existing system_tags from original feature
    original_system_tags = FeatureService.extract_system_tags(original_geojson)

    # Ensure the feature has the required structure (type, geometry, properties)
    # Always set these explicitly to ensure they exist
    merged_feature['type'] = 'Feature'
    if 'geometry' not in merged_feature or not merged_feature['geometry']:
        merged_feature['geometry'] = original_geojson.get('geometry', {})
    if 'properties' not in merged_feature:
        merged_feature['properties'] = {}

    # Handle coordinate updates first (before other property updates)
    if 'coordinates' in update_fields:
        coordinates_data = update_fields['coordinates']
        geometry = merged_feature.get('geometry', {})
        geometry_type = geometry.get('type', '')

        if not geometry_type:
            return error_response('Feature has no geometry type', 400)

        # Validate coordinates is an array and not empty
        if coordinates_data is None:
            return error_response('Coordinates cannot be null or empty', 400)

        if not isinstance(coordinates_data, list):
            return error_response('Coordinates must be a valid JSON array', 400)

        if len(coordinates_data) == 0:
            return error_response('Coordinates cannot be empty', 400)

        # Validate coordinates structure, bounds, and detect lat/lon swapping
        try:
            if geometry_type == 'GeometryCollection':
                # For GeometryCollection, validate each geometry's coordinates
                if not isinstance(coordinates_data, list):
                    return error_response('GeometryCollection geometries must be an array', 400)
                for idx, sub_geometry in enumerate(coordinates_data):
                    if not isinstance(sub_geometry, dict):
                        return error_response(f'Geometry at index {idx} must be an object', 400)
                    sub_type = sub_geometry.get('type', '')
                    if sub_type and sub_type != 'GeometryCollection':
                        sub_coords = sub_geometry.get('coordinates')
                        if sub_coords is not None:
                            validate_coordinates_for_geometry_type(sub_coords, sub_type)
                geometry['geometries'] = coordinates_data
            else:
                # Validate coordinates for the geometry type
                validate_coordinates_for_geometry_type(coordinates_data, geometry_type)
                # Update coordinates array
                geometry['coordinates'] = coordinates_data
        except CoordinateValidationError as e:
            return error_response(f'Invalid coordinates: {str(e)}', 400)

        merged_feature['geometry'] = geometry

    # Merge update fields into the feature properties
    for field, value in update_fields.items():
        if field == 'tags':
            # Validate tags
            try:
                FeatureService.validate_user_tags(value)
            except FeatureValidationError as e:
                return error_response(str(e), 400)

            # Strip system tags from incoming tags (defensive - user shouldn't be able to add them)
            user_tags = filter_protected_tags(value, CONST_INTERNAL_TAGS)

            # Prepare user tags (lowercase and deduplicate)
            user_tags = prepare_user_tags(user_tags)

            merged_feature['properties']['tags'] = user_tags
        elif field == 'name':
            merged_feature['properties']['name'] = value
        elif field == 'description':
            merged_feature['properties']['description'] = value
        elif field == 'created':
            merged_feature['properties']['created'] = value
        elif field == 'icon':
            # Handle icon - empty string means remove icon
            if value == '':
                # Remove all possible icon properties
                for icon_prop in ['icon', 'icon-href', 'iconUrl', 'icon_url', 'marker-icon', 'marker-symbol', 'symbol']:
                    merged_feature['properties'].pop(icon_prop, None)
            else:
                merged_feature['properties']['icon'] = value
        elif field == 'marker-color':
            merged_feature['properties']['marker-color'] = value
        elif field == 'stroke':
            # For lines and polygons, update stroke
            merged_feature['properties']['stroke'] = value
            # For polygons, also update fill to match stroke
            geometry_type = merged_feature.get('geometry', {}).get('type', '')
            if geometry_type.lower() in ['polygon', 'multipolygon']:
                merged_feature['properties']['fill'] = value
                merged_feature['properties']['fill-opacity'] = 0.1

    # Update system tags if created date was changed
    updated_system_tags = original_system_tags
    if 'created' in update_fields:
        updated_system_tags = update_feature_date_tags(original_system_tags, update_fields['created'])

    # Run the merged feature through validate_and_normalize_geojson_feature()
    try:
        normalized_feature = FeatureService.validate_and_preserve_feature(merged_feature)
        # Ensure system_tags are preserved after normalization
        normalized_feature['properties']['system_tags'] = updated_system_tags
    except GeometryValidationError as e:
        return error_response(f'Feature validation failed: {str(e)}', 400)

    # Regenerate geojson_hash if coordinates were updated
    if 'coordinates' in update_fields:
        normalized_feature['properties']['geojson_hash'] = generate_geojson_hash(normalized_feature)
        feature.geojson_hash = normalized_feature['properties']['geojson_hash']

    # Update the feature's geojson data
    feature.geojson = normalized_feature
    feature.save()

    return JsonResponse({
        'message': f'Feature metadata updated successfully. Updated fields: {", ".join(updated_fields)}',
        'feature_id': feature.id,
        'updated_fields': updated_fields
    })


@api_or_login_required_401()
@require_http_methods(["POST"])
@validate_payload(BulkFeatureUpdatePayload)
def bulk_update_features_metadata(request, validated_data):
    """
    API endpoint to bulk update metadata for multiple features (name, description, tags, created date).
    Does not modify geometry or geojson_hash.
    
    Request body: JSON object with:
    - updates: array of update objects, each containing:
      - feature_id: int (required)
      - tags: array of strings (optional)
      - name: string (optional)
      - description: string (optional)
      - created: datetime string in ISO format (optional)
    
    Returns:
    - success: bool
    - updated_count: int (number of successfully updated features)
    - errors: array of error objects with feature_id and error message
    """
    # Process all updates in a single transaction
    updated_count = 0
    errors = []
    updates = validated_data['updates']

    with transaction.atomic():
        for update_data in updates:
            feature_id = update_data['feature_id']

            allowed_fields = {'name', 'description', 'created', 'tags'}
            update_fields = {}
            updated_fields = []

            for field in allowed_fields:
                if field in update_data:
                    update_fields[field] = update_data[field]
                    updated_fields.append(field)

            if not updated_fields:
                errors.append({
                    'feature_id': feature_id,
                    'error': 'No valid fields to update. Supported fields: name, description, tags, created'
                })
                continue

            try:
                # Get the feature from database
                feature = FeatureService.get_owned_feature_or_404(request.user, feature_id)

                # Create a deep copy of the original feature to merge updates into
                original_geojson = feature.geojson

                # Ensure original_geojson is a dict (it should be, but be defensive)
                if not isinstance(original_geojson, dict):
                    errors.append({
                        'feature_id': feature_id,
                        'error': 'Invalid feature data in database'
                    })
                    continue

                merged_feature = copy.deepcopy(original_geojson)

                # Preserve existing system_tags from original feature
                original_system_tags = FeatureService.extract_system_tags(original_geojson)

                # Ensure the feature has the required structure (type, geometry, properties)
                merged_feature['type'] = 'Feature'
                if 'geometry' not in merged_feature or not merged_feature['geometry']:
                    merged_feature['geometry'] = original_geojson.get('geometry', {})
                if 'properties' not in merged_feature:
                    merged_feature['properties'] = {}

                for field, value in update_fields.items():
                    if field == 'tags':
                        user_tags = filter_protected_tags(value, CONST_INTERNAL_TAGS)
                        user_tags = prepare_user_tags(user_tags)
                        merged_feature['properties']['tags'] = user_tags
                    else:
                        merged_feature['properties'][field] = value

                # Update system tags if created date was changed
                updated_system_tags = original_system_tags
                if 'created' in update_fields:
                    updated_system_tags = update_feature_date_tags(original_system_tags, update_fields['created'])

                # Run the merged feature through validate_and_normalize_geojson_feature()
                try:
                    normalized_feature = FeatureService.validate_and_preserve_feature(merged_feature)
                    # Ensure system_tags are preserved after normalization
                    normalized_feature['properties']['system_tags'] = updated_system_tags
                except GeometryValidationError:
                    logger.error("Feature validation failed for %s in bulk update:\n%s", feature_id, traceback.format_exc())
                    errors.append({
                        'feature_id': feature_id,
                        'error': 'Feature validation failed'
                    })
                    continue

                # Update the feature's geojson data
                feature.geojson = normalized_feature
                feature.save()

                updated_count += 1

            except Http404:
                errors.append({
                    'feature_id': feature_id,
                    'error': 'Feature not found or access denied'
                })
            except Exception:
                logger.error("Error updating feature metadata %s in bulk update:\n%s", feature_id, traceback.format_exc())
                errors.append({
                    'feature_id': feature_id,
                    'error': 'Failed to update feature metadata'
                })

    return JsonResponse({
        'updated_count': updated_count,
        'errors': errors
    })
