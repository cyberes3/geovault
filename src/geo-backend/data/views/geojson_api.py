import time
import logging
import traceback
from typing import List, Tuple, Dict

from django.contrib.gis.geos import Polygon
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from data.models import FeatureStore
from geo_lib.website.auth import login_required_401

logger = logging.getLogger(__name__)


def _parse_bbox(bbox_str: str) -> tuple[float, ...] | None:
    """Parse bounding box string into tuple of floats"""
    try:
        parts = bbox_str.split(',')
        if len(parts) != 4:
            return None
        return tuple(float(x.strip()) for x in parts)
    except (ValueError, AttributeError):
        return None


def _get_features_in_bbox(bbox: Tuple[float, float, float, float], user_id: int, zoom_level: int) -> List[Dict]:
    """Get features within bounding box from database"""
    min_lon, min_lat, max_lon, max_lat = bbox

    # Create a polygon from the bounding box
    bbox_polygon = Polygon.from_bbox(bbox)

    # Adjust query based on zoom level for performance
    if zoom_level < 8:
        # Low zoom: only get a subset of features to avoid overwhelming the client
        features = FeatureStore.objects.filter(
            user_id=user_id,
            geometry__intersects=bbox_polygon
        )[:100]  # Limit to 100 features for low zoom
    elif zoom_level < 12:
        # Medium zoom: get more features but still limit
        features = FeatureStore.objects.filter(
            user_id=user_id,
            geometry__intersects=bbox_polygon
        )[:500]  # Limit to 500 features for medium zoom
    else:
        # High zoom: get all features in the area
        features = FeatureStore.objects.filter(
            user_id=user_id,
            geometry__intersects=bbox_polygon
        )

    # Convert to GeoJSON format
    geojson_features = []
    for feature in features:
        geojson_data = feature.geojson
        if geojson_data and 'geometry' in geojson_data:
            # Wrap the data in a proper GeoJSON Feature structure
            geojson_feature = {
                "type": "Feature",
                "geometry": geojson_data.get('geometry'),
                "properties": geojson_data.get('properties', {})
            }
            geojson_features.append(geojson_feature)

    return geojson_features


@login_required_401
@require_http_methods(["GET"])
def get_geojson_data(request):
    """
    API endpoint to fetch GeoJSON data for a given bounding box.
    
    Query parameters:
    - bbox: comma-separated bounding box (min_lon,min_lat,max_lon,max_lat)
    - zoom: zoom level (integer, 1-20)
    """
    # Get query parameters
    bbox_str = request.GET.get('bbox')
    zoom_str = request.GET.get('zoom', '10')

    # Validate bbox parameter
    if not bbox_str:
        return JsonResponse({
            'success': False,
            'error': 'bbox parameter is required',
            'code': 400
        }, status=400)

    bbox = _parse_bbox(bbox_str)
    if not bbox:
        return JsonResponse({
            'success': False,
            'error': 'Invalid bbox format. Expected: min_lon,min_lat,max_lon,max_lat',
            'code': 400
        }, status=400)

    # Validate zoom parameter
    try:
        zoom_level = int(zoom_str)
        if zoom_level < 1 or zoom_level > 20:
            raise ValueError("Zoom level out of range")
    except ValueError:
        return JsonResponse({
            'success': False,
            'error': 'Invalid zoom level. Expected integer between 1 and 20',
            'code': 400
        }, status=400)

    # Fetch data from database
    try:
        features = _get_features_in_bbox(bbox, request.user.id, zoom_level)

        # Create GeoJSON FeatureCollection
        geojson_data = {
            "type": "FeatureCollection",
            "features": features
        }

        return JsonResponse({
            'success': True,
            'data': geojson_data,
            'feature_count': len(features),
            'timestamp': time.time()
        })

    except Exception as e:
        logger.error(f"Error in get_geojson_data API: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to get features in bounding box',
            'code': 500
        }, status=500)
