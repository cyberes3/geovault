import hashlib
import json
import time
from typing import Dict, List, Optional, Tuple

import redis
from django.contrib.gis.geos import Polygon
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from data.models import FeatureStore
from geo_lib.website.auth import login_required_401


class GeoJSONCache:
    """Cache manager for GeoJSON data with size limits and TTL"""

    def __init__(self, max_cache_size_mb: int = 100, default_ttl: int = 3600):
        self.max_cache_size_mb = max_cache_size_mb
        self.default_ttl = default_ttl
        self.redis_client = redis.Redis(host='localhost', port=6379, db=1)  # Use db=1 for API cache
        self.cache_metadata_key = "geojson_cache_metadata"

    def _get_cache_key(self, bbox: Tuple[float, float, float, float], zoom_level: int, user_id: int) -> str:
        """Generate a cache key based on bounding box, zoom level, and user"""
        # Round bbox to reduce cache fragmentation for similar views
        rounded_bbox = (
            round(bbox[0], 4),  # min_lon
            round(bbox[1], 4),  # min_lat
            round(bbox[2], 4),  # max_lon
            round(bbox[3], 4)  # max_lat
        )
        cache_data = f"{rounded_bbox}_{zoom_level}_{user_id}"
        return f"geojson_bbox_{hashlib.md5(cache_data.encode()).hexdigest()}"

    def _get_cache_size_mb(self) -> float:
        """Get current cache size in MB"""
        try:
            info = self.redis_client.info('memory')
            return info.get('used_memory', 0) / (1024 * 1024)
        except:
            return 0

    def _cleanup_cache_if_needed(self):
        """Clean up cache if it exceeds size limit"""
        current_size = self._get_cache_size_mb()
        if current_size > self.max_cache_size_mb:
            # Get all cache keys and their TTL
            keys = self.redis_client.keys("geojson_bbox_*")
            if keys:
                # Sort by TTL (oldest first) and remove oldest 20%
                key_ttls = []
                for key in keys:
                    ttl = self.redis_client.ttl(key)
                    if ttl > 0:  # Only consider keys with TTL
                        key_ttls.append((key, ttl))

                key_ttls.sort(key=lambda x: x[1])  # Sort by TTL
                keys_to_delete = key_ttls[:len(key_ttls) // 5]  # Remove oldest 20%

                for key, _ in keys_to_delete:
                    self.redis_client.delete(key)

    def get(self, bbox: Tuple[float, float, float, float], zoom_level: int, user_id: int) -> Optional[Dict]:
        """Get cached data for a bounding box"""
        cache_key = self._get_cache_key(bbox, zoom_level, user_id)
        try:
            cached_data = self.redis_client.get(cache_key)
            if cached_data:
                return json.loads(cached_data)
        except Exception as e:
            print(f"Cache get error: {e}")
        return None

    def set(self, bbox: Tuple[float, float, float, float], zoom_level: int, user_id: int, data: Dict):
        """Cache data for a bounding box"""
        cache_key = self._get_cache_key(bbox, zoom_level, user_id)
        try:
            # Clean up cache if needed before adding new data
            self._cleanup_cache_if_needed()

            # Set with TTL
            self.redis_client.setex(
                cache_key,
                self.default_ttl,
                json.dumps(data)
            )
        except Exception as e:
            print(f"Cache set error: {e}")


# Global cache instance
geojson_cache = GeoJSONCache()


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
    - use_cache: whether to use cache (default: true)
    """
    # Get query parameters
    bbox_str = request.GET.get('bbox')
    zoom_str = request.GET.get('zoom', '10')
    use_cache = request.GET.get('use_cache', 'true').lower() == 'true'

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

    # Check cache first if enabled
    if use_cache:
        cached_data = geojson_cache.get(bbox, zoom_level, request.user.id)
        if cached_data:
            return JsonResponse({
                'success': True,
                'data': cached_data,
                'cached': True,
                'timestamp': time.time()
            })

    # Fetch data from database
    try:
        features = _get_features_in_bbox(bbox, request.user.id, zoom_level)

        # Create GeoJSON FeatureCollection
        geojson_data = {
            "type": "FeatureCollection",
            "features": features
        }

        # Cache the result if caching is enabled
        if use_cache:
            geojson_cache.set(bbox, zoom_level, request.user.id, geojson_data)

        return JsonResponse({
            'success': True,
            'data': geojson_data,
            'cached': False,
            'feature_count': len(features),
            'timestamp': time.time()
        })

    except Exception as e:
        return JsonResponse({
            'success': False,
            'error': f'Database error: {str(e)}',
            'code': 500
        }, status=500)


@login_required_401
@require_http_methods(["GET"])
def get_cache_stats(request):
    """Get cache statistics for debugging"""
    try:
        cache_size_mb = geojson_cache._get_cache_size_mb()
        cache_keys = len(geojson_cache.redis_client.keys("geojson_bbox_*"))

        return JsonResponse({
            'success': True,
            'cache_size_mb': round(cache_size_mb, 2),
            'cache_keys': cache_keys,
            'max_cache_size_mb': geojson_cache.max_cache_size_mb,
            'default_ttl': geojson_cache.default_ttl
        })
    except Exception as e:
        return JsonResponse({
            'success': False,
            'error': f'Cache stats error: {str(e)}',
            'code': 500
        }, status=500)


@login_required_401
@require_http_methods(["POST"])
def clear_cache(request):
    """Clear the GeoJSON cache"""
    try:
        keys = geojson_cache.redis_client.keys("geojson_bbox_*")
        if keys:
            geojson_cache.redis_client.delete(*keys)

        return JsonResponse({
            'success': True,
            'message': f'Cleared {len(keys)} cache entries'
        })
    except Exception as e:
        return JsonResponse({
            'success': False,
            'error': f'Cache clear error: {str(e)}',
            'code': 500
        }, status=500)
