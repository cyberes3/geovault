"""
GeoJSON validation and normalization with key whitelisting.

This module provides a comprehensive validation function that:
- Uses Pydantic models to whitelist only explicitly allowed keys at all levels
- Automatically removes all non-whitelisted keys via Pydantic validation
- Performs style normalization
- Validates structure using Pydantic
"""

import logging
from typing import Dict, Any, Optional, List, Union
from datetime import datetime

from pydantic import BaseModel, Field, ConfigDict, field_validator

from geo_lib.validation.geometry_validation import GeometryValidationError
from geo_lib.validation.styling_validation import (
    is_valid_hex_color,
    normalize_hex_color,
)

logger = logging.getLogger(__name__)


# Valid geometry types
VALID_GEOMETRY_TYPES = {
    'Point',
    'LineString',
    'Polygon',
    'MultiPoint',
    'MultiLineString',
    'MultiPolygon',
    'GeometryCollection',
}

# Line geometry types (lowercase for comparison with lowercased geom_type)
LINE_GEOMETRY_TYPES = {'linestring', 'multilinestring'}

# Polygon geometry types (lowercase for comparison with lowercased geom_type)
POLYGON_GEOMETRY_TYPES = {'polygon', 'multipolygon'}


class GeometryModel(BaseModel):
    """Pydantic model for GeoJSON geometry validation."""

    type: str
    coordinates: Optional[Union[List, List[List], List[List[List]]]] = None
    geometries: Optional[List[Dict[str, Any]]] = None
    
    @field_validator('type')
    @classmethod
    def validate_type(cls, v: str) -> str:
        if v not in VALID_GEOMETRY_TYPES:
            raise ValueError(f'Invalid geometry type: {v}')
        return v


class PropertiesModel(BaseModel):
    """Pydantic model for GeoJSON properties validation."""

    # Core properties
    name: str = "Unnamed Feature"
    id: Optional[str] = None
    description: Optional[str] = None
    created: Optional[Union[str, datetime]] = None
    tags: Optional[List[str]] = Field(default_factory=list)
    
    # Time property (for GPX routes and other features with time metadata)
    time: Optional[str] = None
    
    # Coordinate properties (for tracks with timestamps/elevation)
    coordinateProperties: Optional[Dict[str, Any]] = None
    
    # System-generated tags (added during processing)
    system_tags: Optional[List[str]] = Field(default_factory=list)
    
    # Point styling
    icon: Optional[str] = None
    icon_href: Optional[str] = Field(default=None, alias='icon-href')
    iconUrl: Optional[str] = None
    icon_url: Optional[str] = None
    marker_icon: Optional[str] = Field(default=None, alias='marker-icon')
    marker_symbol: Optional[str] = Field(default=None, alias='marker-symbol')
    symbol: Optional[str] = None
    marker_color: Optional[str] = Field(default=None, alias='marker-color')
    
    # Line/Polygon styling
    stroke: Optional[str] = None
    stroke_width: Optional[Union[int, float]] = Field(default=None, alias='stroke-width')
    fill: Optional[str] = None
    fill_opacity: Optional[Union[int, float]] = Field(default=None, alias='fill-opacity')
    
    @field_validator('name', mode='before')
    @classmethod
    def validate_name(cls, v: Any) -> str:
        if v is None or (isinstance(v, str) and v.strip() == ''):
            return "Unnamed Feature"
        return str(v)
    
    @field_validator('description', mode='before')
    @classmethod
    def validate_description(cls, v: Any) -> Optional[str]:
        """
        Parse description field, handling dictionary format from togeojson.
        KML descriptions with HTML content come through as {'@type': 'html', 'value': '...'}
        """
        if v is None:
            return None
        
        # Handle dictionary format from togeojson (KML HTML descriptions)
        if isinstance(v, dict):
            if '@type' in v and v['@type'] == 'html' and 'value' in v:
                return v['value']
            else:
                # If it's a dict but not the expected format, convert to string
                return str(v)
        
        # Ensure we have a string
        if not isinstance(v, str):
            return str(v)
        
        return v
    
    @field_validator('tags', mode='before')
    @classmethod
    def validate_tags(cls, v: Any) -> List[str]:
        if v is None:
            return []
        if not isinstance(v, list):
            return []
        return [str(tag) for tag in v if isinstance(tag, str)]


