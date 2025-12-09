from datetime import datetime
from typing import Optional, Union, List, Dict, Any

from pydantic import BaseModel, field_validator, Field

_VALID_GEOMETRY_TYPES = {
    'Point',
    'LineString',
    'Polygon',
    'MultiPoint',
    'MultiLineString',
    'MultiPolygon',
    'GeometryCollection',
}


class GeometryModel(BaseModel):
    """Pydantic model for GeoJSON geometry validation."""

    type: str
    coordinates: Optional[Union[List, List[List], List[List[List]]]] = None
    geometries: Optional[List[Dict[str, Any]]] = None

    @field_validator('type')
    @classmethod
    def validate_type(cls, v: str) -> str:
        if v not in _VALID_GEOMETRY_TYPES:
            raise ValueError(f'Invalid geometry type: {v}')
        return v


class PropertiesModel(BaseModel):
    """Pydantic model for GeoJSON properties validation."""

    # Core properties
    name: str = ""
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
        # Convert None to empty string, allow empty strings
        if v is None:
            return ""
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
