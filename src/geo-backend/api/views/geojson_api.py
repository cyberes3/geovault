import json
import os
import time
import traceback
from pathlib import Path
from typing import List, Tuple, Dict, NamedTuple

from django import forms
from django.conf import settings
from django.contrib.gis.geos import Polygon, GEOSGeometry
from django.db.models import Q
from django.http import HttpResponse, Http404, JsonResponse
from django.views.decorators.csrf import csrf_protect
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore
from geo_lib.const_strings import CONST_INTERNAL_TAGS, filter_protected_tags, is_protected_tag
from geo_lib.feature_id import generate_feature_hash
from geo_lib.logging.console import get_access_logger
from geo_lib.processing.icon_manager import store_icon
from geo_lib.types.feature import PointFeature, LineStringFeature, MultiLineStringFeature, PolygonFeature
from geo_lib.validation.geometry_validation import (
    normalize_and_validate_feature_update,
    GeometryValidationError
)
from geo_lib.website.auth import login_required_401

logger = get_access_logger()


class BboxQueryResult(NamedTuple):
    """Result of a bounding box query containing features and total count"""
    features: List[Dict]
    total_count: int
    fallback_used: bool = False  # Indicates if fallback mechanism was triggered


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

    # Calculate spans for world-wide detection
    lon_span = max_lon - min_lon if max_lon >= min_lon else (180 - min_lon) + (max_lon + 180)
    lat_span = max_lat - min_lat

    # Check if this is a world-wide bbox that crosses the International Date Line
    # This happens when min_lon > max_lon (e.g., min_lon=134, max_lon=134 means we're crossing 180°/-180°)
    crosses_dateline = min_lon > max_lon

    # Improved world-wide extent detection with more conservative thresholds
    # Lower threshold from 300° to 280° for more conservative detection
    # Also check latitude span (>170° indicates world-wide view)
    world_wide_extent = False
    if crosses_dateline:
        world_wide_extent = True
    else:
        # Check longitude span (more conservative: 280° instead of 300°)
        if lon_span > 280:
            world_wide_extent = True
        # Check latitude span (if lat span > 170°, treat as world-wide)
        elif lat_span > 170:
            world_wide_extent = True
        # Additional check for very large extents (>270° longitude)
        elif lon_span > 270:
            world_wide_extent = True

    # Get the maximum features limit from settings
    max_features = getattr(settings, 'MAX_FEATURES_PER_REQUEST', -1)

    # Base query with explicit NULL geometry exclusion and ordering
    # Exclude features with NULL geometry to ensure consistent query behavior
    # Order by id to ensure consistent results when slicing
    base_query_filter = FeatureStore.objects.filter(user_id=user_id).exclude(geometry__isnull=True).order_by('id')

    if crosses_dateline or world_wide_extent:
        # Handle world-wide bbox that crosses the International Date Line or spans most of the globe
        base_query = base_query_filter
    else:
        # Normal bbox that doesn't cross the International Date Line
        # Use spatial query with error handling
        try:
            bbox_polygon = Polygon.from_bbox(bbox)
            base_query = base_query_filter.filter(geometry__intersects=bbox_polygon)
        except Exception as e:
            logger.warning(f"Error creating bbox polygon or spatial query: {e}. Falling back to world-wide query.")
            # Fallback to world-wide query if spatial query fails
            base_query = base_query_filter

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
            # Include database ID in properties for frontend editing
            properties = geojson_data.get('properties', {}).copy()
            properties['_id'] = feature.id  # Add database ID for editing
            geojson_feature = {
                "type": "Feature",
                "geometry": geojson_data.get('geometry'),
                "properties": properties,
                "geojson_hash": feature.geojson_hash
            }
            geojson_features.append(geojson_feature)

    # Fallback mechanism: if spatial query returned suspiciously few results for a large extent,
    # fall back to world-wide query
    fallback_used = False
    if not (crosses_dateline or world_wide_extent):
        # If we used a spatial query but got very few results for a large extent, something might be wrong
        # Check if the extent is large (>200° longitude or >150° latitude) but we got very few results
        is_large_extent = lon_span > 200 or lat_span > 150
        suspicious_result = is_large_extent and total_count < 10 and total_count > 0

        if suspicious_result:
            logger.warning(
                f"Suspicious result: large extent (lon_span={lon_span:.1f}°, lat_span={lat_span:.1f}°) "
                f"but only {total_count} features found. Falling back to world-wide query."
            )
            fallback_used = True
            # Fall back to world-wide query
            base_query = base_query_filter
            total_count = base_query.count()

            # Re-apply limit if configured
            if max_features > 0:
                features_query = base_query[:max_features]
            else:
                features_query = base_query

            # Re-convert to GeoJSON format
            geojson_features = []
            for feature in features_query:
                geojson_data = feature.geojson
                if geojson_data and 'geometry' in geojson_data:
                    # Include database ID in properties for frontend editing
                    properties = geojson_data.get('properties', {}).copy()
                    properties['_id'] = feature.id  # Add database ID for editing
                    geojson_feature = {
                        "type": "Feature",
                        "geometry": geojson_data.get('geometry'),
                        "properties": properties,
                        "geojson_hash": feature.geojson_hash
                    }
                    geojson_features.append(geojson_feature)

    return BboxQueryResult(features=geojson_features, total_count=total_count, fallback_used=fallback_used)


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
        fallback_used = query_result.fallback_used

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
            'timestamp': time.time(),
            'fallback_used': fallback_used
        }

        # Add warning if features were limited by configuration
        if 0 < max_features < total_features_in_bbox:
            response_data['warning'] = f'Displaying {len(features)} of {total_features_in_bbox} features due to MAX_FEATURES_PER_REQUEST limit ({max_features})'

        return JsonResponse(response_data)

    except Exception:
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

        # Include database ID in properties for frontend editing (same as _get_features_in_bbox)
        geojson_data = feature.geojson.copy()
        if geojson_data and 'properties' in geojson_data:
            geojson_data['properties']['_id'] = feature.id

        # Return the feature data
        return JsonResponse({
            'success': True,
            'feature': {
                'id': feature.id,
                'geojson': geojson_data,
                'geojson_hash': feature.geojson_hash,
                'timestamp': feature.timestamp.isoformat() if feature.timestamp else None
            }
        })

    except FeatureStore.DoesNotExist:
        return JsonResponse({
            'success': False,
            'error': 'Feature not found or access denied',
            'code': 404
        }, status=404)
    except Exception:
        logger.error(f"Error getting feature {feature_id}: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to get feature',
            'code': 500
        }, status=500)


