import json
from typing import Any

from django.contrib.gis.geos import GEOSGeometry

from geo_lib.spatial.bbox import get_feature_bounding_box_center


def feature_to_geos(feature: dict[str, Any]) -> GEOSGeometry | None:
    geometry = feature.get('geometry')
    if not geometry:
        return None
    geom_data = dict(geometry)
    geom_type = geom_data.get('type')
    coords = geom_data.get('coordinates')
    if not geom_type or coords is None:
        return None
    if geom_type == 'Point':
        if len(coords) == 2:
            geom_data['coordinates'] = [coords[0], coords[1], 0.0]
    elif geom_type == 'LineString':
        geom_data['coordinates'] = [
            [coord[0], coord[1], coord[2] if len(coord) > 2 else 0.0]
            for coord in coords
        ]
    elif geom_type == 'Polygon':
        geom_data['coordinates'] = [
            [
                [coord[0], coord[1], coord[2] if len(coord) > 2 else 0.0]
                for coord in ring
            ]
            for ring in coords
        ]
    try:
        return GEOSGeometry(json.dumps(geom_data))
    except Exception:
        return None


def spatial_sort_tuple(feature: dict[str, Any]) -> tuple[float, float]:
    center = get_feature_bounding_box_center(feature)
    if center is None:
        return (0.0, 0.0)
    lat, lon = center
    return (-lat, lon)
