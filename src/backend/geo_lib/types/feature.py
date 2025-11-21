import json
from datetime import datetime
from enum import Enum
from typing import List, Tuple, Optional, Type
from typing import Union

from pydantic import BaseModel, Field, ConfigDict

from geo_lib.feature_id import generate_feature_hash
from geo_lib.processing.logging import ImportLog, DatabaseLogLevel


class GeoFeatureType(str, Enum):
    POINT = 'Point'
    LINESTRING = 'LineString'
    MULTILINESTRING = 'MultiLineString'
    POLYGON = 'Polygon'


class Properties(BaseModel):
    model_config = ConfigDict(extra='allow')  # Allow additional properties from togeojson

    name: str
    id: Optional[str] = None
    description: Optional[str] = None
    created: Optional[datetime] = None
    tags: Optional[List[str]] = Field(default_factory=list)


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


GeoFeatureSupported = Type[PolygonFeature | LineStringFeature | MultiLineStringFeature | PointFeature]


def geojson_to_geofeature(geojson: dict) -> Tuple[List[GeoFeatureSupported], ImportLog]:
    result = []
    import_log = ImportLog()

    for item in geojson['features']:
        match item['geometry']['type'].lower():
            case 'point':
                c = PointFeature
            case 'multipoint':
                c = PointFeature
            case 'linestring':
                c = LineStringFeature
            case 'multilinestring':
                c = MultiLineStringFeature
            case 'polygon':
                c = PolygonFeature
            case 'multipolygon':
                c = PolygonFeature
            case _:
                import_log.add(f'Feature named "{item["properties"].get("name", "unnamed")}" had unsupported type "{item["geometry"]["type"]}".', 'GeoJSON to GeoFeature', DatabaseLogLevel.WARNING)
                continue

        f = c(**item)
        # No need to process rendering since we're not using it anymore

        # Generate hash-based ID for the feature
        feature_dict = f.model_dump()
        feature_hash = generate_feature_hash(feature_dict)
        f.properties.id = feature_hash

        result.append(f)

    return result, import_log


def geofeature_to_geojson(feature: Union[GeoFeatureSupported, list]) -> str:
    if isinstance(feature, list):
        return json.dumps({
            'type': 'FeatureCollection',
            'features': [json.loads(x.model_dump_json(by_alias=True)) for x in feature]
        })
    else:
        return feature.model_dump_json(by_alias=True)