@login_required_401
@require_http_methods(["GET"])
def get_features_by_tag(request):
    """
    API endpoint to get all features grouped by user-generated tags.
    Returns a dictionary where keys are tags and values are lists of features with that tag.
    """
    try:
        # Get all features for the user
        features = FeatureStore.objects.filter(user=request.user)

        # Dictionary to store features by tag
        features_by_tag = {}

        # Iterate through all features
        for feature in features:
            geojson_data = feature.geojson
            if not geojson_data or 'properties' not in geojson_data:
                continue

            properties = geojson_data.get('properties', {})
            tags = properties.get('tags', [])

            if not isinstance(tags, list):
                continue

            # Filter out protected tags to only show user-generated tags
            filtered_tags = filter_protected_tags(tags, CONST_INTERNAL_TAGS)

            # Include database ID in properties for frontend editing
            feature_properties = properties.copy()
            feature_properties['_id'] = feature.id

            # Create GeoJSON feature
            geojson_feature = {
                "type": "Feature",
                "geometry": geojson_data.get('geometry'),
                "properties": feature_properties,
                "geojson_hash": feature.geojson_hash
            }

            # Add feature to each tag's list
            for tag in filtered_tags:
                if isinstance(tag, str) and tag:  # Ensure tag is a non-empty string
                    if tag not in features_by_tag:
                        features_by_tag[tag] = []
                    features_by_tag[tag].append(geojson_feature)

        # Sort tags alphabetically
        sorted_tags = sorted(features_by_tag.keys())

        # Build response with sorted tags
        response_data = {
            'success': True,
            'tags': {}
        }

        for tag in sorted_tags:
            response_data['tags'][tag] = features_by_tag[tag]

        return JsonResponse(response_data)

    except Exception:
        logger.error(f"Error getting features by tag: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to get features by tag',
            'code': 500
        }, status=500)


