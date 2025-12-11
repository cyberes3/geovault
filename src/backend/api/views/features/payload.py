from typing import Optional

from coordinate_parser import parse_coordinate
from pydantic import BaseModel, Field, field_validator


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
