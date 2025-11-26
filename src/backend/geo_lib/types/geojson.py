from typing import Optional, List, Any, Dict, Union
from datetime import datetime, timezone
import logging

from pydantic import BaseModel, Field, field_validator, ConfigDict


class GeojsonRawProperty(BaseModel):
    # A class to whitelist these properties.
    # Core properties
    name: str = "Unnamed Feature"  # Default name for features without explicit names
    id: Optional[str] = None
    description: Optional[str] = None
    created: Optional[datetime] = None
    tags: List[str] = Field(default_factory=list, alias='feature_tags')  # kml2geojson calls this field `feature_tags`
    
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
    def parse_name_field(cls, v):
        # Convert None or empty strings to default name
        if v is None or (isinstance(v, str) and v.strip() == ''):
            return "Unnamed Feature"
        return v
    
    @field_validator('created', mode='before')
    @classmethod
    def parse_created_field(cls, v):
        if v is None:
            return None
        
        if isinstance(v, datetime):
            return v
        
        if isinstance(v, str):
            try:
                # Try parsing ISO format with Z suffix (UTC)
                if v.endswith('Z'):
                    return datetime.fromisoformat(v[:-1] + '+00:00')
                # Try parsing ISO format with timezone
                elif '+' in v or v.endswith('00:00'):
                    return datetime.fromisoformat(v)
                # Try parsing basic ISO format and assume UTC
                else:
                    return datetime.fromisoformat(v).replace(tzinfo=timezone.utc)
            except ValueError as e:
                logging.error(f"Failed to parse created timestamp '{v}': {e}")
                return None
        
        logging.error(f"Invalid created field type: {type(v)}, value: {v}")
        return None
