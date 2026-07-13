"""Shared base fields for feature metadata payloads."""

from datetime import datetime
from typing import Any, List, Optional, Union

from pydantic import BaseModel, ConfigDict, Field, field_validator

from geo_lib.validation.styling_validation import is_valid_hex_color, is_valid_icon_url


class BaseMetadataFields(BaseModel):
    """Base model with common metadata fields for features."""
    model_config = ConfigDict(extra='forbid')

    name: Optional[str] = Field(default=None, description="Feature name")
    description: Optional[str] = Field(default=None, description="Feature description")
    tags: Optional[List[str]] = Field(default=None, description="Feature tags")
    created: Optional[str] = Field(default=None, description="Feature creation date (ISO format)")

    # Icon and styling fields (for points)
    icon: Optional[str] = Field(default=None, description="Icon URL or empty string to remove icon")
    marker_color: Optional[str] = Field(default=None, alias='marker-color', description="Marker color (hex)")

    # Styling fields (for lines and polygons)
    stroke: Optional[str] = Field(default=None, description="Stroke color (hex)")

    # Geometry update field (coordinates or geometries array)
    coordinates: Optional[Union[List, List[List], List[List[List]]]] = Field(default=None, description="Coordinates array to update geometry (JSON array)")

    @field_validator('created')
    @classmethod
    def validate_created(cls, v: Any) -> Optional[str]:
        """Validate that created field is a valid ISO datetime string."""
        if v is None:
            return None
        try:
            datetime.fromisoformat(v.replace('Z', '+00:00'))
        except (ValueError, AttributeError):
            raise ValueError('created must be a valid ISO datetime string')
        return v

    @field_validator('marker_color', 'stroke')
    @classmethod
    def validate_color(cls, v: Any) -> Optional[str]:
        """Validate that color fields are valid hex colors."""
        if v is None:
            return None
        if not is_valid_hex_color(v):
            raise ValueError('Color must be a valid hex color')
        return v

    @field_validator('icon')
    @classmethod
    def validate_icon(cls, v: Any) -> Optional[str]:
        """Validate that icon field is a valid icon URL or empty string."""
        if v is None or v == '':
            return v  # Allow None or empty string
        if not is_valid_icon_url(v):
            raise ValueError('Icon must be a valid icon URL')
        return v
