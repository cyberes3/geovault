import copy
import json
import traceback

from django.contrib.gis.geos import GEOSGeometry
from django.db.models import Q
from django.http import Http404
from website.settings_utils import get_required_setting
from django.db import transaction
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore, ImportQueue
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response, handle_404
from geo_lib.tags.const_strings import CONST_INTERNAL_TAGS, filter_protected_tags, is_protected_tag, prepare_user_tags
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.logging.console import get_access_logger
from geo_lib.processing.tagging import generate_auto_tags, update_feature_date_tags
from geo_lib.processing.import_utils import (
    apply_bulk_operations as apply_bulk_operations_to_features,
    validate_bulk_operations_payload,
)
from geo_lib.types.feature import PointFeature, LineStringFeature, MultiLineStringFeature, PolygonFeature, GeoFeatureSupported
from geo_lib.validation.geometry_validation import (
    normalize_and_validate_feature_update,
    GeometryValidationError
)
from geo_lib.validation.coordinate.helpers import (
    CoordinateValidationError
)
from geo_lib.validation.coordinate.coordinate_validation import validate_coordinates_for_geometry_type
from geo_lib.validation import validate_and_normalize_geojson_feature
from geo_lib.validation.styling_validation import (
    is_valid_icon_url,
)
from geo_lib.website.auth import api_or_login_required_401
from api.validation.feature_updates import validate_payload, BulkFeatureUpdatePayload, FeatureMetadataUpdate, ReplacementGeometryPayload

logger = get_access_logger()


def _validate_tags(tags):
    """
    Validate a list of tags.
    
    Args:
        tags: List of tags to validate
        
    Returns:
        Tuple of (is_valid, error_response) where error_response is None if valid
    """
    if not isinstance(tags, list):
        return False, error_response('tags must be an array', 400)
    
    for tag in tags:
        if not isinstance(tag, str):
            return False, error_response('all tags must be strings', 400)
        
        # Check if tag is a system tag (protected tag)
        if is_protected_tag(tag, CONST_INTERNAL_TAGS):
            return False, error_response(
                'System tags (type, import-year, import-month, feature-year, feature-month, source-file, track, elevation, geocoding) cannot be added as user tags',
                400
            )
        
        # Validate tag length
        tag_max_length = get_required_setting('TAG_MAX_LENGTH')
        if len(tag) > tag_max_length:
            return False, error_response(
                f'Tag "{tag[:50]}..." exceeds maximum length of {tag_max_length} characters',
                400
            )
        
        # Validate tag is not empty after stripping
        if not tag.strip():
            return False, error_response('Tags cannot be empty or contain only whitespace', 400)
        
        # Validate tag format: no control characters
        if any(ord(c) < 32 and c not in '\t\n\r' for c in tag):
            return False, error_response('Tags cannot contain control characters', 400)
    
    return True, None


def _extract_system_tags(feature: dict) -> list:
    """
    Extract and normalize system_tags from a feature dictionary.
    
    Args:
        feature: Feature dictionary (can be full feature or just properties)
        
    Returns:
        List of system_tags (empty list if not present or invalid)
    """
    if isinstance(feature, dict):
        properties = feature.get('properties', feature)
        system_tags = properties.get('system_tags', [])
        if isinstance(system_tags, list):
            return system_tags
    return []


def _validate_and_preserve_feature(feature: dict) -> dict:
    """
    Validate and normalize a feature, preserving system_tags and geojson_hash.
    
    Args:
        feature: GeoJSON Feature dictionary

    Returns:
        Validated and normalized feature dictionary
        
    Raises:
        GeometryValidationError: If validation fails
    """
    # Extract system_tags before validation
    system_tags = _extract_system_tags(feature)
    
    # Validate and normalize
    normalized_feature = validate_and_normalize_geojson_feature(
        feature,
        preserve_system_tags=system_tags,
        preserve_geojson_hash=True
    )
    
    # Ensure system_tags are preserved after normalization
    normalized_feature['properties']['system_tags'] = system_tags
    
    return normalized_feature


