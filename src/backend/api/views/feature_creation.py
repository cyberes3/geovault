"""
API views for feature creation.
"""
import traceback
from typing import Optional

from coordinate_parser import parse_coordinate
from django.contrib.gis.geos import Point
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore
from api.utils.responses import error_response, success_response
from api.validation.feature_updates import validate_payload
from geo_lib.tags.const_strings import filter_protected_tags, prepare_user_tags, CONST_INTERNAL_TAGS
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.geocoding.background_geocoding import geocode_feature_async
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.tagging.generate import generate_auto_tags
from geo_lib.types.feature import PointFeature
from geo_lib.validation.geojson.geojson_whitelist import validate_and_normalize_geojson_feature
from geo_lib.validation.geometry_validation import GeometryValidationError
from geo_lib.website.auth import api_or_login_required_401
from pydantic import BaseModel, Field, field_validator
from website.settings_utils import get_required_setting
import requests

logger = get_tagged_logger('access')


class QuickPointCreatePayload(BaseModel):
    """Payload for quick point creation."""
    latitude: float = Field(..., ge=-90, le=90, description="Latitude (-90 to 90)")
    longitude: float = Field(..., ge=-180, le=180, description="Longitude (-180 to 180)")
    name: str = Field(..., min_length=1, max_length=255, description="Feature name")
    description: Optional[str] = Field(None, max_length=10000, description="Feature description")
    tags: list[str] = Field(default_factory=list, description="User tags")
    marker_color: str = Field(default="#ff0000", description="Marker color (hex)")
    icon: Optional[str] = Field(None, description="Icon URL")

    @field_validator('latitude')
    @classmethod
    def validate_latitude(cls, v):
        """Validate latitude using coordinate-parser library."""
        if v is None:
            raise ValueError('Latitude is required')
        try:
            # Validate using coordinate-parser library
            # parse_coordinate accepts float directly and validates the coordinate is in valid range
            parsed = parse_coordinate(v, coord_type="latitude", validate=True)
            # Return the parsed value (ensures consistency with coordinate-parser's output)
            return float(parsed)
        except ValueError as e:
            raise ValueError(f'Invalid latitude: {str(e)}')
        except Exception as e:
            raise ValueError(f'Latitude validation failed: {str(e)}')

    @field_validator('longitude')
    @classmethod
    def validate_longitude(cls, v):
        """Validate longitude using coordinate-parser library."""
        if v is None:
            raise ValueError('Longitude is required')
        try:
            # Validate using coordinate-parser library
            # parse_coordinate accepts float directly and validates the coordinate is in valid range
            parsed = parse_coordinate(v, coord_type="longitude", validate=True)
            # Return the parsed value (ensures consistency with coordinate-parser's output)
            return float(parsed)
        except ValueError as e:
            raise ValueError(f'Invalid longitude: {str(e)}')
        except Exception as e:
            raise ValueError(f'Longitude validation failed: {str(e)}')

    @field_validator('tags')
    @classmethod
    def validate_tags(cls, v):
        if v is None:
            return []
        if not isinstance(v, list):
            raise ValueError('Tags must be a list')
        if len(v) > 100:
            raise ValueError('Maximum 100 tags allowed')
        for tag in v:
            if not isinstance(tag, str):
                raise ValueError('All tags must be strings')
            if len(tag) > 100:
                raise ValueError('Tag length must not exceed 100 characters')
        return v


def _fetch_elevation_for_point(longitude: float, latitude: float) -> Optional[float]:
    """
    Fetch elevation from external elevation API for a single point.
    
    Args:
        longitude: Longitude coordinate
        latitude: Latitude coordinate
        
    Returns:
        Elevation value in meters, or None if fetch failed
    """
    # Check if elevation API is enabled
    if not get_required_setting('ELEVATION_API_ENABLED'):
        logger.info("Elevation API is disabled")
        return None
    
    api_url = get_required_setting('ELEVATION_API_URL')
    api_timeout = get_required_setting('ELEVATION_API_TIMEOUT')
    
    try:
        # API expects [lat, lon] format
        response = requests.post(
            api_url,
            json=[[latitude, longitude]],
            headers={'Content-Type': 'application/json'},
            timeout=api_timeout
        )
        response.raise_for_status()
        
        elevations = response.json()
        if isinstance(elevations, list) and len(elevations) > 0:
            elevation = elevations[0]
            if isinstance(elevation, (int, float)):
                return float(elevation)
        
        logger.warning(f"Unexpected elevation API response format")
        return None
        
    except requests.Timeout:
        logger.error(f"Elevation API request timed out after {api_timeout}s")
        return None
    except:
        logger.error(f"Error fetching elevation data: {traceback.format_exc()}")
        return None


@api_or_login_required_401()
@require_http_methods(["POST"])
@validate_payload(QuickPointCreatePayload)
def create_quick_point(request, validated_data):
    """
    Create a new point feature with automatic elevation fetching.
    
    POST body:
    - latitude: float (required, -90 to 90)
    - longitude: float (required, -180 to 180)
    - name: string (required)
    - description: string (optional)
    - tags: array of strings (optional)
    - marker_color: string (optional, default: "#ff0000")
    - icon: string (optional)
    
    Returns:
    - feature: Created feature data
    """
    try:
        latitude = validated_data['latitude']
        longitude = validated_data['longitude']
        name = validated_data['name'].strip()
        description = validated_data.get('description', '').strip()
        tags = validated_data.get('tags', [])
        marker_color = validated_data.get('marker_color', '#ff0000')
        icon = validated_data.get('icon')
        
        # Filter out system tags from user input (defensive)
        user_tags = filter_protected_tags(tags, CONST_INTERNAL_TAGS)
        user_tags = prepare_user_tags(user_tags)
        
        # Fetch elevation data
        elevation = _fetch_elevation_for_point(longitude, latitude)
        if elevation is None:
            # Default to 0.0 if elevation fetch failed
            elevation = 0.0
            logger.info(f"Using default elevation 0.0 for point at ({latitude}, {longitude})")
        
        # Create GeoJSON feature
        coordinates = [longitude, latitude, elevation]
        
        properties = {
            'name': name,
            'description': description,  # Always include description (empty string if not provided)
            'marker-color': marker_color,
            'tags': user_tags
        }
        
        if icon:
            properties['icon'] = icon
        
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
        
        # Generate system tags using PointFeature type (skip geocoding for async processing)
        point_feature = PointFeature(**normalized_feature)
        system_tags = generate_auto_tags(point_feature, import_log=None, filename='quick-point', skip_geocoding=True)
        
        # Add 'quick-point' system tag to identify features created via this endpoint
        if 'quick-point' not in system_tags:
            system_tags.append('quick-point')
        
        # Add system tags to properties
        normalized_feature['properties']['system_tags'] = system_tags
        
        # Remove geojson_hash from properties (it's stored separately in FeatureStore)
        del normalized_feature['properties']['geojson_hash']
        
        # Create geometry for spatial queries
        geometry = Point(longitude, latitude, elevation)
        
        # Save to database
        feature_store = FeatureStore.objects.create(
            user=request.user,
            geojson=normalized_feature,
            geometry=geometry,
            geojson_hash=geojson_hash
        )
        
        # Start background geocoding (non-blocking)
        geocode_feature_async(feature_store.id)
        
        # Add database_id to properties for response
        normalized_feature['properties']['database_id'] = feature_store.id
        
        return success_response({
            'feature': normalized_feature
        }, status=201)
        
    except Exception as e:
        logger.error(f"Error creating quick point: {traceback.format_exc()}")
        return error_response(f'Failed to create point: {str(e)}', 500)

