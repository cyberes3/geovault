"""
Pydantic validation for places extension.
Ensures create/update payloads are GeoJSON Feature with Point geometry only.
"""

from typing import Literal, Tuple, Union

from pydantic import BaseModel, Field, field_validator

from geo_lib.validation.coordinate.coordinate_validation import validate_coordinates_for_geometry_type
from geo_lib.validation.coordinate.helpers import CoordinateValidationError


class PointGeometry(BaseModel):
    """GeoJSON Point geometry: [longitude, latitude] or [longitude, latitude, elevation]."""

    type: Literal["Point"] = "Point"
    coordinates: Union[Tuple[float, float], Tuple[float, float, float]] = Field(
        ...,
        min_length=2,
        max_length=3,
        description="[longitude, latitude] or [longitude, latitude, elevation]",
    )

    @field_validator("coordinates")
    @classmethod
    def validate_coordinates(cls, v) -> Tuple[float, ...]:
        if not isinstance(v, (list, tuple)) or len(v) < 2 or len(v) > 3:
            raise ValueError("coordinates must be [lon, lat] or [lon, lat, z]")
        coords = [float(v[0]), float(v[1])]
        if len(v) == 3:
            coords.append(float(v[2]))
        try:
            validate_coordinates_for_geometry_type(coords[:2], 'Point')
        except CoordinateValidationError as e:
            raise ValueError(str(e)) from e
        if len(v) == 3:
            return (coords[0], coords[1], coords[2])
        return (coords[0], coords[1])


class PlaceFeaturePayload(BaseModel):
    """GeoJSON Feature with Point geometry only. Used for create and update."""

    type: Literal["Feature"] = "Feature"
    geometry: PointGeometry
    properties: dict = Field(default_factory=dict, description="Feature properties (e.g. name, description)")

    @field_validator("properties", mode="before")
    @classmethod
    def ensure_properties_dict(cls, v):
        if v is None:
            return {}
        if not isinstance(v, dict):
            raise ValueError("properties must be an object")
        return v
