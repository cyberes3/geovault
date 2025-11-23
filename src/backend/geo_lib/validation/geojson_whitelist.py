"""
GeoJSON validation and normalization with key whitelisting.

This module provides a comprehensive validation function that:
- Whitelists only explicitly allowed keys at all levels
- Removes all non-whitelisted keys
- Performs style normalization
- Uses Pydantic for validation
"""

import logging
from typing import Dict, Any, Optional, List, Union
from datetime import datetime

from pydantic import BaseModel, Field, ConfigDict, field_validator

from geo_lib.validation.geometry_validation import GeometryValidationError

logger = logging.getLogger(__name__)


# Whitelisted property keys
ALLOWED_PROPERTY_KEYS = {
    # Core properties
    'name',
    'id',
    'description',
    'created',
    'tags',
    # Point styling
    'icon',
    'icon-href',
    'iconUrl',
    'icon_url',
    'marker-icon',
    'marker-symbol',
    'symbol',
    'marker-color',
    # Line/Polygon styling
    'stroke',
    'stroke-width',
    'fill',
    'fill-opacity',
}

# Unused style properties to remove
UNUSED_STYLE_PROPERTIES = {
    'stroke-opacity',
    'opacity',
    'weight',
    'dashArray',
    'dash-array',
    'lineCap',
    'line-cap',
    'lineJoin',
    'line-join',
    'color',
}

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

# Line geometry types
LINE_GEOMETRY_TYPES = {'LineString', 'MultiLineString'}

# Polygon geometry types
POLYGON_GEOMETRY_TYPES = {'Polygon', 'MultiPolygon'}


class GeometryModel(BaseModel):
    """Pydantic model for GeoJSON geometry validation."""
    model_config = ConfigDict(extra='allow')  # Allow extra for now, we'll filter manually
    
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
    model_config = ConfigDict(extra='allow')  # Allow extra for now, we'll filter manually
    
    name: str = "Unnamed Feature"
    id: Optional[str] = None
    description: Optional[str] = None
    created: Optional[Union[str, datetime]] = None
    tags: Optional[List[str]] = Field(default_factory=list)
    
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
    
    @field_validator('tags', mode='before')
    @classmethod
    def validate_tags(cls, v: Any) -> List[str]:
        if v is None:
            return []
        if not isinstance(v, list):
            return []
        return [str(tag) for tag in v if isinstance(tag, str)]


class FeatureModel(BaseModel):
    """Pydantic model for GeoJSON Feature validation."""
    model_config = ConfigDict(extra='allow')  # Allow extra for now, we'll filter manually
    
    type: str = 'Feature'
    geometry: GeometryModel
    properties: PropertiesModel
    
    @field_validator('type')
    @classmethod
    def validate_type(cls, v: str) -> str:
        if v != 'Feature':
            raise ValueError('Feature type must be "Feature"')
        return v


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


def _normalize_properties(properties: Dict[str, Any], geometry_type: str) -> Dict[str, Any]:
    """
    Normalize properties by whitelisting keys and applying style normalization.
    
    Args:
        properties: Properties dictionary
        geometry_type: Geometry type string
        
    Returns:
        Normalized properties with only whitelisted keys and normalized styles
    """
    # Start with whitelisted keys only
    normalized = {k: v for k, v in properties.items() if k in ALLOWED_PROPERTY_KEYS}
    
    # Remove unused style properties
    for prop_name in UNUSED_STYLE_PROPERTIES:
        if prop_name in normalized:
            del normalized[prop_name]
            logger.debug(f"Removed unused style property: {prop_name}")
    
    # Apply style normalization based on geometry type
    geom_type_lower = geometry_type.lower()
    
    if geom_type_lower in LINE_GEOMETRY_TYPES:
        # Lines: normalize stroke-width to 2
        if 'stroke' in normalized:
            normalized['stroke-width'] = 2
        # Remove fill properties for lines
        if 'fill' in normalized:
            del normalized['fill']
        if 'fill-opacity' in normalized:
            del normalized['fill-opacity']
    
    elif geom_type_lower in POLYGON_GEOMETRY_TYPES:
        # Polygons: normalize stroke-width, fill, and fill-opacity
        if 'stroke' in normalized:
            normalized['stroke-width'] = 2
            # Set fill to match stroke color (or default to #ff0000)
            stroke_color = normalized.get('stroke', '#ff0000')
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
    preserve_id: Optional[bool] = False
) -> Dict[str, Any]:
    """
    Validate and normalize a GeoJSON Feature by whitelisting keys and normalizing styles.
    
    This function:
    - Whitelists only explicitly allowed keys at all levels
    - Removes all non-whitelisted keys
    - Performs style normalization (stroke-width, fill, fill-opacity)
    - Validates structure using Pydantic
    
    Note: `system_tags` and `_id` are NOT in the whitelist and must be handled by the caller.
    This function will remove them if present. Use preserve_system_tags to add them back.
    
    Args:
        feature: GeoJSON Feature dictionary
        preserve_system_tags: Optional list of system_tags to preserve (will be added back after normalization)
        preserve_id: If True, preserve the '_id' property even if not in whitelist
        
    Returns:
        Validated and normalized GeoJSON Feature dictionary
        
    Raises:
        GeometryValidationError: If feature structure is invalid
    """
    if not isinstance(feature, dict):
        raise GeometryValidationError('Feature must be a dictionary object')
    
    # Extract and preserve system_tags and _id before normalization
    original_system_tags = feature.get('properties', {}).get('system_tags')
    original_id = feature.get('properties', {}).get('_id')
    
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
        normalized_geometry = validated_geometry.model_dump(exclude_none=False)
    except Exception as e:
        raise GeometryValidationError(f'Geometry validation failed: {str(e)}')
    
    # Get geometry type for style normalization
    geometry_type = normalized_geometry.get('type', '')
    
    # Whitelist and normalize properties
    original_properties = filtered_feature.get('properties', {})
    normalized_properties = _normalize_properties(original_properties, geometry_type)
    
    # Validate properties structure using Pydantic
    try:
        validated_properties = PropertiesModel(**normalized_properties)
        normalized_properties = validated_properties.model_dump(exclude_none=False, by_alias=True)
    except Exception as e:
        raise GeometryValidationError(f'Properties validation failed: {str(e)}')
    
    # Restore preserved values (after validation)
    if preserve_system_tags is not None:
        normalized_properties['system_tags'] = preserve_system_tags
    elif original_system_tags is not None:
        # If not explicitly provided, preserve original if it exists
        normalized_properties['system_tags'] = original_system_tags
    
    if preserve_id and original_id is not None:
        normalized_properties['_id'] = original_id
    
    # Build final normalized feature
    normalized = {
        'type': 'Feature',
        'geometry': normalized_geometry,
        'properties': normalized_properties
    }
    
    return normalized

