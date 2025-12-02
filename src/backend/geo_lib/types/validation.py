from geo_lib.types.feature import PointFeature, LineStringFeature, MultiLineStringFeature, PolygonFeature


def match_geometry_class(geometry_type: str) -> type[PointFeature] | type[LineStringFeature] | type[MultiLineStringFeature] | type[PolygonFeature]:
    geometry_type = geometry_type.lower()
    c = None
    match geometry_type:
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
            raise Exception(f"Unknown geometry type: {geometry_type}")
    assert c
    return c
