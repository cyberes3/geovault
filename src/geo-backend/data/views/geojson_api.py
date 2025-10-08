import time
import logging
import traceback
import json
from typing import List, Tuple, Dict

from django.contrib.gis.geos import Polygon, GEOSGeometry
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods
from django.views.decorators.csrf import csrf_protect
from django.conf import settings
from django.db.models import Q

from data.models import FeatureStore
from geo_lib.website.auth import login_required_401
from geo_lib.feature_id import generate_feature_hash

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
    """Get features within bounding box from database, handling world-wide extents that cross the International Date Line"""
    min_lon, min_lat, max_lon, max_lat = bbox

    # Check if this is a world-wide bbox that crosses the International Date Line
    # This happens when min_lon > max_lon (e.g., min_lon=134, max_lon=134 means we're crossing 180°/-180°)
    crosses_dateline = min_lon > max_lon

    # Get the maximum features limit from settings
    max_features = getattr(settings, 'MAX_FEATURES_PER_REQUEST', -1)

    if crosses_dateline:
        # Handle world-wide bbox that crosses the International Date Line
        # Split into two queries: one for the western hemisphere and one for the eastern hemisphere

        # Western hemisphere: from min_lon to 180°
        bbox_west = (min_lon, min_lat, 180.0, max_lat)
        bbox_polygon_west = Polygon.from_bbox(bbox_west)

        # Eastern hemisphere: from -180° to max_lon
        bbox_east = (-180.0, min_lat, max_lon, max_lat)
        bbox_polygon_east = Polygon.from_bbox(bbox_east)

        # Build queries for both hemispheres
        features_query_west = FeatureStore.objects.filter(
            user_id=user_id,
            geometry__intersects=bbox_polygon_west
        )

        features_query_east = FeatureStore.objects.filter(
            user_id=user_id,
            geometry__intersects=bbox_polygon_east
        )

        # Combine the queries using OR
        features_query = features_query_west.union(features_query_east)

    else:
        # Normal bbox that doesn't cross the International Date Line
        bbox_polygon = Polygon.from_bbox(bbox)
        features_query = FeatureStore.objects.filter(
            user_id=user_id,
            geometry__intersects=bbox_polygon
        )

    # Apply limit if configured (max_features = -1 means no limit)
    if max_features > 0:
        features = features_query[:max_features]
    else:
        features = features_query

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

        # Get the configured limit and total count for comparison
        max_features = getattr(settings, 'MAX_FEATURES_PER_REQUEST', -1)

        # Calculate total features in bbox, handling world-wide extents
        min_lon, min_lat, max_lon, max_lat = bbox
        crosses_dateline = min_lon > max_lon

        if crosses_dateline:
            # Handle world-wide bbox that crosses the International Date Line
            bbox_west = (min_lon, min_lat, 180.0, max_lat)
            bbox_east = (-180.0, min_lat, max_lon, max_lat)
            bbox_polygon_west = Polygon.from_bbox(bbox_west)
            bbox_polygon_east = Polygon.from_bbox(bbox_east)

            total_features_in_bbox = FeatureStore.objects.filter(
                Q(user_id=request.user.id, geometry__intersects=bbox_polygon_west) |
                Q(user_id=request.user.id, geometry__intersects=bbox_polygon_east)
            ).count()
        else:
            # Normal bbox
            bbox_polygon = Polygon.from_bbox(bbox)
            total_features_in_bbox = FeatureStore.objects.filter(
                user_id=request.user.id,
                geometry__intersects=bbox_polygon
            ).count()

        # Create GeoJSON FeatureCollection
        geojson_data = {
            "type": "FeatureCollection",
            "features": features
        }

        response_data = {
            'success': True,
            'data': geojson_data,
            'feature_count': len(features),
            'total_features_in_bbox': total_features_in_bbox,
            'max_features_limit': max_features,
            'zoom_level': zoom_level,
            'timestamp': time.time()
        }

        # Add warning if features were limited by configuration
        if max_features > 0 and total_features_in_bbox > max_features:
            response_data['warning'] = f'Displaying {len(features)} of {total_features_in_bbox} features due to MAX_FEATURES_PER_REQUEST limit ({max_features})'

        return JsonResponse(response_data)

    except Exception as e:
        logger.error(f"Error in get_geojson_data API: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to get features in bounding box',
            'code': 500
        }, status=500)


@login_required_401
@require_http_methods(["GET"])
def get_feature(request, feature_id):
    """
    API endpoint to get a specific feature by ID.

    URL parameter:
    - feature_id: ID of the feature to retrieve
    """
    try:
        # Get the feature from database
        feature = FeatureStore.objects.get(id=feature_id, user=request.user)

        # Return the feature data
        return JsonResponse({
            'success': True,
            'feature': {
                'id': feature.id,
                'geojson': feature.geojson,
                'timestamp': feature.timestamp.isoformat() if feature.timestamp else None
            }
        })

    except FeatureStore.DoesNotExist:
        return JsonResponse({
            'success': False,
            'error': 'Feature not found or access denied',
            'code': 404
        }, status=404)
    except Exception as e:
        logger.error(f"Error getting feature {feature_id}: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to get feature',
            'code': 500
        }, status=500)


@login_required_401
@csrf_protect
@require_http_methods(["PUT"])
def update_feature(request, feature_id):
    """
    API endpoint to update a specific feature.

    URL parameter:
    - feature_id: ID of the feature to update

    Request body: GeoJSON feature object
    """
    try:
        # Get the feature from database
        feature = FeatureStore.objects.get(id=feature_id, user=request.user)

        # Parse request body
        try:
            feature_data = json.loads(request.body)
        except json.JSONDecodeError:
            return JsonResponse({
                'success': False,
                'error': 'Invalid JSON in request body',
                'code': 400
            }, status=400)

        # Validate that it's a proper GeoJSON feature or geometry
        if not isinstance(feature_data, dict):
            return JsonResponse({
                'success': False,
                'error': 'Request body must be a valid GeoJSON object',
                'code': 400
            }, status=400)

        # Handle both Feature objects and geometry objects
        if feature_data.get('type') == 'Feature':
            # It's already a Feature object, use it as-is
            pass
        elif feature_data.get('type') in ['Point', 'LineString', 'Polygon', 'MultiPoint', 'MultiLineString', 'MultiPolygon']:
            # It's a geometry object, wrap it in a Feature object
            feature_data = {
                'type': 'Feature',
                'geometry': feature_data,
                'properties': feature_data.get('properties', {})
            }
        else:
            return JsonResponse({
                'success': False,
                'error': 'Request body must be a valid GeoJSON Feature or geometry object',
                'code': 400
            }, status=400)

        # Update the feature data
        feature.geojson = feature_data

        # Regenerate the hash for the updated feature
        feature.geojson_hash = generate_feature_hash(feature_data)

        # Update the geometry field if coordinates changed
        try:
            geom_data = feature_data.get('geometry', {})
            if geom_data and geom_data.get('type') and geom_data.get('coordinates'):
                # Ensure coordinates have 3 dimensions for consistency
                coords = geom_data['coordinates']
                if geom_data['type'] == 'Point':
                    if len(coords) == 2:
                        coords = [coords[0], coords[1], 0.0]
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
            'success': True,
            'message': 'Feature updated successfully',
            'feature_id': feature.id
        })

    except FeatureStore.DoesNotExist:
        return JsonResponse({
            'success': False,
            'error': 'Feature not found or access denied',
            'code': 404
        }, status=404)
    except Exception as e:
        logger.error(f"Error updating feature {feature_id}: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to update feature',
            'code': 500
        }, status=500)