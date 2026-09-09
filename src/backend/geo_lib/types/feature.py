from datetime import datetime
from enum import Enum
from typing import List, Tuple, Optional, Any
from typing import Union

from pydantic import BaseModel, Field, ConfigDict, field_validator

from geo_lib.utils.date_parser import parse_date_field


class GeoFeatureType(str, Enum):
    POINT = 'Point'
    LINESTRING = 'LineString'
    MULTILINESTRING = 'MultiLineString'
    POLYGON = 'Polygon'


class Properties(BaseModel):
    model_config = ConfigDict(extra='allow')  # Allow additional properties from togeojson

    name: str = ""
    geojson_hash: str
    description: Optional[str] = None
    created: Optional[datetime] = None
    tags: Optional[List[str]] = Field(default_factory=list)  # User tags only
    system_tags: Optional[List[str]] = Field(default_factory=list)  # System-generated tags (type, import-year, import-month, source-file, reverse geocoding)

    @field_validator('name', mode='before')
    @classmethod
    def validate_name(cls, v: Any) -> str:
        """Convert None to empty string, allow empty strings."""
        if v is None:
            return ""
        return str(v)

    @field_validator('created', mode='before')
    @classmethod
    def parse_created_field(cls, v: Any) -> Optional[datetime]:
        """Parse created field from string or datetime using dateparser for flexible format support."""
        return parse_date_field(v)


class PointFeatureGeometry(BaseModel):
    type: GeoFeatureType = GeoFeatureType.POINT
    coordinates: Union[Tuple[float, float], Tuple[float, float, float]]


class LineStringGeometry(BaseModel):
    type: GeoFeatureType = GeoFeatureType.LINESTRING
    coordinates: List[Union[Tuple[float, float], Tuple[float, float, float], Tuple[float, float, float, int]]]


class MultiLineStringGeometry(BaseModel):
    type: GeoFeatureType = GeoFeatureType.MULTILINESTRING
    coordinates: List[List[Union[Tuple[float, float], Tuple[float, float, float], Tuple[float, float, float, int]]]]


class PolygonGeometry(BaseModel):
    type: GeoFeatureType = GeoFeatureType.POLYGON
    coordinates: List[List[Union[Tuple[float, float], Tuple[float, float, float]]]]


class Feature(BaseModel):
    type: str = 'Feature'
    geometry: Union[PointFeatureGeometry, LineStringGeometry, MultiLineStringGeometry, PolygonGeometry]
    properties: Properties


class PointFeature(Feature):
    geometry: PointFeatureGeometry


class LineStringFeature(Feature):
    geometry: LineStringGeometry


class MultiLineStringFeature(Feature):
    geometry: MultiLineStringGeometry


class PolygonFeature(Feature):
    geometry: PolygonGeometry


GeoFeatureSupported = PointFeature | LineStringFeature | MultiLineStringFeature | PolygonFeature