def _normalize_geometry(geometry: Dict[str, Any]) -> Dict[str, Any]:
    """
    Normalize geometry by whitelisting keys.
    
    Args:
        geometry: Geometry dictionary
        
    Returns:
        Normalized geometry with only whitelisted keys
    """
    geom_type = geometry.get('type', '')
    
    # Allowed keys for geometry
    allowed_keys = {'type', 'coordinates', 'geometries'}
    
    normalized = {k: v for k, v in geometry.items() if k in allowed_keys}
    
    # For GeometryCollection, recursively normalize each geometry
    if geom_type == 'GeometryCollection' and 'geometries' in normalized:
        normalized['geometries'] = [
            _normalize_geometry(geom) if isinstance(geom, dict) else geom
            for geom in normalized['geometries']
        ]
    
    return normalized


def _has_polygon_geometry(geometry: Dict[str, Any]) -> bool:
    """
    Check if a geometry contains any polygon geometries.
    
    For GeometryCollection, recursively checks all geometries.
    For Polygon/MultiPolygon, returns True.
    Otherwise, returns False.
    
    Args:
        geometry: Geometry dictionary
        
    Returns:
        True if geometry contains any polygon geometries, False otherwise
    """
    geom_type_lower = geometry.get('type', '').lower()
    
    # Check if this is a polygon type (case-insensitive)
    if geom_type_lower in {'polygon', 'multipolygon'}:
        return True
    
    # For GeometryCollection, recursively check all geometries
    if geom_type_lower == 'geometrycollection':
        geometries = geometry.get('geometries', [])
        return any(_has_polygon_geometry(g) for g in geometries if isinstance(g, dict))
    
    return False


def _normalize_properties(feature: Dict[str, Any]) -> Dict[str, Any]:
    """
    Normalize properties by validating with Pydantic and applying style normalization.
    
    Pydantic automatically filters out any fields not defined in PropertiesModel.
    
    Args:
        feature: Full GeoJSON Feature dictionary (used to access both properties and geometry)
        
    Returns:
        Normalized properties with only whitelisted keys and normalized styles
    """
    properties = feature.get('properties', {})
    geometry = feature.get('geometry', {})
    
    # Validate with Pydantic - this automatically filters out extra fields
    validated_properties = PropertiesModel(**properties)
    # Use mode='json' to ensure datetime objects are serialized to ISO strings
    normalized = validated_properties.model_dump(mode='json', exclude_none=True, by_alias=True)
    
    # Validate and normalize colors - set invalid colors to default red (#ff0000)
    DEFAULT_COLOR = '#ff0000'
    
    # Validate marker-color (for points)
    if 'marker-color' in normalized:
        color = normalized['marker-color']
        if isinstance(color, str) and is_valid_hex_color(color):
            normalized['marker-color'] = normalize_hex_color(color)
        else:
            normalized['marker-color'] = DEFAULT_COLOR
    
    # Validate stroke (for lines and polygons)
    if 'stroke' in normalized:
        color = normalized['stroke']
        if isinstance(color, str) and is_valid_hex_color(color):
            normalized['stroke'] = normalize_hex_color(color)
        else:
            normalized['stroke'] = DEFAULT_COLOR
    
    # Validate fill (for polygons)
    if 'fill' in normalized:
        color = normalized['fill']
        if isinstance(color, str) and is_valid_hex_color(color):
            normalized['fill'] = normalize_hex_color(color)
        else:
            normalized['fill'] = DEFAULT_COLOR
    
    # Apply style normalization based on geometry type
    geom_type = geometry.get('type', '').lower()
    has_polygon = _has_polygon_geometry(geometry)
    
    if geom_type in LINE_GEOMETRY_TYPES:
        # Lines: normalize stroke-width to 2
        if 'stroke' in normalized:
            normalized['stroke-width'] = 2
        # Remove fill properties for lines
        if 'fill' in normalized:
            del normalized['fill']
        if 'fill-opacity' in normalized:
            del normalized['fill-opacity']
    
    elif geom_type in POLYGON_GEOMETRY_TYPES or has_polygon:
        # Polygons: normalize stroke-width, fill, and fill-opacity
        # Also applies to GeometryCollection that contains polygon geometries
        if 'stroke' in normalized:
            normalized['stroke-width'] = 2
            # Set fill to match stroke color (stroke is already validated above)
            stroke_color = normalized.get('stroke', DEFAULT_COLOR)
            normalized['fill'] = stroke_color
            normalized['fill-opacity'] = 0.1
        elif 'fill' in normalized:
            # If no stroke but has fill, still normalize fill-opacity
            normalized['fill-opacity'] = 0.1
    
    # Points: no style normalization needed (only icon/marker-color)
    
    return normalized