@login_required_401
@require_http_methods(["GET"])
def search_features(request):
    """
    API endpoint to search features by name, description, or tags.
    Searches across all user's features, not just those in view.
    
    Query parameters:
    - query: search text (required)
    """
    # Get query parameter
    query = request.GET.get('query', '').strip()
    
    if not query:
        return JsonResponse({
            'success': False,
            'error': 'query parameter is required',
            'code': 400
        }, status=400)
    
    try:
        # Base query for user's features
        base_query = FeatureStore.objects.filter(user=request.user).exclude(geometry__isnull=True)
        
        # Build search query using Q objects for OR conditions
        # Search in name, description, and tags fields
        # Use PostgreSQL JSON field lookups with case-insensitive contains
        search_q = (
            Q(geojson__properties__name__icontains=query) |
            Q(geojson__properties__description__icontains=query) |
            Q(geojson__properties__tags__icontains=query)
        )
        
        # Apply search filter
        features_query = base_query.filter(search_q).order_by('id')
        
        # Convert to GeoJSON format
        geojson_features = []
        for feature in features_query:
            geojson_data = feature.geojson
            if geojson_data and 'geometry' in geojson_data:
                # Include database ID in properties for frontend editing
                properties = geojson_data.get('properties', {}).copy()
                properties['_id'] = feature.id
                geojson_feature = {
                    "type": "Feature",
                    "geometry": geojson_data.get('geometry'),
                    "properties": properties,
                    "geojson_hash": feature.geojson_hash
                }
                geojson_features.append(geojson_feature)
        
        # Create GeoJSON FeatureCollection
        geojson_data = {
            "type": "FeatureCollection",
            "features": geojson_features
        }
        
        response_data = {
            'success': True,
            'data': geojson_data,
            'feature_count': len(geojson_features),
            'query': query
        }
        
        return JsonResponse(response_data)
        
    except Exception:
        logger.error(f"Error searching features: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to search features',
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

            # Filter out protected tags from incoming tags
            filtered_tags = filter_protected_tags(metadata['tags'], CONST_INTERNAL_TAGS)

            # Preserve existing protected tags from the original feature
            original_tags = geojson_data.get('properties', {}).get('tags', [])
            if not isinstance(original_tags, list):
                original_tags = []
            protected_tags = [tag for tag in original_tags if is_protected_tag(tag, CONST_INTERNAL_TAGS)]

            # Combine filtered user tags with preserved protected tags
            geojson_data['properties']['tags'] = filtered_tags + protected_tags
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

        # Get original feature data for reference
        original_geojson = feature.geojson
        original_properties = original_geojson.get('properties', {})

        # Normalize and validate Feature or geometry object
        try:
            feature_data = normalize_and_validate_feature_update(feature_data, original_properties)
        except GeometryValidationError as e:
            return JsonResponse({
                'success': False,
                'error': str(e),
                'code': 400
            }, status=400)

        # Get new properties (if geometry-only update, properties were already set to original)
        new_properties = feature_data.get('properties', {})

        # Preserve protected tags from original feature
        original_tags = original_properties.get('tags', [])
        if not isinstance(original_tags, list):
            original_tags = []
        protected_tags = [tag for tag in original_tags if is_protected_tag(tag, CONST_INTERNAL_TAGS)]

        # Filter protected tags from incoming tags
        new_tags = new_properties.get('tags', [])
        if not isinstance(new_tags, list):
            new_tags = []
        filtered_tags = filter_protected_tags(new_tags, CONST_INTERNAL_TAGS)

        # Combine filtered user tags with preserved protected tags
        new_properties['tags'] = filtered_tags + protected_tags

        # Check for custom PNG icon URLs in original feature
        icon_property_names = ['icon', 'icon-href', 'iconUrl', 'icon_url', 'marker-icon', 'marker-symbol', 'symbol']
        original_icon_url = None
        for prop_name in icon_property_names:
            if prop_name in original_properties and original_properties[prop_name]:
                icon_url = original_properties[prop_name]
                if isinstance(icon_url, str):
                    # Check if it's a PNG icon (ends with .png or starts with /api/data/icons/)
                    if icon_url.endswith('.png') or icon_url.startswith('/api/data/icons/'):
                        original_icon_url = icon_url
                        break

        # Handle icon URL changes
        # Allow: removing icons (null/empty), setting new icons from upload endpoint, keeping same icon
        # Prevent: manually changing existing icon URLs to different values
        if original_icon_url:
            # Check if icon is being removed (main 'icon' property is empty)
            if new_properties.get('icon') == '':
                # Icon is being removed - clear all icon properties and ensure marker-color is set
                for prop_name in icon_property_names:
                    new_properties[prop_name] = ''
                if 'marker-color' not in new_properties or not new_properties.get('marker-color'):
                    new_properties['marker-color'] = original_properties.get('marker-color', '#ff0000')
            else:
                # Icon is not being removed - prevent manual URL changes
                for prop_name in icon_property_names:
                    if prop_name in new_properties:
                        new_icon_url = new_properties[prop_name]
                        # Allow keeping the same icon or setting new icon from upload endpoint
                        if (isinstance(new_icon_url, str) and
                                (new_icon_url == original_icon_url or new_icon_url.startswith('/api/data/icons/'))):
                            continue
                        # Prevent manually changing to a different URL (must use upload endpoint)
                        if isinstance(new_icon_url, str) and new_icon_url != original_icon_url:
                            new_properties[prop_name] = original_icon_url
                            logger.warning(f"Attempted to manually change icon URL for feature {feature_id}, restored original")

        # Validate feature structure using the same validation as import conversion
        try:
            geom_type = feature_data.get('geometry', {}).get('type', '').lower()
            feature_class = None

            # GeometryCollection is not supported by the feature classes, but we allow it
            if geom_type == 'geometrycollection':
                # For GeometryCollection, we do basic validation but skip feature class validation
                geom_data = feature_data.get('geometry', {})
                if not geom_data.get('geometries') or not isinstance(geom_data.get('geometries'), list):
                    return JsonResponse({
                        'success': False,
                        'error': 'GeometryCollection must have a geometries array',
                        'code': 400
                    }, status=400)
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
                        return JsonResponse({
                            'success': False,
                            'error': f'Unsupported geometry type: {geom_type}',
                            'code': 400
                        }, status=400)

            # Validate by instantiating the feature class (this will raise ValidationError if invalid)
            # Skip for GeometryCollection as it's not supported by feature classes
            if feature_class is not None:
                validated_feature = feature_class(**feature_data)
                # Convert back to dict for storage (this ensures proper structure)
                feature_data = json.loads(validated_feature.model_dump_json())

        except Exception as e:
            logger.error(f"Feature validation error for feature {feature_id}: {str(e)}")
            return JsonResponse({
                'success': False,
                'error': f'Feature validation failed: {str(e)}',
                'code': 400
            }, status=400)

        # Update the feature data
        feature.geojson = feature_data

        # Regenerate the hash for the updated feature
        feature.geojson_hash = generate_feature_hash(feature_data)

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


@login_required_401
@csrf_protect
@require_http_methods(["DELETE"])
def delete_feature(request, feature_id):
    """
    API endpoint to delete a specific feature.

    URL parameter:
    - feature_id: ID of the feature to delete
    """
    try:
        # Get the feature from database and verify user ownership
        feature = FeatureStore.objects.get(id=feature_id, user=request.user)

        # Delete the feature
        feature.delete()

        return JsonResponse({
            'success': True,
            'message': 'Feature deleted successfully',
            'feature_id': feature_id
        })

    except FeatureStore.DoesNotExist:
        return JsonResponse({
            'success': False,
            'error': 'Feature not found or access denied',
            'code': 404
        }, status=404)
    except Exception as e:
        logger.error(f"Error deleting feature {feature_id}: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to delete feature',
            'code': 500
        }, status=500)


