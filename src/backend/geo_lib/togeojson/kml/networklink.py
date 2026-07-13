"""
Port of togeojson's `lib/kml/networklink.ts`: converting a KML `NetworkLink`
element into a GeoJSON `Feature` with a `Polygon` geometry taken from its
`Region`.

`AltitudeMode` is intentionally not parsed here (see `kml/shared.py`'s module
docstring): upstream's `processAltitudeMode()` call in this file only feeds a
`console.debug()` log and never affects the output, so it's dead code and
this port drops it entirely.
"""

from __future__ import annotations

from dataclasses import dataclass

from geo_lib.togeojson.kml.extract_style import extract_icon_href, extract_style
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


@dataclass(frozen=True)
class _Region:
    coordinate_box: BoxGeometry | None
    lod: list[float | None] | None


def _get_lod(node) -> list[float | None] | None:
    lod = get_one(node, "Lod")

    if lod is not None:
        min_lod_pixels = num_one(lod, "minLodPixels")
        max_lod_pixels = num_one(lod, "maxLodPixels")
        return [
            min_lod_pixels if min_lod_pixels is not None else -1,
            max_lod_pixels if max_lod_pixels is not None else -1,
            num_one(lod, "minFadeExtent"),
            num_one(lod, "maxFadeExtent"),
        ]

    return None


def _get_lat_lon_alt_box(node) -> BoxGeometry | None:
    lat_lon_alt_box = get_one(node, "LatLonAltBox")

    if lat_lon_alt_box is not None:
        north = num_one(lat_lon_alt_box, "north")
        west = num_one(lat_lon_alt_box, "west")
        east = num_one(lat_lon_alt_box, "east")
        south = num_one(lat_lon_alt_box, "south")

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
            return BoxGeometry(geometry={"type": "Polygon", "coordinates": coordinates}, bbox=bbox)

    return None


def _get_network_link_region(node) -> _Region | None:
    region = get_one(node, "Region")

    if region is not None:
        return _Region(coordinate_box=_get_lat_lon_alt_box(region), lod=_get_lod(node))

    return None


def _get_link_object(node) -> dict:
    link_obj = get_one(node, "Link")

    if link_obj is not None:
        return get_multi(
            link_obj,
            [
                "href",
                "refreshMode",
                "refreshInterval",
                "viewRefreshMode",
                "viewRefreshTime",
                "viewBoundScale",
                "viewFormat",
                "httpQuery",
            ],
        )

    return {}


def get_network_link(node, style_map: StyleMap, schema: Schema, options: KmlOptions) -> dict | None:
    box = _get_network_link_region(node)
    geometry = box.coordinate_box.geometry if (box and box.coordinate_box) else None

    if geometry is None and options.skip_null_geometry:
        return None

    properties: dict = {"@geometry-type": "networklink"}
    properties.update(
        get_multi(
            node,
            [
                "name",
                "address",
                "visibility",
                "open",
                "phoneNumber",
                "styleUrl",
                "refreshVisibility",
                "flyToView",
                "description",
            ],
        )
    )
    properties.update(get_maybe_html_description(node))
    properties.update(extract_cascaded_style(node, style_map))
    properties.update(extract_style(node))
    properties.update(extract_icon_href(node))
    properties.update(extract_extended_data(node, schema))
    properties.update(extract_time_span(node))
    properties.update(extract_time_stamp(node))
    properties.update(_get_link_object(node))

    if box and box.lod:
        properties["lod"] = box.lod

    if "visibility" in properties:
        properties["visibility"] = properties["visibility"] != "0"

    feature: dict = {"type": "Feature", "geometry": geometry, "properties": properties}

    if box and box.coordinate_box and box.coordinate_box.bbox:
        feature["bbox"] = list(box.coordinate_box.bbox)

    node_id = node.getAttribute("id")
    if node_id:
        feature["id"] = node_id

    return feature
