import json
import traceback
from django.contrib.gis.geos import Point
from django.http import HttpResponse, JsonResponse
from django.views.decorators.http import require_http_methods
from django.views.decorators.csrf import csrf_exempt
from .models import ExampleItem

# Import platform utilities for feature operations
from api.models import FeatureStore
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response, success_response, handle_404
from api.views.features.updates.shared import extract_system_tags, _validate_and_preserve_feature, _validate_tags
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.reverse_geocoding.background_geocoding import reverse_geocode_feature_async
from geo_lib.processing.logging import ImportLog
from geo_lib.processing.tagging.generate import generate_auto_tags
from geo_lib.tags.const_strings import CONST_INTERNAL_TAGS, filter_protected_tags, prepare_user_tags
from geo_lib.types.feature import PointFeature
from geo_lib.validation.geojson.geojson_whitelist import validate_and_normalize_geojson_feature
from geo_lib.logging.console import get_tagged_logger
from geo_lib.validation.geometry_validation import GeometryValidationError
from geo_lib.website.auth import api_or_login_required_401

_logger = get_tagged_logger(__name__)

# ==============================================================================
# Extension Views (API Endpoints)
# ==============================================================================
# Views handle the business logic for extension-specific functionality.
# These will be automatically scoped under /api/extensions/<name>/

@api_or_login_required_401()
@require_http_methods(["GET", "POST"])
@csrf_exempt # For simplicity in this demo. For production, the platform provides CSRF utilities.
def item_list_create(request):
    """
    Handle fetching all items or creating a new one.
    """
    if request.method == "GET":
        # Standard Django QuerySet logic
        items = list(ExampleItem.objects.all().values('id', 'name', 'description'))
        return JsonResponse(items, safe=False)
    
    elif request.method == "POST":
        try:
            # Typical JSON request parsing
            data = json.loads(request.body)
            item = ExampleItem.objects.create(
                name=data.get('name', 'Unnamed Item'),
                description=data.get('description', '')
            )
            return JsonResponse({
                'id': item.id,
                'name': item.name,
                'description': item.description
            }, status=201)
        except Exception:
            _logger.error("Failed to create item:\n%s", traceback.format_exc())
            return JsonResponse({'error': 'Failed to create item'}, status=400)

@api_or_login_required_401()
@require_http_methods(["DELETE"])
@csrf_exempt
def item_delete(request, item_id):
    """
    Delete a specific item by ID.
    """
    try:
        item = ExampleItem.objects.get(id=item_id)
        item.delete()
        return HttpResponse(status=204)
    except ExampleItem.DoesNotExist:
        return JsonResponse({'error': 'Not found'}, status=404)


# ==============================================================================
# Geostore Feature CRUD Examples
# ==============================================================================
# These views demonstrate how extensions can create, modify, and delete
# geostore features (FeatureStore objects).