def _apply_bulk_ops_and_save_feature(feature: FeatureStore, bulk_ops: dict) -> bool:
    """
    Apply bulk operations to a feature, validate, and save.
    
    Args:
        feature: FeatureStore instance to update
        bulk_ops: Bulk operations dictionary
        
    Returns:
        True if feature was successfully updated, False if skipped due to error
    """
    original_geojson = feature.geojson
    if not isinstance(original_geojson, dict):
        return False
    
    # Apply bulk operations
    updated_features = apply_bulk_operations_to_features([original_geojson], bulk_ops)
    if not updated_features:
        return False
    
    updated_geojson = updated_features[0]
    
    # Validate and normalize the updated feature
    try:
        normalized_feature = _validate_and_preserve_feature(updated_geojson)
    except GeometryValidationError as e:
        logger.warning(f"Feature validation failed for feature {feature.id} in bulk operations: {str(e)}")
        return False
    
    # Update feature geojson and hash (geometry is unchanged by styling)
    feature.geojson = normalized_feature
    feature.geojson_hash = generate_geojson_hash(normalized_feature)
    feature.save(update_fields=['geojson', 'geojson_hash'])
    
    return True


def _validate_and_preserve_system_tags(properties_dict, original_system_tags):
    """
    Validate that system_tags are not being modified and return preserved system_tags.
    Silently discards any received system_tags and replaces them with originals from the DB.
    
    Args:
        properties_dict: Dictionary containing properties (may include system_tags)
        original_system_tags: Original system_tags from the feature
        
    Returns:
        Tuple of (is_valid, error_response, preserved_system_tags) where error_response is None if valid
    """
    # Ensure original_system_tags is a list
    if not isinstance(original_system_tags, list):
        original_system_tags = []
    
    # Silently discard any received system_tags and replace with originals from DB
    if 'system_tags' in properties_dict:
        del properties_dict['system_tags']
    
    return True, None, original_system_tags


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
    feature = get_object_or_404_for_user(FeatureStore, request.user, id=feature_id)

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
    original_system_tags = _extract_system_tags(original_geojson)
    
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
            is_valid, error_resp = _validate_tags(value)
            if not is_valid:
                return error_resp
            
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
        normalized_feature = validate_and_normalize_geojson_feature(
            merged_feature,
            preserve_system_tags=updated_system_tags,
            preserve_geojson_hash=True
        )
    except GeometryValidationError as e:
        return error_response(f'Feature validation failed: {str(e)}', 400)
    
    # Ensure system_tags are preserved after normalization
    normalized_feature['properties']['system_tags'] = updated_system_tags
    
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
    try:
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
                    feature = get_object_or_404_for_user(FeatureStore, request.user, id=feature_id)
                    
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
                    original_system_tags = _extract_system_tags(original_geojson)
                    
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
                        normalized_feature = validate_and_normalize_geojson_feature(
                            merged_feature,
                            preserve_system_tags=updated_system_tags,
                            preserve_geojson_hash=True
                        )
                    except GeometryValidationError as e:
                        errors.append({
                            'feature_id': feature_id,
                            'error': f'Feature validation failed: {str(e)}'
                        })
                        continue
                    
                    # Ensure system_tags are preserved after normalization
                    normalized_feature['properties']['system_tags'] = updated_system_tags
                    
                    # Update the feature's geojson data
                    feature.geojson = normalized_feature
                    feature.save()
                    
                    updated_count += 1
                    
                except Http404:
                    errors.append({
                        'feature_id': feature_id,
                        'error': 'Feature not found or access denied'
                    })
                except Exception as e:
                    logger.error(f"Error updating feature metadata {feature_id} in bulk update: {traceback.format_exc()}")
                    errors.append({
                        'feature_id': feature_id,
                        'error': f'Failed to update feature metadata: {str(e)}'
                    })
        
        return JsonResponse({
            'updated_count': updated_count,
            'errors': errors
        })
    
    except Exception:
        logger.error(f"Error in bulk update features metadata: {traceback.format_exc()}")
        return error_response('Failed to process bulk update request', 500)


