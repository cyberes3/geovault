"""
Port of togeojson's `lib/kml/ground_overlay.ts`: converting a KML
`GroundOverlay` element into a GeoJSON `Feature` with a `Polygon` geometry.

One deliberate behavioral fix over upstream: `_get_ground_overlay_box()`
checks for a `LatLonQuad` child under both its namespaced (`gx:LatLonQuad`)
and unprefixed (`LatLonQuad`) tag name. Upstream only ever checks the
namespaced form, which is correct for a raw KML DOM, but GeoVault strips all
namespace prefixes from KML before parsing (see `kml_processor.py`), so a
`gx:LatLonQuad`-based ground overlay would silently get a `None` geometry in
production without this fix.
"""

from __future__ import annotations

import math

from geo_lib.togeojson.kml.extract_style import extract_icon_href, extract_style
from geo_lib.togeojson.kml.geometry import coord, fix_ring, get_coordinates
from geo_lib.togeojson.kml.shared import (
    BBox,
    BoxGeometry,
    KmlOptions,
    Schema,
    extract_cascaded_style,
    extract_extended_data,
    extract_time_span,
    extract_time_stamp,
    get_maybe_html_description,
)
from geo_lib.togeojson.kml.style_map import StyleMap
from geo_lib.togeojson.shared import get_one, get_multi, num_one

_DEGREES_TO_RADIANS = math.pi / 180


def _get_ground_overlay_box(node) -> BoxGeometry | None:
    lat_lon_quad = get_one(node, "LatLonQuad") or get_one(node, "gx:LatLonQuad")

    if lat_lon_quad is not None:
        ring = fix_ring(coord(get_coordinates(node)))
        return BoxGeometry(geometry={"type": "Polygon", "coordinates": [ring]})

    return _get_lat_lon_box(node)


def _rotate_box(bbox: BBox, coordinates: list[list[list[float]]], rotation: float) -> list[list[list[float]]]:
    center = [(bbox[0] + bbox[2]) / 2, (bbox[1] + bbox[3]) / 2]

    rotated = []
    for coordinate in coordinates[0]:
        dy = coordinate[1] - center[1]
        dx = coordinate[0] - center[0]
        distance = math.sqrt(dy**2 + dx**2)
        angle = math.atan2(dy, dx) + rotation * _DEGREES_TO_RADIANS
        rotated.append([center[0] + math.cos(angle) * distance, center[1] + math.sin(angle) * distance])

    return [rotated]


def _get_lat_lon_box(node) -> BoxGeometry | None:
    lat_lon_box = get_one(node, "LatLonBox")

    if lat_lon_box is not None:
        north = num_one(lat_lon_box, "north")
        west = num_one(lat_lon_box, "west")
        east = num_one(lat_lon_box, "east")
        south = num_one(lat_lon_box, "south")
        rotation = num_one(lat_lon_box, "rotation")

        if north is not None and south is not None and west is not None and east is not None:
            bbox: BBox = (west, south, east, north)
            coordinates = [
                [
                    [west, north],
                    [east, north],
                    [east, south],
                    [west, south],
                    [west, north],
                ]
            ]
            if rotation is not None:
                coordinates = _rotate_box(bbox, coordinates, rotation)
            return BoxGeometry(geometry={"type": "Polygon", "coordinates": coordinates}, bbox=bbox)

    return None


def get_ground_overlay(node, style_map: StyleMap, schema: Schema, options: KmlOptions) -> dict | None:
    box = _get_ground_overlay_box(node)
    geometry = box.geometry if box else None

    if geometry is None and options.skip_null_geometry:
        return None

    properties: dict = {"@geometry-type": "groundoverlay"}
    properties.update(
        get_multi(node, ["name", "address", "visibility", "open", "phoneNumber", "description"])
    )
    properties.update(get_maybe_html_description(node))
    properties.update(extract_cascaded_style(node, style_map))
    properties.update(extract_style(node))
    properties.update(extract_icon_href(node))
    properties.update(extract_extended_data(node, schema))
    properties.update(extract_time_span(node))
    properties.update(extract_time_stamp(node))

    if "visibility" in properties:
        properties["visibility"] = properties["visibility"] != "0"

    feature: dict = {"type": "Feature", "geometry": geometry, "properties": properties}

    if box and box.bbox:
        feature["bbox"] = list(box.bbox)

    node_id = node.getAttribute("id")
    if node_id:
        feature["id"] = node_id

    return feature
