"""Feature sharing operations"""
import uuid

from django.db.models import F
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import TagShare, CollectionShare, FeatureShare, FeatureStore
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response, success_response
from api.validation.feature_updates import validate_payload, FeatureSharePayload
from api.views.features.retrieval import (
    _extract_coordinates_with_elevation_from_geojson
)
from geo_lib.logging.console import get_tagged_logger
from geo_lib.website.auth import api_or_login_required_401

_logger = get_tagged_logger()


@api_or_login_required_401()
@require_http_methods(["POST"])
@validate_payload(FeatureSharePayload)
def create_feature_share(request, validated_data):
    """
    Create a new share link for a single feature.
    Always uses UUID4 for share_id.
    
    POST body:
    - feature_id: int (required) - The feature ID to share
    - allow_downloads: boolean (optional, default=False) - Whether to allow downloads
    """
    feature_id = validated_data['feature_id']

    # Verify feature exists and belongs to user
    feature = get_object_or_404_for_user(FeatureStore, request.user, id=feature_id)

    # Check if a share already exists for this feature
    existing_share = FeatureShare.objects.filter(feature=feature, user=request.user).first()
    if existing_share:
        # Return the existing share
        base_url = request.build_absolute_uri('/').rstrip('/')
        share_url = f"{base_url}/#/mapshare?id={existing_share.share_id}"
        
        return JsonResponse({
            'share_id': existing_share.share_id,
            'url': share_url,
            'created_at': existing_share.created_at.isoformat(),
            'allow_downloads': existing_share.allow_downloads
        })

    # Generate UUID4 share_id
    share_id = str(uuid.uuid4())
    # Ensure uniqueness (very unlikely but check anyway)
    while (TagShare.objects.filter(share_id=share_id).exists() or 
           CollectionShare.objects.filter(share_id=share_id).exists() or
           FeatureShare.objects.filter(share_id=share_id).exists()):
        share_id = str(uuid.uuid4())

    # Get allow_downloads from validated data
    allow_downloads = validated_data.get('allow_downloads', False)

    # Create new share
    feature_share = FeatureShare.objects.create(
        share_id=share_id,
        feature=feature,
        user=request.user,
        allow_downloads=allow_downloads
    )

    # Build full URL
    base_url = request.build_absolute_uri('/').rstrip('/')
    share_url = f"{base_url}/#/mapshare?id={feature_share.share_id}"

    return JsonResponse({
        'share_id': feature_share.share_id,
        'url': share_url,
        'created_at': feature_share.created_at.isoformat(),
        'allow_downloads': feature_share.allow_downloads
    })


@api_or_login_required_401()
@require_http_methods(["GET"])
def get_feature_share(request, feature_id):
    """
    Get the share information for a feature if it exists.
    
    Returns 404 if no share exists for this feature.
    """
    # Verify feature exists and belongs to user
    feature = get_object_or_404_for_user(FeatureStore, request.user, id=feature_id)

    # Get the share for this feature
    share = FeatureShare.objects.filter(feature=feature, user=request.user).first()
    if not share:
        return error_response('No share exists for this feature', code=404)

    # Build full URL
    base_url = request.build_absolute_uri('/').rstrip('/')
    share_url = f"{base_url}/#/mapshare?id={share.share_id}"

    return JsonResponse({
        'share_id': share.share_id,
        'url': share_url,
        'created_at': share.created_at.isoformat(),
        'access_count': share.access_count,
        'allow_downloads': share.allow_downloads
    })


@api_or_login_required_401()
@require_http_methods(["PATCH"])
def update_feature_share(request, feature_id):
    """
    Update the allow_downloads setting for a feature share.
    
    PATCH body:
    - allow_downloads: boolean (required) - Whether to allow downloads
    """
    try:
        import json
        data = json.loads(request.body)
        allow_downloads = data.get('allow_downloads')
        
        if allow_downloads is None:
            return error_response('allow_downloads is required', code=400)
        
        if not isinstance(allow_downloads, bool):
            return error_response('allow_downloads must be a boolean', code=400)
        
        # Verify feature exists and belongs to user
        feature = get_object_or_404_for_user(FeatureStore, request.user, id=feature_id)
        
        # Get the share for this feature
        share = FeatureShare.objects.filter(feature=feature, user=request.user).first()
        if not share:
            return error_response('No share exists for this feature', code=404)
        
        # Update allow_downloads
        share.allow_downloads = allow_downloads
        share.save()
        
        # Build full URL
        base_url = request.build_absolute_uri('/').rstrip('/')
        share_url = f"{base_url}/#/mapshare?id={share.share_id}"
        
        return JsonResponse({
            'share_id': share.share_id,
            'url': share_url,
            'created_at': share.created_at.isoformat(),
            'access_count': share.access_count,
            'allow_downloads': share.allow_downloads
        })
    except json.JSONDecodeError:
        return error_response('Invalid JSON in request body', code=400)
    except Exception as e:
        _logger.error(f"Error updating feature share: {e}")
        return error_response('Failed to update share', code=500)


@require_http_methods(["GET"])
def get_public_feature_share(request, share_id):
    """
    Public endpoint to get a single shared feature.
    No authentication required.
    Returns GeoJSON of the shared feature.
    Increments access_count on each successful access.
    """
    # Get the share
    share = FeatureShare.objects.filter(share_id=share_id).select_related('feature', 'user').first()
    if not share:
        return JsonResponse({
            'error': 'Invalid share link',
            'code': 404
        }, status=404)

    # Increment access count
    FeatureShare.objects.filter(share_id=share_id).update(access_count=F('access_count') + 1)

    # Return the feature as GeoJSON
    feature = share.feature
    
    # Ensure the feature has database_id in properties for frontend processing
    feature_geojson = feature.geojson.copy()
    if 'properties' not in feature_geojson:
        feature_geojson['properties'] = {}
    feature_geojson['properties']['database_id'] = feature.id
    
    # Build response with single feature
    return JsonResponse({
        'type': 'FeatureCollection',
        'features': [feature_geojson],
        'allow_downloads': share.allow_downloads
    })


@require_http_methods(["GET"])
def get_public_feature_elevations_internal(request, share_id):
    """
    Public endpoint to get elevations for a shared feature's coordinates from the stored GPS data.
    No authentication required.
    This returns the original elevation data that was imported with the feature (e.g., from GPX files).
    
    URL parameter:
    - share_id: Share ID of the feature share
    
    Returns:
    - coordinates: List of [lon, lat, elevation] arrays (elevation may be None if not stored)
    """
    # Get the share
    share = FeatureShare.objects.filter(share_id=share_id).select_related('feature').first()
    if not share:
        return JsonResponse({
            'error': 'Invalid share link',
            'code': 404
        }, status=404)
    
    # Extract coordinates from the feature's GeoJSON (with elevation if present)
    geojson_data = share.feature.geojson
    coordinates = _extract_coordinates_with_elevation_from_geojson(geojson_data)
    
    if not coordinates:
        return JsonResponse({
            'error': 'Feature does not contain LineString or MultiLineString geometry',
            'code': 400
        }, status=400)
    
    # Convert to [lon, lat, elevation] format, including elevation if present
    coordinates_with_elevations = []
    for coord_tuple in coordinates:
        lon, lat, elevation = coord_tuple
        if elevation is not None and elevation != 0.0:  # Exclude 0.0 as it's a placeholder
            coordinates_with_elevations.append([lon, lat, elevation])
        else:
            # No elevation data stored
            coordinates_with_elevations.append([lon, lat])
    
    return JsonResponse({
        'coordinates': coordinates_with_elevations
    })

