"""Port of togeojson's `lib/kml/placemark.ts`: converting a KML `Placemark`
element into a GeoJSON `Feature`."""

from __future__ import annotations

from geo_lib.togeojson.kml.extract_style import extract_style
from geo_lib.togeojson.kml.geometry import get_geometry
from geo_lib.togeojson.kml.shared import (
    KmlOptions,
    Schema,
    extract_cascaded_style,
    extract_extended_data,
    extract_time_span,
    extract_time_stamp,
    get_maybe_html_description,
)
from geo_lib.togeojson.kml.style_map import StyleMap
from geo_lib.togeojson.shared import get_multi


def _geometry_list_to_geometry(geometries: list[dict]) -> dict | None:
    if len(geometries) == 0:
        return None
    if len(geometries) == 1:
        return geometries[0]
    return {"type": "GeometryCollection", "geometries": geometries}


def get_placemark(node, style_map: StyleMap, schema: Schema, options: KmlOptions) -> dict | None:
    geometry_result = get_geometry(node)
    geometry = _geometry_list_to_geometry(geometry_result.geometries)

    if geometry is None and options.skip_null_geometry:
        return None

    properties: dict = {}
    properties.update(
        get_multi(node, ["name", "address", "visibility", "open", "phoneNumber", "description"])
    )
    properties.update(get_maybe_html_description(node))
    properties.update(extract_cascaded_style(node, style_map))
    properties.update(extract_style(node))
    properties.update(extract_extended_data(node, schema))
    properties.update(extract_time_span(node))
    properties.update(extract_time_stamp(node))

    if geometry_result.coord_times:
        times = geometry_result.coord_times
        properties["coordinateProperties"] = {
            "times": times[0] if len(times) == 1 else times
        }

    if "visibility" in properties:
        properties["visibility"] = properties["visibility"] != "0"

    feature: dict = {"type": "Feature", "geometry": geometry, "properties": properties}

    node_id = node.getAttribute("id")
    if node_id:
        feature["id"] = node_id

    return feature