@api_or_login_required_401()
@require_http_methods(["POST"])
@csrf_exempt
def create_feature(request):
    """
    Create a new point feature in the geostore.
    
    Request body (JSON):
    - latitude: float (required, -90 to 90)
    - longitude: float (required, -180 to 180)
    - name: string (required)
    - description: string (optional)
    - tags: array of strings (optional)
    
    Returns:
    - feature: Created feature data with database_id
    """
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return error_response('Invalid JSON in request body', 400)
    
    # Extract and validate required fields
    latitude = data.get('latitude')
    longitude = data.get('longitude')
    name = data.get('name', '').strip()
    
    # Validate required fields
    if latitude is None or longitude is None:
        return error_response('latitude and longitude are required', 400)
    
    try:
        latitude = float(latitude)
        longitude = float(longitude)
    except (ValueError, TypeError):
        return error_response('latitude and longitude must be valid numbers', 400)
    
    if not (-90 <= latitude <= 90):
        return error_response('latitude must be between -90 and 90', 400)
    
    if not (-180 <= longitude <= 180):
        return error_response('longitude must be between -180 and 180', 400)
    
    if not name:
        return error_response('name is required', 400)
    
    # Extract optional fields
    description = data.get('description', '').strip()
    tags = data.get('tags', [])
    
    # Process tags if provided
    if tags:
        # Filter out system tags first (defensive - user shouldn't be able to add them)
        user_tags = filter_protected_tags(tags, CONST_INTERNAL_TAGS)
        
        # Validate remaining user tags
        if user_tags:
            is_valid, error_resp = _validate_tags(user_tags)
            if not is_valid:
                return error_resp
        
        # Prepare user tags (lowercase and deduplicate)
        user_tags = prepare_user_tags(user_tags)
    else:
        user_tags = []
    
    # Default elevation to 0.0 (can be enhanced to fetch from elevation API)
    elevation = 0.0
    
    # Create GeoJSON feature
    coordinates = [longitude, latitude, elevation]
    
    properties = {
        'name': name,
        'description': description,
        'marker-color': '#ff0000',  # Default red marker
        'tags': user_tags
    }
    
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
    except GeometryValidationError as e:
        return error_response(f'Feature validation failed: {str(e)}', 400)
    
    # Generate hash first (needed for PointFeature type)
    geojson_hash = generate_geojson_hash(normalized_feature)
    
    # Add geojson_hash to properties for PointFeature type validation
    if 'properties' not in normalized_feature:
        normalized_feature['properties'] = {}
    normalized_feature['properties']['geojson_hash'] = geojson_hash
    
    # Generate system tags using PointFeature type (skip reverse_geocoding for async processing)
    point_feature = PointFeature(**normalized_feature)
    system_tags = generate_auto_tags(point_feature, import_log=ImportLog(), filename='example-extension', skip_reverse_geocoding=True)
    
    # Add 'example-extension' system tag to identify features created via this extension
    if 'example-extension' not in system_tags:
        system_tags.append('example-extension')
    
    # Remove geojson_hash from properties (it's stored separately in FeatureStore)
    del normalized_feature['properties']['geojson_hash']
    
    # Set system tags in properties
    normalized_feature['properties']['system_tags'] = system_tags
    
    # Create geometry for spatial queries
    geometry = Point(longitude, latitude, elevation)
    
    # Save to database
    feature_store = FeatureStore.objects.create(
        user=request.user,
        geojson=normalized_feature,
        geometry=geometry,
        geojson_hash=geojson_hash
    )
    
    # Start background reverse geocoding (non-blocking)
    reverse_geocode_feature_async(feature_store.id)
    
    # Add database_id to properties for response
    normalized_feature['properties']['database_id'] = feature_store.id
    
    return success_response({
        'feature': normalized_feature
    }, status=201)


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
@csrf_exempt
def modify_feature(request, feature_id):
    """
    Modify an existing feature by adding a special tag "example-extension:special".
    
    URL parameter:
    - feature_id: ID of the feature to modify
    
    Returns:
    - feature: Modified feature data
    """
    # Get the feature from database
    feature = get_object_or_404_for_user(FeatureStore, request.user, id=feature_id)
    
    # Get original feature data
    original_geojson = feature.geojson
    if not isinstance(original_geojson, dict):
        return error_response('Invalid feature data in database', 500)
    
    # Preserve existing system_tags
    original_system_tags = extract_system_tags(original_geojson)
    
    # Get existing user tags
    existing_tags = original_geojson.get('properties', {}).get('tags', [])
    if not isinstance(existing_tags, list):
        existing_tags = []
    
    # Filter out system tags from existing tags (defensive)
    user_tags = filter_protected_tags(existing_tags, CONST_INTERNAL_TAGS)
    
    # Prepare user tags (lowercase and deduplicate)
    user_tags = prepare_user_tags(user_tags)
    
    # Add the special tag if not already present
    special_tag = 'example-extension:special'
    if special_tag not in user_tags:
        user_tags.append(special_tag)
        # Re-prepare to ensure lowercase and deduplication
        user_tags = prepare_user_tags(user_tags)
    
    # Create a copy of the feature for modification
    modified_feature = original_geojson.copy()
    if 'properties' not in modified_feature:
        modified_feature['properties'] = {}
    
    # Update tags
    modified_feature['properties']['tags'] = user_tags
    
    # Ensure system_tags are preserved
    modified_feature['properties']['system_tags'] = original_system_tags
    
    # Validate and normalize the modified feature
    try:
        normalized_feature = _validate_and_preserve_feature(modified_feature)
    except GeometryValidationError as e:
        return error_response(f'Feature validation failed: {str(e)}', 400)
    
    # Update the feature's geojson data
    feature.geojson = normalized_feature
    feature.save()
    
    # Add database_id to properties for response
    normalized_feature['properties']['database_id'] = feature.id
    
    return success_response({
        'feature': normalized_feature,
        'message': f'Feature modified: added tag "{special_tag}"'
    })


@api_or_login_required_401()
@require_http_methods(["DELETE"])
@handle_404
@csrf_exempt
def delete_feature(request, feature_id):
    """
    Delete a feature by ID.
    
    URL parameter:
    - feature_id: ID of the feature to delete
    
    Returns:
    - message: Success message
    - feature_id: ID of the deleted feature
    """
    # Get the feature from database
    feature = get_object_or_404_for_user(FeatureStore, request.user, id=feature_id)
    
    # Store ID for response
    deleted_id = feature.id
    
    # Delete the feature
    feature.delete()
    
    return success_response({
        'message': 'Feature deleted successfully',
        'feature_id': deleted_id
    })
