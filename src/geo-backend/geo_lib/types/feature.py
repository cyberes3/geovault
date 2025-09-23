import json
from enum import Enum
from typing import List, Tuple, Optional, Type
from typing import Union
from datetime import datetime

from pydantic import BaseModel, Field

from geo_lib.daemon.workers.workers_lib.importer.logging import ImportLog


class GeoFeatureType(str, Enum):
    POINT = 'Point'
    LINESTRING = 'LineString'
    POLYGON = 'Polygon'


class Rendering(BaseModel):
    stroke_width: int = Field(2, alias='strokeWidth')
    stroke_color: Tuple[int, int, int, float] = Field((255, 0, 0, 1.0), alias='strokeColor')
    fill_color: Optional[Tuple[int, int, int, float]] = Field((255, 0, 0, 0.2), alias='fillColor')


class Properties(BaseModel):
    name: str
    id: Optional[int] = -1
    description: Optional[str] = None
    created: Optional[datetime] = None
    tags: Optional[List[str]] = Field(default_factory=list)
    rendering: Optional[Rendering] = Field(default_factory=Rendering)


class PointFeatureGeometry(BaseModel):
    type: GeoFeatureType = GeoFeatureType.POINT
    coordinates: Union[Tuple[float, float], Tuple[float, float, float]]


class LineStringGeometry(BaseModel):
    type: GeoFeatureType = GeoFeatureType.LINESTRING
    coordinates: List[Union[Tuple[float, float], Tuple[float, float, float], Tuple[float, float, float, int]]]


class PolygonGeometry(BaseModel):
    type: GeoFeatureType = GeoFeatureType.POLYGON
    coordinates: List[List[Union[Tuple[float, float], Tuple[float, float, float]]]]


class Feature(BaseModel):
    type: str = 'Feature'
    geometry: Union[PointFeatureGeometry, LineStringGeometry, PolygonGeometry]
    properties: Properties


class PointFeature(Feature):
    geometry: PointFeatureGeometry


class LineStringFeature(Feature):
    geometry: LineStringGeometry


class PolygonFeature(Feature):
    geometry: PolygonGeometry


GeoFeatureSupported = Type[PolygonFeature | LineStringFeature | PointFeature]


def geojson_to_geofeature(geojson: dict) -> Tuple[List[GeoFeatureSupported], ImportLog]:
    result = []
    import_log = ImportLog()
    for item in geojson['features']:
        match item['geometry']['type'].lower():
            case 'point':
                c = PointFeature
            case 'linestring':
                c = LineStringFeature
            case 'polygon':
                c = PolygonFeature
            case _:
                import_log.add(f'Feature named "{item["properties"]["title"]}" had unsupported type "{item["geometry"]["type"]}".')
                continue

        f = c(**item)
        if isinstance(f, (PointFeature, LineStringFeature)):
            del f.properties.rendering.fill_color

        # TODO: do this shit
        f.properties.id = -1  # This will be updated after it's added to the main data store.

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