@require_http_methods(["GET"])
def get_config(request):
    """
    API endpoint to get server configuration, including protected tags.
    
    Returns:
        JSON object with protectedTags list
    """
    return JsonResponse({
        'protectedTags': CONST_INTERNAL_TAGS
    })


class IconUploadForm(forms.Form):
    """Form for icon file upload"""
    file = forms.FileField()


@login_required_401
@csrf_protect
@require_http_methods(["POST"])
def upload_icon(request):
    """
    API endpoint to upload a custom icon file.
    
    Request: POST with multipart/form-data containing 'file' field
    Returns: JSON with success status and icon URL path
    """
    try:
        if not request.FILES:
            return JsonResponse({
                'success': False,
                'error': 'No file provided',
                'code': 400
            }, status=400)

        form = IconUploadForm(request.POST, request.FILES)
        if not form.is_valid():
            return JsonResponse({
                'success': False,
                'error': 'Invalid form data',
                'code': 400
            }, status=400)

        uploaded_file = request.FILES['file']
        file_name = uploaded_file.name

        # Validate file extension (only PNG, JPG, ICO allowed for uploads)
        file_ext = os.path.splitext(file_name)[1].lower()
        if file_ext not in settings.ICON_UPLOAD_ALLOWED_EXTENSIONS:
            return JsonResponse({
                'success': False,
                'error': f'Invalid file extension. Allowed extensions: {", ".join(sorted(settings.ICON_UPLOAD_ALLOWED_EXTENSIONS))}',
                'code': 400
            }, status=400)

        # Read file data
        try:
            icon_data = uploaded_file.read()
        except Exception as e:
            logger.error(f"Error reading uploaded icon file: {str(e)}")
            return JsonResponse({
                'success': False,
                'error': 'Failed to read file',
                'code': 500
            }, status=500)

        # Validate file size (500KB limit for uploads)
        if len(icon_data) > settings.ICON_UPLOAD_MAX_SIZE_BYTES:
            max_size_mb = settings.ICON_UPLOAD_MAX_SIZE_BYTES / 1024
            return JsonResponse({
                'success': False,
                'error': f'File size exceeds maximum allowed size of {max_size_mb:.0f}KB',
                'code': 400
            }, status=400)

        # Store icon using existing icon manager
        icon_url = store_icon(icon_data, file_name)

        if not icon_url:
            return JsonResponse({
                'success': False,
                'error': 'Failed to store icon',
                'code': 500
            }, status=500)

        return JsonResponse({
            'success': True,
            'icon_url': icon_url,
            'code': 200
        }, status=200)

    except Exception as e:
        logger.error(f"Error uploading icon: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': f'Internal server error: {str(e)}',
            'code': 500
        }, status=500)


