from typing import Optional, List, Any
from datetime import datetime, timezone
import logging

from pydantic import BaseModel, Field, field_validator, ConfigDict


class GeojsonRawProperty(BaseModel):
    model_config = ConfigDict(extra='allow')  # Allow additional properties from togeojson
    
    # A class to whitelist these properties.
    name: str = "Unnamed Feature"  # Default name for features without explicit names
    description: Optional[str] = None
    created: Optional[datetime] = None
    tags: List[str] = Field(default_factory=list, alias='feature_tags')  # kml2geojson calls this field `feature_tags`
    
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
