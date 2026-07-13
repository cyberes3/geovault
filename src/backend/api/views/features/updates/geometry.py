"""Geometry update and replacement operations"""
import json
import traceback

from django.contrib.gis.geos import GEOSGeometry
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore, ImportQueue
from api.services.feature_service import FeatureService
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response, handle_404
from api.validation.feature_updates import validate_payload, ReplacementGeometryPayload
from api.views.features.updates.shared import (
    _validate_tags,
    extract_system_tags,
    _validate_and_preserve_feature,
    _validate_and_preserve_system_tags
)
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.logging import ImportLog
from geo_lib.processing.tagging.generate import generate_auto_tags
from geo_lib.processing.tagging.modules.feature_date import update_feature_date_tags
from geo_lib.tags.const_strings import CONST_INTERNAL_TAGS, filter_protected_tags, prepare_user_tags
from geo_lib.types.feature import (
    PointFeature,
    LineStringFeature,
    MultiLineStringFeature,
    PolygonFeature,
    GeoFeatureSupported
)
from geo_lib.validation.geometry_validation import (
    normalize_and_validate_feature_update,
    GeometryValidationError
)
from geo_lib.validation.styling_validation import is_valid_icon_url
from geo_lib.website.auth import api_or_login_required_401

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
    # Get the feature from database
    feature = FeatureService.get_owned_feature_or_404(request.user, feature_id)

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
    original_system_tags = extract_system_tags(original_geojson)

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
                _logger.warning(f"Attempted to manually change icon URL for feature {feature_id}, restored original")
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
                _logger.warning(f"Attempted to set external icon URL for feature {feature_id}, removed (only built-in and uploaded icons allowed)")

    # Note: stroke-width, fill, and fill-opacity normalization is now handled by validate_and_normalize_geojson_feature
    # The normalization function ensures stroke-width=2 for lines/polygons and proper fill/fill-opacity for polygons
    # Color validation (invalid colors set to default red) is also handled by validate_and_normalize_geojson_feature

    # Ensure geojson_hash is present for Pydantic validation
    # Generate temporary hash if not present (will be regenerated later)
    feature_data.setdefault('properties', {})['geojson_hash'] = generate_geojson_hash(feature_data)

    # Validate feature structure using the same validation as import conversion
    is_valid, validated_data, error_msg = _validate_feature_with_class(feature_data, feature_id)
    if not is_valid:
        return error_response(error_msg, 400)
    feature_data = validated_data

    # Update the feature data
    feature.geojson = feature_data

    # Regenerate the hash for the updated feature
    feature.geojson_hash = generate_geojson_hash(feature_data)

    # Update the geometry field if coordinates changed
    _update_feature_geometry_field(feature, feature_data, feature_id)

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
    feature = FeatureService.get_owned_feature_or_404(request.user, feature_id)

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
        return error_response(f'feature_index {feature_index} is out of bounds (0-{len(geofeatures) - 1})', 400)

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
    is_valid, validated_data, error_msg = _validate_feature_with_class(feature_data, feature_id)
    if not is_valid:
        return error_response(error_msg, 400)
    feature_data = validated_data

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
    _update_feature_geometry_field(feature, feature_data, feature_id)

    # Regenerate tags if requested (using the new geometry)
    if regenerate_tags:
        try:
            # Preserve original import-year and import-month tags from the original feature
            original_system_tags = extract_system_tags(original_geojson)

            # Extract import-year and import-month tags from original system_tags
            preserved_import_tags = [
                tag for tag in original_system_tags
                if isinstance(tag, str) and (tag.startswith('import-year:') or tag.startswith('import-month:'))
            ]

            # Get the updated feature's geometry type
            geom_type = feature_data.get('geometry', {}).get('type', '').lower()
            tag_feature_class = _get_feature_class_for_geometry_type(geom_type)

            if tag_feature_class is None:
                # Skip tag regeneration for unsupported geometry types (e.g., GeometryCollection)
                _logger.warning(f"Skipping tag regeneration for unsupported geometry type: {geom_type}")

            if tag_feature_class is not None:
                # Create feature instance with the updated geometry for tag generation
                try:
                    feature_instance: GeoFeatureSupported = tag_feature_class(**feature_data)
                except Exception:
                    _logger.error(
                        "Error creating feature instance for tag regeneration %s:\n%s",
                        feature_id,
                        traceback.format_exc()
                    )
                    # Continue without regenerating tags if feature instance creation fails
                else:
                    # Get existing user tags (preserve them)
                    existing_user_tags = feature_data.get('properties', {}).get('tags', [])
                    if not isinstance(existing_user_tags, list):
                        existing_user_tags = []

                    # Generate new system tags based on the new geometry
                    new_system_tags = generate_auto_tags(feature_instance, import_log=ImportLog())

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
                        _logger.error(f"Feature validation failed for feature {feature_id} during tag regeneration in replacement: {str(e)}")
                        # Continue without regenerating tags if validation fails
                        feature_data = feature.geojson  # Restore original

                    # Update the feature's geojson with regenerated tags
                    feature.geojson = feature_data
        except:
            _logger.error(f"Error regenerating tags for feature {feature_id}: {traceback.format_exc()}")
            # Continue without regenerating tags if there's an error

    # Save the updated feature
    feature.save()

    # Delete the ImportQueue row after successful application
    import_queue.delete()

    return JsonResponse({
        'message': 'Replacement geometry applied successfully',
        'feature_id': feature.id
    })


