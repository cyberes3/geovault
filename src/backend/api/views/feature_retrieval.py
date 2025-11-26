import traceback
from typing import List, Tuple, Optional

import requests
from django.conf import settings
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore
from geo_lib.logging.console import get_access_logger
from geo_lib.website.auth import login_required_401

logger = get_access_logger()

# Maximum points per API request (API limit is ~10,000, we use 10,000 to be safe)
MAX_POINTS_PER_REQUEST = 10000


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

        # Include database ID in properties for frontend editing (same as _get_features_in_bbox)
        geojson_data = feature.geojson.copy()
        if geojson_data and 'properties' in geojson_data:
            geojson_data['properties']['_id'] = feature.id

        # Return the feature data
        return JsonResponse({
            'feature': {
                'id': feature.id,
                'geojson': geojson_data,
                'geojson_hash': feature.file_hash,
                'timestamp': feature.timestamp.isoformat() if feature.timestamp else None
            }
        })

    except FeatureStore.DoesNotExist:
        return JsonResponse({
            'error': 'Feature not found or access denied',
            'code': 404
        }, status=404)
    except Exception:
        logger.error(f"Error getting feature {feature_id}: {traceback.format_exc()}")
        return JsonResponse({
            'error': 'Failed to get feature',
            'code': 500
        }, status=500)


def _extract_coordinates_from_geojson(geojson_data: dict) -> List[Tuple[float, float]]:
    """
    Extract all coordinates from a GeoJSON feature.
    Returns a list of (lon, lat) tuples.
    """
    coordinates_list = []
    geometry = geojson_data.get('geometry', {})
    geom_type = geometry.get('type', '').lower()
    coords = geometry.get('coordinates', [])
    
    if geom_type == 'linestring':
        # LineString: [[lon, lat], [lon, lat], ...] or [[lon, lat, ele], ...]
        for coord in coords:
            if len(coord) >= 2:
                coordinates_list.append((float(coord[0]), float(coord[1])))
    elif geom_type == 'multilinestring':
        # MultiLineString: [[[lon, lat], ...], [[lon, lat], ...], ...]
        for line in coords:
            if isinstance(line, list):
                for coord in line:
                    if len(coord) >= 2:
                        coordinates_list.append((float(coord[0]), float(coord[1])))
    
    return coordinates_list


def _fetch_elevations_from_api(coordinates: List[Tuple[float, float]]) -> List[Optional[float]]:
    """
    Fetch elevations from external elevation API for given coordinates.
    
    Args:
        coordinates: List of (lon, lat) tuples
        
    Returns:
        List of elevation values (in meters) or None for failed requests
    """
    # Check if elevation API is enabled
    if not getattr(settings, 'ELEVATION_API_ENABLED', True):
        logger.warning("Elevation API is disabled")
        return [None] * len(coordinates)
    
    api_url = getattr(settings, 'ELEVATION_API_URL', 'https://elevation.racemap.com/api')
    api_timeout = getattr(settings, 'ELEVATION_API_TIMEOUT', 30)
    
    # Convert coordinates to API format: [lat, lon]
    api_coords = [[lat, lon] for lon, lat in coordinates]
    
    # Fetch elevations in batches
    elevations: List[Optional[float]] = []
    
    for batch_start in range(0, len(api_coords), MAX_POINTS_PER_REQUEST):
        batch_end = min(batch_start + MAX_POINTS_PER_REQUEST, len(api_coords))
        batch_coords = api_coords[batch_start:batch_end]
        
        try:
            response = requests.post(
                api_url,
                json=batch_coords,
                headers={'Content-Type': 'application/json'},
                timeout=api_timeout
            )
            response.raise_for_status()
            
            batch_elevations = response.json()
            if not isinstance(batch_elevations, list):
                logger.warning(f"Unexpected API response format for batch starting at {batch_start}")
                elevations.extend([None] * len(batch_coords))
                continue
            
            if len(batch_elevations) != len(batch_coords):
                logger.warning(f"API returned {len(batch_elevations)} elevations for {len(batch_coords)} points")
                elevations.extend([None] * len(batch_coords))
                continue
            
            # Convert elevations to float
            for elevation in batch_elevations:
                if isinstance(elevation, (int, float)):
                    elevations.append(float(elevation))
                else:
                    elevations.append(None)
                    
        except requests.exceptions.Timeout:
            logger.error(f"Elevation API request timed out after {api_timeout}s for batch starting at {batch_start}")
            elevations.extend([None] * len(batch_coords))
        except requests.exceptions.RequestException as e:
            logger.error(f"Elevation API request failed for batch starting at {batch_start}: {str(e)}")
            elevations.extend([None] * len(batch_coords))
        except Exception as e:
            logger.error(f"Unexpected error fetching elevation data for batch starting at {batch_start}: {str(e)}")
            elevations.extend([None] * len(batch_coords))
    
    return elevations


@login_required_401
@require_http_methods(["GET"])
def get_feature_elevations(request, feature_id):
    """
    API endpoint to get elevations for a feature's coordinates from the external elevation API.
    
    URL parameter:
    - feature_id: ID of the feature to get elevations for
    
    Returns:
    - coordinates: List of [lon, lat, elevation] arrays
    """
    try:
        # Get the feature from database and verify user ownership
        feature = FeatureStore.objects.get(id=feature_id, user=request.user)
        
        # Extract coordinates from the feature's GeoJSON
        geojson_data = feature.geojson
        coordinates = _extract_coordinates_from_geojson(geojson_data)
        
        if not coordinates:
            return JsonResponse({
                'error': 'Feature does not contain LineString or MultiLineString geometry',
                'code': 400
            }, status=400)
        
        # Fetch elevations from API
        elevations = _fetch_elevations_from_api(coordinates)
        
        # Combine coordinates with elevations: [lon, lat, elevation]
        coordinates_with_elevations = []
        for (lon, lat), elevation in zip(coordinates, elevations):
            if elevation is not None:
                coordinates_with_elevations.append([lon, lat, elevation])
            else:
                # If elevation fetch failed, include coordinate without elevation
                coordinates_with_elevations.append([lon, lat])
        
        return JsonResponse({
            'coordinates': coordinates_with_elevations
        })
        
    except FeatureStore.DoesNotExist:
        return JsonResponse({
            'error': 'Feature not found or access denied',
            'code': 404
        }, status=404)
    except Exception:
        logger.error(f"Error getting elevations for feature {feature_id}: {traceback.format_exc()}")
        return JsonResponse({
            'error': 'Failed to get feature elevations',
            'code': 500
        }, status=500)