def validate_and_normalize_geojson_feature(
    feature: Dict[str, Any],
    preserve_system_tags: Optional[List[str]] = None,
    preserve_feature_hash: Optional[bool] = False
) -> Dict[str, Any]:
    """
    Validate and normalize a GeoJSON Feature by whitelisting keys and normalizing styles.
    
    This function:
    - Uses Pydantic models to whitelist only explicitly allowed keys at all levels
    - Automatically removes all non-whitelisted keys via Pydantic validation
    - Performs style normalization (stroke-width, fill, fill-opacity)
    - Validates structure using Pydantic
    
    Note: `system_tags` and `feature_hash` are preserved if present in the original properties.
    Use preserve_system_tags to explicitly set system_tags after normalization.
    
    Args:
        feature: GeoJSON Feature dictionary
        preserve_system_tags: Optional list of system_tags to preserve (will be added back after normalization)
        preserve_feature_hash: If True, preserve the 'feature_hash' property even if not in whitelist
        
    Returns:
        Validated and normalized GeoJSON Feature dictionary
        
    Raises:
        GeometryValidationError: If feature structure is invalid
    """
    if not isinstance(feature, dict):
        raise GeometryValidationError('Feature must be a dictionary object')
    
    # Extract and preserve system_tags and feature_hash before normalization
    original_system_tags = feature.get('properties', {}).get('system_tags')
    original_feature_hash = feature.get('properties', {}).get('feature_hash')
    
    # First, whitelist top-level keys
    allowed_top_level = {'type', 'geometry', 'properties'}
    filtered_feature = {k: v for k, v in feature.items() if k in allowed_top_level}
    
    if filtered_feature.get('type') != 'Feature':
        raise GeometryValidationError('Feature type must be "Feature"')
    
    # Whitelist and normalize geometry
    if 'geometry' not in filtered_feature:
        raise GeometryValidationError('Feature must have a geometry object')
    
    normalized_geometry = _normalize_geometry(filtered_feature['geometry'])
    
    # Validate geometry structure using Pydantic
    try:
        validated_geometry = GeometryModel(**normalized_geometry)
        normalized_geometry = validated_geometry.model_dump(exclude_none=True)
    except Exception as e:
        raise GeometryValidationError(f'Geometry validation failed: {str(e)}')
    
    # Normalize properties (Pydantic validation happens inside _normalize_properties)
    # Pass the full feature object to detect polygon geometries in GeometryCollection
    filtered_feature['geometry'] = normalized_geometry
    normalized_properties = _normalize_properties(filtered_feature)
    
    # Restore preserved values (after validation)
    if preserve_system_tags is not None:
        normalized_properties['system_tags'] = preserve_system_tags
    elif original_system_tags is not None:
        # If not explicitly provided, preserve original if it exists
        normalized_properties['system_tags'] = original_system_tags
    
    if preserve_feature_hash and original_feature_hash is not None:
        normalized_properties['feature_hash'] = original_feature_hash
    
    # Build final normalized feature
    normalized = {
        'type': 'Feature',
        'geometry': normalized_geometry,
        'properties': normalized_properties
    }
    
    return normalized

