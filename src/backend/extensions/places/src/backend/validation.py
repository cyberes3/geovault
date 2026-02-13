"""
Pydantic validation for places extension.
Ensures create/update payloads are GeoJSON Feature with Point geometry only.
"""

from typing import Literal, Tuple, Union

from pydantic import BaseModel, Field, field_validator


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
        lon, lat = float(v[0]), float(v[1])
        if not (-180 <= lon <= 180):
            raise ValueError("longitude must be between -180 and 180")
        if not (-90 <= lat <= 90):
            raise ValueError("latitude must be between -90 and 90")
        if len(v) == 3:
            return (lon, lat, float(v[2]))
        return (lon, lat)


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
