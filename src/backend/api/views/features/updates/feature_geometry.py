"""Shared geometry validation helpers for feature update and replacement."""
import json
import traceback

from django.contrib.gis.geos import GEOSGeometry

from api.models import FeatureStore
from geo_lib.logging.console import get_tagged_logger
from geo_lib.spatial.coordinates import ensure_3d_geometry_coordinates
from geo_lib.types.feature import (
    LineStringFeature,
    MultiLineStringFeature,
    PointFeature,
    PolygonFeature,
)

_logger = get_tagged_logger()


def get_feature_class_for_geometry_type(geom_type: str):
    match geom_type:
        case 'point' | 'multipoint':
            return PointFeature
        case 'linestring':
            return LineStringFeature
        case 'multilinestring':
            return MultiLineStringFeature
        case 'polygon' | 'multipolygon':
            return PolygonFeature
        case _:
            return None


def validate_feature_with_class(feature_data: dict, feature_id: str | None = None) -> tuple[bool, dict | None, str | None]:
    geom_type = feature_data.get('geometry', {}).get('type', '').lower()

    if geom_type == 'geometrycollection':
        geom_data = feature_data.get('geometry', {})
        if not geom_data.get('geometries') or not isinstance(geom_data.get('geometries'), list):
            return False, None, 'GeometryCollection must have a geometries array'
        return True, feature_data, None

    feature_class = get_feature_class_for_geometry_type(geom_type)

    if feature_class is None:
        return False, None, f'Unsupported geometry type: {geom_type}'

    try:
        validated_feature = feature_class(**feature_data)
        feature_data = json.loads(validated_feature.model_dump_json())
        return True, feature_data, None
    except Exception:
        _logger.error(
            "Feature validation error for feature %s:\n%s",
            feature_id or "unknown",
            traceback.format_exc()
        )
        return False, None, 'Feature validation failed'


def update_feature_geometry_field(feature: FeatureStore, feature_data: dict, feature_id: str):
    try:
        geom_data = feature_data.get('geometry', {})
        if geom_data and geom_data.get('type'):
            if geom_data['type'] == 'GeometryCollection':
                pass
            elif geom_data.get('coordinates'):
                geom_data = ensure_3d_geometry_coordinates(geom_data)
                feature.geometry = GEOSGeometry(json.dumps(geom_data))
    except Exception:
        _logger.error("Error updating geometry for feature %s:\n%s", feature_id, traceback.format_exc())