@require_http_methods(["GET"])
def serve_icon(request, icon_hash):
    """
    Serve icon files from storage directory.
    
    URL parameter:
    - icon_hash: Hash of the icon file (with extension, e.g., 'abc123def456.png')
    """
    try:
        # Validate icon_hash format (should be hash + extension)
        if not icon_hash or len(icon_hash) < 5:  # At least hash (64 chars) + extension (e.g., .png)
            raise Http404("Invalid icon hash")

        # Extract hash and extension
        # Icon hash format: {hash}{extension} (e.g., abc123def456.png)
        # Hash is 64 characters (SHA-256), extension starts after that
        # Find the last dot to separate hash from extension
        if '.' not in icon_hash:
            raise Http404("Invalid icon hash format - missing extension")

        # Split on last dot to get hash and extension
        hash_part, extension = icon_hash.rsplit('.', 1)
        extension = '.' + extension  # Add leading dot back

        # Validate hash length (should be 64 characters for SHA-256)
        if len(hash_part) != 64:
            raise Http404("Invalid icon hash format - hash length incorrect")

        # Validate extension
        valid_extensions = {'.png', '.jpg', '.jpeg', '.gif', '.bmp', '.svg', '.webp', '.ico'}
        if extension not in valid_extensions:
            raise Http404("Invalid icon extension")

        # Get storage path
        storage_dir = Path(settings.ICON_STORAGE_DIR)
        icon_path = storage_dir / hash_part[0:2] / hash_part[2:4] / icon_hash

        # Check if file exists
        if not icon_path.exists() or not icon_path.is_file():
            raise Http404("Icon not found")

        # Read icon file
        icon_data = icon_path.read_bytes()

        # Determine content type based on extension
        content_types = {
            '.png': 'image/png',
            '.jpg': 'image/jpeg',
            '.jpeg': 'image/jpeg',
            '.gif': 'image/gif',
            '.bmp': 'image/bmp',
            '.svg': 'image/svg+xml',
            '.webp': 'image/webp',
            '.ico': 'image/x-icon',
        }
        content_type = content_types.get(extension, 'image/png')

        # Create response with appropriate headers
        response = HttpResponse(icon_data, content_type=content_type)
        response['Cache-Control'] = 'public, max-age=31536000'  # Cache for 1 year
        return response

    except Http404:
        raise
    except Exception as e:
        logger.error(f"Error serving icon {icon_hash}: {traceback.format_exc()}")
        raise Http404("Icon not found")
