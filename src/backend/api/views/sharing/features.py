"""Feature sharing operations"""
import traceback

from django.db.models import F
from django.views.decorators.http import require_http_methods

from api.models import FeatureShare
from api.services.feature_service import FeatureService
from api.services.feature_serialization import geojson_feature_from_instance
from api.utils.responses import error_response, handle_404, success_response
from api.validation.decorators import validate_payload
from api.validation.payloads.sharing import UpdateFeatureSharePayload
from api.views.sharing.public_share import invalid_share_response
from api.views.sharing.utils import build_share_url, validate_share_id
from api.views.features.retrieval import (
    _extract_coordinates_with_elevation_from_geojson
)
from geo_lib.logging.console import get_tagged_logger
from website.auth_decorators import api_or_login_required_401

_logger = get_tagged_logger()


@api_or_login_required_401()
@require_http_methods(["GET"])
@handle_404
def get_feature_share(request, feature_id):
    """
    Get the share information for a feature if it exists.
    
    Returns 404 if no share exists for this feature.
    """
    # Verify feature exists, belongs to user, and is a main-map feature (extension-scoped
    # features like `places` are shared through their own extension API, not this one).
    feature = FeatureService.get_owned_feature_or_404(request.user, feature_id)

    # Get the share for this feature
    share = FeatureShare.objects.filter(feature=feature, user=request.user).first()
    if not share:
        return error_response('No share exists for this feature', code=404)

    # Build full URL using configured site domain
    share_url = build_share_url(request, share.share_id)

    return success_response({
        'share_id': share.share_id,
        'url': share_url,
        'created_at': share.created_at.isoformat(),
        'access_count': share.access_count,
        'allow_downloads': share.allow_downloads,
        'include_tags': share.include_tags
    })


@api_or_login_required_401()
@require_http_methods(["PATCH"])
@handle_404
@validate_payload(UpdateFeatureSharePayload)
def update_feature_share(request, feature_id, validated_data):
    """
    Update the allow_downloads and/or include_tags settings for a feature share.

    PATCH body (at least one required):
    - allow_downloads: boolean - Whether to allow downloads
    - include_tags: boolean - Whether to include tags in the shared feature
    """
    allow_downloads = validated_data.get('allow_downloads')
    include_tags = validated_data.get('include_tags')

    # Verify feature exists, belongs to user, and is a main-map feature (matches the
    # scope guard get_feature_share above enforces via FeatureService).
    feature = FeatureService.get_owned_feature_or_404(request.user, feature_id)

    try:
        # Get the share for this feature
        share = FeatureShare.objects.filter(feature=feature, user=request.user).first()
        if not share:
            return error_response('No share exists for this feature', code=404)

        # Update whichever fields were provided
        if allow_downloads is not None:
            share.allow_downloads = allow_downloads
        if include_tags is not None:
            share.include_tags = include_tags
        share.save()

        # Build full URL using configured site domain
        share_url = build_share_url(request, share.share_id)

        return success_response({
            'share_id': share.share_id,
            'url': share_url,
            'created_at': share.created_at.isoformat(),
            'access_count': share.access_count,
            'allow_downloads': share.allow_downloads,
            'include_tags': share.include_tags
        })
    except Exception:
        _logger.error("Error updating feature share: %s", traceback.format_exc())
        return error_response('Failed to update share', code=500)


@require_http_methods(["GET"])
def get_public_feature_share(request, share_id):
    """
    Public endpoint to get a single shared feature.
    No authentication required.
    Returns GeoJSON of the shared feature.
    Increments access_count on each successful access.
    """
    # Validate share_id format (must be UUID4)
    if not validate_share_id(share_id):
        return invalid_share_response()

    # Get the share
    share = FeatureShare.objects.filter(share_id=share_id).select_related('feature', 'user').first()
    if not share:
        return invalid_share_response()

    # Increment access count
    FeatureShare.objects.filter(share_id=share_id).update(access_count=F('access_count') + 1)

    # Build the feature as GeoJSON, stripping tags/system_tags unless the sharer opted in
    feature_geojson = geojson_feature_from_instance(
        share.feature,
        public_safe=True,
        include_tags=share.include_tags,
    )

    # Build response with single feature
    return success_response({
        'type': 'FeatureCollection',
        'features': [feature_geojson] if feature_geojson else [],
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
    # Validate share_id format (must be UUID4)
    if not validate_share_id(share_id):
        return invalid_share_response()

    # Get the share
    share = FeatureShare.objects.filter(share_id=share_id).select_related('feature').first()
    if not share:
        return invalid_share_response()
    
    # Extract coordinates from the feature's GeoJSON (with elevation if present)
    geojson_data = share.feature.geojson
    coordinates = _extract_coordinates_with_elevation_from_geojson(geojson_data)
    
    if not coordinates:
        return error_response('Feature does not contain LineString or MultiLineString geometry', code=400)
    
    # Convert to [lon, lat, elevation] format, including elevation if present
    coordinates_with_elevations = []
    for coord_tuple in coordinates:
        lon, lat, elevation = coord_tuple
        if elevation is not None and elevation != 0.0:  # Exclude 0.0 as it's a placeholder
            coordinates_with_elevations.append([lon, lat, elevation])
        else:
            # No elevation data stored
            coordinates_with_elevations.append([lon, lat])
    
    return success_response({
        'coordinates': coordinates_with_elevations
    })