@api_or_login_required_401()
@require_http_methods(["POST"])
def apply_bulk_operations_to_tag(request, tag_name: str):
    """
    Apply bulk operations to all features that have the specified tag.

    This endpoint is used from the Tags page to style all features in a tag
    (point color, point icon, line color, polygon color, and additional tags).

    Request body:
    - bulk_operations: JSON object with the same structure as import bulk operations:
      {
        "tags": [...],
        "pointColor": "#rrggbb" | null,
        "pointIcon": "url" | null,
        "lineColor": "#rrggbb" | null,
        "polyColor": "#rrggbb" | null
      }
    """
    try:
        try:
            data = json.loads(request.body)
        except json.JSONDecodeError:
            return error_response('Invalid JSON in request body', 400)

        if not isinstance(data, dict):
            return error_response('Request body must be a valid JSON object', 400)

        bulk_ops = data.get('bulk_operations', {})
        is_valid, error_message = validate_bulk_operations_payload(bulk_ops)
        if not is_valid:
            return error_response(error_message, 400)

        # Validate tag name
        if not isinstance(tag_name, str) or not tag_name.strip():
            return error_response('Tag name is required', 400)

        # Only operate on the current user's features
        # Search in both user tags and system tags
        features_qs = FeatureStore.objects.filter(
            user=request.user
        ).filter(
            Q(geojson__properties__tags__contains=[tag_name]) |
            Q(geojson__properties__system_tags__contains=[tag_name])
        ).only('id', 'geojson')

        if not features_qs.exists():
            return JsonResponse({
                'success': True,
                'updated_count': 0,
                'msg': 'No features found for this tag'
            })

        updated_count = 0

        with transaction.atomic():
            for feature in features_qs.iterator(chunk_size=200):
                if _apply_bulk_ops_and_save_feature(feature, bulk_ops):
                    updated_count += 1

        return JsonResponse({
            'success': True,
            'updated_count': updated_count
        })

    except Exception:
        logger.error(f"Error applying bulk operations to tag '{tag_name}': {traceback.format_exc()}")
        return error_response('Failed to apply bulk operations to tag', 500)


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
    # Get the feature from database
    feature = get_object_or_404_for_user(FeatureStore, request.user, id=feature_id)

    # Parse request body
    try:
        feature_data = json.loads(request.body)
    except json.JSONDecodeError:
        return error_response('Invalid JSON in request body', 400)

    # Validate that it's a proper GeoJSON feature or geometry
    if not isinstance(feature_data, dict):
        return error_response('Request body must be a valid GeoJSON object', 400)

    # Get original feature data for reference
    original_geojson = feature.geojson
    original_properties = original_geojson.get('properties', {})

    # Normalize and validate Feature or geometry object
    try:
        feature_data = normalize_and_validate_feature_update(feature_data, original_properties)
    except GeometryValidationError as e:
        return error_response(str(e), 400)

    # Preserve existing system_tags from original feature
    original_system_tags = _extract_system_tags(original_geojson)
    
    # Validate, whitelist, and normalize the feature
    try:
        feature_data = _validate_and_preserve_feature(feature_data)
    except GeometryValidationError as e:
        return error_response(str(e), 400)

    # Get new properties after normalization
    new_properties = feature_data.get('properties', {})

    # Validate that system_tags were preserved correctly
    is_valid, error_resp, preserved_system_tags = _validate_and_preserve_system_tags(
        new_properties, original_system_tags
    )
    if not is_valid:
        return error_resp

    # Update system tags if created date was changed
    original_created = original_properties.get('created')
    new_created = new_properties.get('created')
    if new_created and new_created != original_created:
        # Created date was updated, update feature-year and feature-month tags
        if isinstance(new_created, str):
            preserved_system_tags = update_feature_date_tags(preserved_system_tags, new_created)
        elif hasattr(new_created, 'isoformat'):
            # datetime object
            preserved_system_tags = update_feature_date_tags(preserved_system_tags, new_created.isoformat())

    # Strip system tags from incoming tags (defensive)
    new_tags = new_properties.get('tags', [])
    if not isinstance(new_tags, list):
        new_tags = []
    
    # Validate tags
    is_valid, error_resp = _validate_tags(new_tags)
    if not is_valid:
        return error_resp
    
    # Filter out any system tags that user might have added
    user_tags = filter_protected_tags(new_tags, CONST_INTERNAL_TAGS)

    # Prepare user tags (lowercase and deduplicate)
    user_tags = prepare_user_tags(user_tags)

    # Store user tags and preserve system tags separately
    new_properties['tags'] = user_tags
    new_properties['system_tags'] = preserved_system_tags

    # Check for icon URLs in original feature (built-in or uploaded only)
    icon_property_names = ['icon', 'icon-href', 'iconUrl', 'icon_url', 'marker-icon', 'marker-symbol', 'symbol']
    original_icon_url = None
    for prop_name in icon_property_names:
        if prop_name in original_properties and original_properties[prop_name]:
            icon_url = original_properties[prop_name]
            if isinstance(icon_url, str) and icon_url.strip() and is_valid_icon_url(icon_url):
                original_icon_url = icon_url
                break

    # Handle icon URL changes
    # Allow: removing icons (null/empty), setting new built-in icons (assets/), 
    #        setting new uploaded icons (/api/icons/), keeping same icon
    # Prevent: manually changing existing icon URLs to arbitrary external URLs
    new_icon_url = new_properties.get('icon', '')
    
    if original_icon_url:
        # Check if icon is being removed (main 'icon' property is empty)
        if new_icon_url == '':
            # Icon is being removed - clear all icon properties and ensure marker-color is set
            for prop_name in icon_property_names:
                new_properties[prop_name] = ''
            if 'marker-color' not in new_properties or not new_properties.get('marker-color'):
                new_properties['marker-color'] = original_properties.get('marker-color', '#ff0000')
        elif isinstance(new_icon_url, str) and new_icon_url.strip():
            # Icon is being changed - validate new icon URL
            # Allow: same icon, built-in icons (assets/), uploaded icons (/api/icons/)
            if (
                new_icon_url == original_icon_url
                or is_valid_icon_url(new_icon_url)
            ):
                # Valid icon change - clear other icon property names to avoid conflicts
                for prop_name in icon_property_names:
                    if prop_name != 'icon' and prop_name in new_properties:
                        del new_properties[prop_name]
            else:
                # Invalid external URL - restore original icon
                new_properties['icon'] = original_icon_url
                # Clear other icon properties
                for prop_name in icon_property_names:
                    if prop_name != 'icon':
                        new_properties[prop_name] = ''
                logger.warning(f"Attempted to manually change icon URL for feature {feature_id}, restored original")
    else:
        # No original icon - validate that new icons are built-in or uploaded (not external URLs)
        if isinstance(new_icon_url, str) and new_icon_url.strip():
            # Only allow built-in icons (assets/) or uploaded icons (/api/icons/)
            if not is_valid_icon_url(new_icon_url):
                # Remove invalid external icon URL
                new_properties['icon'] = ''
                # Clear other icon properties
                for prop_name in icon_property_names:
                    if prop_name != 'icon':
                        new_properties[prop_name] = ''
                logger.warning(f"Attempted to set external icon URL for feature {feature_id}, removed (only built-in and uploaded icons allowed)")

    # Note: stroke-width, fill, and fill-opacity normalization is now handled by validate_and_normalize_geojson_feature
    # The normalization function ensures stroke-width=2 for lines/polygons and proper fill/fill-opacity for polygons
    # Color validation (invalid colors set to default red) is also handled by validate_and_normalize_geojson_feature

    # Ensure geojson_hash is present for Pydantic validation
    # Generate temporary hash if not present (will be regenerated later)
    feature_data.setdefault('properties', {})['geojson_hash'] = generate_geojson_hash(feature_data)

    # Validate feature structure using the same validation as import conversion
    try:
        geom_type = feature_data.get('geometry', {}).get('type', '').lower()
        feature_class = None

        # GeometryCollection is not supported by the feature classes, but we allow it
        if geom_type == 'geometrycollection':
            # For GeometryCollection, we do basic validation but skip feature class validation
            geom_data = feature_data.get('geometry', {})
            if not geom_data.get('geometries') or not isinstance(geom_data.get('geometries'), list):
                return error_response('GeometryCollection must have a geometries array', 400)
            # Skip feature class validation for GeometryCollection
            feature_class = None
        else:
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

        # Validate by instantiating the feature class (this will raise ValidationError if invalid)
        # Skip for GeometryCollection as it's not supported by feature classes
        if feature_class is not None:
            validated_feature = feature_class(**feature_data)
            # Convert back to dict for storage (this ensures proper structure)
            feature_data = json.loads(validated_feature.model_dump_json())

    except Exception as e:
        logger.error(f"Feature validation error for feature {feature_id}: {traceback.format_exc()}")
        return error_response(f'Feature validation failed: {str(e)}', 400)

    # Update the feature data
    feature.geojson = feature_data

    # Regenerate the hash for the updated feature
    feature.geojson_hash = generate_geojson_hash(feature_data)

    # Update the geometry field if coordinates changed
    try:
        geom_data = feature_data.get('geometry', {})
        if geom_data and geom_data.get('type'):
            # Handle GeometryCollection separately (not supported by GEOSGeometry)
            if geom_data['type'] == 'GeometryCollection':
                # For GeometryCollection, we can't use GEOSGeometry, so skip geometry field update
                # The geometry will be stored in the geojson field
                pass
            elif geom_data.get('coordinates'):
                # Ensure coordinates have 3 dimensions for consistency
                coords = geom_data['coordinates']
                if geom_data['type'] == 'Point':
                    if len(coords) == 2:
                        coords = [coords[0], coords[1], 0.0]
                    elif len(coords) == 3:
                        coords = [coords[0], coords[1], coords[2]]
                    geom_data['coordinates'] = coords
                elif geom_data['type'] == 'LineString':
                    geom_data['coordinates'] = [
                        [coord[0], coord[1], coord[2] if len(coord) > 2 else 0.0]
                        for coord in coords
                    ]
                elif geom_data['type'] == 'Polygon':
                    geom_data['coordinates'] = [
                        [
                            [coord[0], coord[1], coord[2] if len(coord) > 2 else 0.0]
                            for coord in ring
                        ]
                        for ring in coords
                    ]

                feature.geometry = GEOSGeometry(json.dumps(geom_data))
    except Exception as e:
        logger.warning(f"Error updating geometry for feature {feature_id}: {e}")
        # Continue without updating geometry if there's an error

    # Save the updated feature
    feature.save()

    return JsonResponse({
        'message': 'Feature updated successfully',
        'feature_id': feature.id
    })


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
@validate_payload(ReplacementGeometryPayload)
def apply_replacement_geometry(request, feature_id, validated_data):
    """
    API endpoint to apply replacement geometry from an ImportQueue entry to an existing feature.
    Only updates the geometry, preserving all properties (name, description, tags, styling, etc.).

    URL parameter:
    - feature_id: ID of the feature to update

    Request body: JSON object with:
    - import_queue_id: ID of the ImportQueue entry containing the replacement features
    - feature_index: Index of the feature in the ImportQueue.geofeatures array to use
    - regenerate_tags: (optional) Boolean, if True, regenerates tags based on the new geometry
    """
    # Get the feature from database
    feature = get_object_or_404_for_user(FeatureStore, request.user, id=feature_id)

    import_queue_id = validated_data['import_queue_id']
    feature_index = validated_data['feature_index']
    regenerate_tags = validated_data.get('regenerate_tags', False)

    # Get the ImportQueue entry
    import_queue = get_object_or_404_for_user(ImportQueue, request.user, id=import_queue_id)

    # Verify this is a replacement upload for this feature
    if import_queue.replacement != feature_id:
        return error_response('ImportQueue entry is not a replacement for this feature', 400)

    # Get the features from the ImportQueue
    geofeatures = import_queue.geofeatures
    if not isinstance(geofeatures, list) or len(geofeatures) == 0:
        return error_response('ImportQueue entry has no features', 400)

    # Validate feature_index is within bounds
    if feature_index < 0 or feature_index >= len(geofeatures):
        return error_response(f'feature_index {feature_index} is out of bounds (0-{len(geofeatures)-1})', 400)

    # Get the selected replacement feature
    replacement_feature = geofeatures[feature_index]
    if not isinstance(replacement_feature, dict) or 'geometry' not in replacement_feature:
        return error_response('Selected feature has invalid structure or missing geometry', 400)

    # Get the replacement geometry
    replacement_geometry = replacement_feature.get('geometry')
    if not replacement_geometry:
        return error_response('Selected feature has no geometry', 400)

    # Get original feature data
    original_geojson = feature.geojson.copy()
    original_properties = original_geojson.get('properties', {})
    
    # Validate that geometry type hasn't changed
    original_geometry_type = original_geojson.get('geometry', {}).get('type', '').lower()
    replacement_geometry_type = replacement_geometry.get('type', '').lower()
    
    if original_geometry_type != replacement_geometry_type:
        return error_response(
            f'Geometry type cannot change. Original: {original_geometry_type}, Replacement: {replacement_geometry_type}',
            400
        )

    # Create updated feature with replacement geometry but original properties
    updated_feature = {
        'type': 'Feature',
        'geometry': replacement_geometry,
        'properties': original_properties
    }
    
    # Generate temporary geojson_hash for the updated feature for validation purposes
    updated_feature.setdefault('properties', {})['geojson_hash'] = generate_geojson_hash(updated_feature)

    # Validate the updated feature
    try:
        feature_data = normalize_and_validate_feature_update(updated_feature, original_properties)
    except GeometryValidationError as e:
        return error_response(str(e), 400)

    # Validate feature structure using feature classes
    try:
        geom_type = feature_data.get('geometry', {}).get('type', '').lower()
        feature_class = None

        # GeometryCollection is not supported by the feature classes, but we allow it
        if geom_type == 'geometrycollection':
            # For GeometryCollection, we do basic validation but skip feature class validation
            geom_data = feature_data.get('geometry', {})
            if not geom_data.get('geometries') or not isinstance(geom_data.get('geometries'), list):
                return error_response('GeometryCollection must have a geometries array', 400)
            # Skip feature class validation for GeometryCollection
            feature_class = None
        else:
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

        # Validate by instantiating the feature class (this will raise ValidationError if invalid)
        # Skip for GeometryCollection as it's not supported by feature classes
        if feature_class is not None:
            validated_feature = feature_class(**feature_data)
            # Convert back to dict for storage (this ensures proper structure)
            feature_data = json.loads(validated_feature.model_dump_json())

    except Exception as e:
        logger.error(f"Feature validation error for replacement feature {feature_id}: {str(e)}")
        return error_response(f'Feature validation failed: {str(e)}', 400)

    # Validate and normalize the feature (including color/icon validation)
    try:
        feature_data = _validate_and_preserve_feature(feature_data)
    except GeometryValidationError as e:
        return error_response(f'Feature validation failed: {str(e)}', 400)

    # Update the feature's geometry (preserving all properties)
    feature.geojson = feature_data

    # Regenerate the hash for the updated feature
    feature.geojson_hash = generate_geojson_hash(feature_data)

    # Update the geometry field if coordinates changed
    try:
        geom_data = feature_data.get('geometry', {})
        if geom_data and geom_data.get('type'):
            # Handle GeometryCollection separately (not supported by GEOSGeometry)
            if geom_data['type'] == 'GeometryCollection':
                # For GeometryCollection, we can't use GEOSGeometry, so skip geometry field update
                # The geometry will be stored in the geojson field
                pass
            elif geom_data.get('coordinates'):
                # Ensure coordinates have 3 dimensions for consistency
                coords = geom_data['coordinates']
                if geom_data['type'] == 'Point':
                    if len(coords) == 2:
                        coords = [coords[0], coords[1], 0.0]
                    elif len(coords) == 3:
                        coords = [coords[0], coords[1], coords[2]]
                    geom_data['coordinates'] = coords
                elif geom_data['type'] == 'LineString':
                    geom_data['coordinates'] = [
                        [coord[0], coord[1], coord[2] if len(coord) > 2 else 0.0]
                        for coord in coords
                    ]
                elif geom_data['type'] == 'Polygon':
                    geom_data['coordinates'] = [
                        [
                            [coord[0], coord[1], coord[2] if len(coord) > 2 else 0.0]
                            for coord in ring
                        ]
                        for ring in coords
                    ]

                feature.geometry = GEOSGeometry(json.dumps(geom_data))
    except Exception as e:
        logger.warning(f"Error updating geometry for feature {feature_id}: {e}")
        # Continue without updating geometry if there's an error

    # Regenerate tags if requested (using the new geometry)
    if regenerate_tags:
        try:
            # Preserve original import-year and import-month tags from the original feature
            original_system_tags = _extract_system_tags(original_geojson)
            
            # Extract import-year and import-month tags from original system_tags
            preserved_import_tags = [
                tag for tag in original_system_tags
                if isinstance(tag, str) and (tag.startswith('import-year:') or tag.startswith('import-month:'))
            ]

            # Get the updated feature's geometry type
            geom_type = feature_data.get('geometry', {}).get('type', '').lower()
            tag_feature_class = None

            match geom_type:
                case 'point' | 'multipoint':
                    tag_feature_class = PointFeature
                case 'linestring':
                    tag_feature_class = LineStringFeature
                case 'multilinestring':
                    tag_feature_class = MultiLineStringFeature
                case 'polygon' | 'multipolygon':
                    tag_feature_class = PolygonFeature
                case _:
                    # Skip tag regeneration for unsupported geometry types (e.g., GeometryCollection)
                    logger.warning(f"Skipping tag regeneration for unsupported geometry type: {geom_type}")
                    tag_feature_class = None

            if tag_feature_class is not None:
                # Create feature instance with the updated geometry for tag generation
                try:
                    feature_instance: GeoFeatureSupported = tag_feature_class(**feature_data)
                except Exception as e:
                    logger.error(f"Error creating feature instance for tag regeneration {feature_id}: {str(e)}")
                    # Continue without regenerating tags if feature instance creation fails
                else:
                    # Get existing user tags (preserve them)
                    existing_user_tags = feature_data.get('properties', {}).get('tags', [])
                    if not isinstance(existing_user_tags, list):
                        existing_user_tags = []

                    # Generate new system tags based on the new geometry
                    new_system_tags = generate_auto_tags(feature_instance, import_log=None)

                    # Remove any import-year and import-month tags from new system tags
                    # (we'll add back the preserved ones)
                    new_system_tags = [
                        tag for tag in new_system_tags
                        if not (isinstance(tag, str) and (tag.startswith('import-year:') or tag.startswith('import-month:')))
                    ]

                    # Add back the preserved import-year and import-month tags
                    new_system_tags.extend(preserved_import_tags)

                    # Update the feature's tags - preserve user tags, regenerate system tags
                    if 'properties' not in feature_data:
                        feature_data['properties'] = {}
                    feature_data['properties']['tags'] = existing_user_tags
                    feature_data['properties']['system_tags'] = new_system_tags

                    # Validate and normalize the feature after tag regeneration
                    try:
                        # Temporarily set system_tags for validation
                        feature_data['properties']['system_tags'] = new_system_tags
                        feature_data = _validate_and_preserve_feature(feature_data)
                    except GeometryValidationError as e:
                        logger.error(f"Feature validation failed for feature {feature_id} during tag regeneration in replacement: {str(e)}")
                        # Continue without regenerating tags if validation fails
                        feature_data = feature.geojson  # Restore original

                    # Update the feature's geojson with regenerated tags
                    feature.geojson = feature_data
        except Exception as e:
            logger.error(f"Error regenerating tags for feature {feature_id}: {traceback.format_exc()}")
            # Continue without regenerating tags if there's an error

    # Save the updated feature
    feature.save()

    # Delete the ImportQueue row after successful application
    import_queue.delete()

    return JsonResponse({
        'message': 'Replacement geometry applied successfully',
        'feature_id': feature.id
    })


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
    except Exception as e:
        logger.error(f"Error creating feature instance for tag regeneration {feature_id}: {str(e)}")
        return error_response(f'Invalid feature structure: {str(e)}', 400)

    # Get existing user tags (preserve them)
    existing_user_tags = geojson_data.get('properties', {}).get('tags', [])
    if not isinstance(existing_user_tags, list):
        existing_user_tags = []

    # Generate new system tags
    new_system_tags = generate_auto_tags(feature_instance, import_log=None)

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