def _get_feature_class_for_geometry_type(geom_type: str):
    """
    Get the appropriate feature class for a given geometry type.
    
    Args:
        geom_type: Geometry type string (lowercase)
        
    Returns:
        Feature class or None for unsupported types
    """
    match geom_type:
        case 'point' | 'multipoint':
            return PointFeature
        case 'linestring':
            return LineStringFeature
        case 'multilinestring':
            return MultiLineStringFeature
        case 'polygon' | 'multipolygon':
            return PolygonFeature
        case _:
            return None


def _validate_feature_with_class(feature_data: dict, feature_id: str = None) -> tuple[bool, dict | None, str | None]:
    """
    Validate a feature using the appropriate feature class based on geometry type.
    
    Args:
        feature_data: Feature data dictionary
        feature_id: Optional feature ID for logging
        
    Returns:
        Tuple of (is_valid, validated_feature_data or None, error_message or None)
    """
    geom_type = feature_data.get('geometry', {}).get('type', '').lower()

    # GeometryCollection is not supported by the feature classes, but we allow it
    if geom_type == 'geometrycollection':
        # For GeometryCollection, we do basic validation but skip feature class validation
        geom_data = feature_data.get('geometry', {})
        if not geom_data.get('geometries') or not isinstance(geom_data.get('geometries'), list):
            return False, None, 'GeometryCollection must have a geometries array'
        # Skip feature class validation for GeometryCollection
        return True, feature_data, None

    feature_class = _get_feature_class_for_geometry_type(geom_type)

    if feature_class is None:
        return False, None, f'Unsupported geometry type: {geom_type}'

    try:
        # Validate by instantiating the feature class (this will raise ValidationError if invalid)
        validated_feature = feature_class(**feature_data)
        # Convert back to dict for storage (this ensures proper structure)
        feature_data = json.loads(validated_feature.model_dump_json())
        return True, feature_data, None
    except Exception:
        _logger.error(
            "Feature validation error for feature %s:\n%s",
            feature_id or "unknown",
            traceback.format_exc()
        )
        return False, None, 'Feature validation failed'


def _normalize_geometry_coordinates(geom_data: dict) -> dict:
    """
    Ensure coordinates have 3 dimensions for consistency.
    
    Args:
        geom_data: Geometry data dictionary with 'type' and 'coordinates'
        
    Returns:
        Geometry data with normalized coordinates
    """
    if not geom_data or not geom_data.get('type') or not geom_data.get('coordinates'):
        return geom_data

    coords = geom_data['coordinates']
    geom_type = geom_data['type']

    if geom_type == 'Point':
        if len(coords) == 2:
            coords = [coords[0], coords[1], 0.0]
        elif len(coords) == 3:
            coords = [coords[0], coords[1], coords[2]]
        geom_data['coordinates'] = coords
    elif geom_type == 'LineString':
        geom_data['coordinates'] = [
            [coord[0], coord[1], coord[2] if len(coord) > 2 else 0.0]
            for coord in coords
        ]
    elif geom_type == 'Polygon':
        geom_data['coordinates'] = [
            [
                [coord[0], coord[1], coord[2] if len(coord) > 2 else 0.0]
                for coord in ring
            ]
            for ring in coords
        ]

    return geom_data


def _update_feature_geometry_field(feature: FeatureStore, feature_data: dict, feature_id: str):
    """
    Update the Django geometry field from the feature data.
    
    Args:
        feature: FeatureStore instance
        feature_data: Feature data dictionary
        feature_id: Feature ID for logging
    """
    try:
        geom_data = feature_data.get('geometry', {})
        if geom_data and geom_data.get('type'):
            # Handle GeometryCollection separately (not supported by GEOSGeometry)
            if geom_data['type'] == 'GeometryCollection':
                # For GeometryCollection, we can't use GEOSGeometry, so skip geometry field update
                # The geometry will be stored in the geojson field
                pass
            elif geom_data.get('coordinates'):
                # Normalize coordinates to 3D
                geom_data = _normalize_geometry_coordinates(geom_data)
                feature.geometry = GEOSGeometry(json.dumps(geom_data))
    except Exception:
        _logger.error("Error updating geometry for feature %s:\n%s", feature_id, traceback.format_exc())
        # Continue without updating geometry if there's an error
