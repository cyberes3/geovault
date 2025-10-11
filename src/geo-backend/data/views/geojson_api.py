import json
import logging
import time
import traceback
from typing import List, Tuple, Dict, NamedTuple

from django.conf import settings
from django.contrib.gis.geos import Polygon, GEOSGeometry
from django.db.models import Q
from django.http import JsonResponse
from django.views.decorators.csrf import csrf_protect
from django.views.decorators.http import require_http_methods

from data.models import FeatureStore
from geo_lib.feature_id import generate_feature_hash
from geo_lib.website.auth import login_required_401

logger = logging.getLogger(__name__)


class BboxQueryResult(NamedTuple):
    """Result of a bounding box query containing features and total count"""
    features: List[Dict]
    total_count: int


def _parse_bbox(bbox_str: str) -> tuple[float, ...] | None:
    """Parse bounding box string into tuple of floats"""
    try:
        parts = bbox_str.split(',')
        if len(parts) != 4:
            return None
        return tuple(float(x.strip()) for x in parts)
    except (ValueError, AttributeError):
        return None


def _get_features_in_bbox(bbox: Tuple[float, float, float, float], user_id: int, zoom_level: int) -> BboxQueryResult:
    """
    Get features within bounding box from database, handling world-wide extents that cross the International Date Line.
    Returns both the features and the total count in a single optimized operation.
    """
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

        # Build the base query for both hemispheres
        base_query = FeatureStore.objects.filter(
            Q(user_id=user_id, geometry__intersects=bbox_polygon_west) |
            Q(user_id=user_id, geometry__intersects=bbox_polygon_east)
        )

    else:
        # Normal bbox that doesn't cross the International Date Line
        bbox_polygon = Polygon.from_bbox(bbox)
        base_query = FeatureStore.objects.filter(
            user_id=user_id,
            geometry__intersects=bbox_polygon
        )

    # Get total count first (this is a lightweight operation)
    total_count = base_query.count()

    # Apply limit if configured (max_features = -1 means no limit)
    if max_features > 0:
        features_query = base_query[:max_features]
    else:
        features_query = base_query

    # Convert to GeoJSON format
    geojson_features = []
    for feature in features_query:
        geojson_data = feature.geojson
        if geojson_data and 'geometry' in geojson_data:
            # Wrap the data in a proper GeoJSON Feature structure
            geojson_feature = {
                "type": "Feature",
                "geometry": geojson_data.get('geometry'),
                "properties": geojson_data.get('properties', {}),
                "geojson_hash": feature.geojson_hash
            }
            geojson_features.append(geojson_feature)

    return BboxQueryResult(features=geojson_features, total_count=total_count)


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

    # Fetch data from database with optimized single query
    try:
        query_result = _get_features_in_bbox(bbox, request.user.id, zoom_level)
        features = query_result.features
        total_features_in_bbox = query_result.total_count

        # Get the configured limit for comparison
        max_features = getattr(settings, 'MAX_FEATURES_PER_REQUEST', -1)

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
def update_feature_metadata(request, feature_id):
    """
    API endpoint to update only the metadata of a specific feature (name, description, tags, created date).
    Does not modify geometry or geojson_hash.

    URL parameter:
    - feature_id: ID of the feature to update

    Request body: JSON object with optional fields:
    - name: string
    - description: string  
    - tags: array of strings
    - created: datetime string (ISO format)
    """
    try:
        # Get the feature from database
        feature = FeatureStore.objects.get(id=feature_id, user=request.user)

        # Parse request body
        try:
            metadata = json.loads(request.body)
        except json.JSONDecodeError:
            return JsonResponse({
                'success': False,
                'error': 'Invalid JSON in request body',
                'code': 400
            }, status=400)

        # Validate that it's a proper object
        if not isinstance(metadata, dict):
            return JsonResponse({
                'success': False,
                'error': 'Request body must be a valid JSON object',
                'code': 400
            }, status=400)

        # Update only the specified metadata fields
        updated_fields = []
        geojson_data = feature.geojson.copy()

        if 'name' in metadata:
            if not isinstance(metadata['name'], str):
                return JsonResponse({
                    'success': False,
                    'error': 'name must be a string',
                    'code': 400
                }, status=400)
            geojson_data['properties']['name'] = metadata['name']
            updated_fields.append('name')

        if 'description' in metadata:
            if not isinstance(metadata['description'], str):
                return JsonResponse({
                    'success': False,
                    'error': 'description must be a string',
                    'code': 400
                }, status=400)
            geojson_data['properties']['description'] = metadata['description']
            updated_fields.append('description')

        if 'tags' in metadata:
            if not isinstance(metadata['tags'], list):
                return JsonResponse({
                    'success': False,
                    'error': 'tags must be an array',
                    'code': 400
                }, status=400)
            # Validate that all tags are strings
            for tag in metadata['tags']:
                if not isinstance(tag, str):
                    return JsonResponse({
                        'success': False,
                        'error': 'all tags must be strings',
                        'code': 400
                    }, status=400)
            geojson_data['properties']['tags'] = metadata['tags']
            updated_fields.append('tags')

        if 'created' in metadata:
            if not isinstance(metadata['created'], str):
                return JsonResponse({
                    'success': False,
                    'error': 'created must be a string',
                    'code': 400
                }, status=400)
            # Validate datetime format
            try:
                from datetime import datetime
                datetime.fromisoformat(metadata['created'].replace('Z', '+00:00'))
            except ValueError:
                return JsonResponse({
                    'success': False,
                    'error': 'created must be a valid ISO datetime string',
                    'code': 400
                }, status=400)
            geojson_data['properties']['created'] = metadata['created']
            updated_fields.append('created')

        if not updated_fields:
            return JsonResponse({
                'success': False,
                'error': 'No valid fields to update. Supported fields: name, description, tags, created',
                'code': 400
            }, status=400)

        # Update the feature's geojson data
        feature.geojson = geojson_data
        feature.save()

        return JsonResponse({
            'success': True,
            'message': f'Feature metadata updated successfully. Updated fields: {", ".join(updated_fields)}',
            'feature_id': feature.id,
            'updated_fields': updated_fields
        })

    except FeatureStore.DoesNotExist:
        return JsonResponse({
            'success': False,
            'error': 'Feature not found or access denied',
            'code': 404
        }, status=404)
    except Exception as e:
        logger.error(f"Error updating feature metadata {feature_id}: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to update feature metadata',
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
