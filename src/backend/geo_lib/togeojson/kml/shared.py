"""
Port of togeojson's `lib/kml/shared.ts`: extended data, descriptions,
timestamps, and cascaded styles shared across Placemark/GroundOverlay/
NetworkLink.

Two deliberate behavioral improvements over upstream (see the port's plan
for the full rationale):

* `type_converters`' numeric converters fall back to the original string
  when it fails to parse as a number, instead of silently emitting `None`
  (upstream emits `NaN`, which `JSON.stringify` turns into `null`, silently
  discarding a `SimpleData` value that merely isn't a "clean" number).
* KML's `AltitudeMode` is not parsed at all. Upstream's `processAltitudeMode`
  result is only ever passed to a `console.debug()` call and never reaches
  the output -- it's dead code, so this port drops it entirely.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import TYPE_CHECKING, Callable
from xml.dom import Node

from geo_lib.togeojson.shared import (
    find_all,
    get,
    get_one,
    js_number,
    node_val,
    normalize_id,
    val_one,
)

if TYPE_CHECKING:
    from geo_lib.togeojson.kml.style_map import StyleMap

TypeConverter = Callable[[str], object]

BBox = tuple[float, float, float, float]


@dataclass(frozen=True)
class KmlOptions:
    """
    Options to customize KML output.

    The only option currently is `skip_null_geometry`. Both the KML and
    GeoJSON formats support the idea of features that don't have
    geometries: in KML, this is a Placemark without a Point, etc element,
    and in GeoJSON it's a geometry member with a value of `None`.

    By default, `None` geometries in KML are translated to `None`
    geometries in GeoJSON. For systems that use GeoJSON but don't support
    null geometries, set `skip_null_geometry=True` to omit these features
    entirely and only include features that have a geometry defined.
    """

    skip_null_geometry: bool = False


@dataclass(frozen=True)
class BoxGeometry:
    geometry: dict
    bbox: BBox | None = None


def _to_number_or_string(x: str) -> object:
    n = js_number(x)
    return x if math.isnan(n) else n


type_converters: dict[str, TypeConverter] = {
    "string": lambda x: x,
    "int": _to_number_or_string,
    "uint": _to_number_or_string,
    "short": _to_number_or_string,
    "ushort": _to_number_or_string,
    "float": _to_number_or_string,
    "double": _to_number_or_string,
    "bool": lambda x: bool(x),
}

Schema = dict[str, TypeConverter]


def extract_extended_data(node, schema: Schema) -> dict:
    def _callback(extended_data, properties: dict) -> dict:
        for data in find_all(extended_data, "Data"):
            properties[data.getAttribute("name") or ""] = node_val(get_one(data, "value"))
        for simple_data in find_all(extended_data, "SimpleData"):
            name = simple_data.getAttribute("name") or ""
            type_converter = schema.get(name, type_converters["string"])
            properties[name] = type_converter(node_val(simple_data))
        return properties

    return get(node, "ExtendedData", _callback)


def get_maybe_html_description(node) -> dict:
    description_node = get_one(node, "description")
    child_nodes = description_node.childNodes if description_node is not None else []
    for child in child_nodes:
        if child.nodeType == Node.CDATA_SECTION_NODE:
            return {"description": {"@type": "html", "value": node_val(child)}}
    return {}


def extract_time_span(node) -> dict:
    def _callback(time_span, _properties: dict) -> dict:
        return {
            "timespan": {
                "begin": node_val(get_one(time_span, "begin")),
                "end": node_val(get_one(time_span, "end")),
            }
        }

    return get(node, "TimeSpan", _callback)


def extract_time_stamp(node) -> dict:
    def _callback(time_stamp, _properties: dict) -> dict:
        return {"timestamp": node_val(get_one(time_stamp, "when"))}

    return get(node, "TimeStamp", _callback)


def extract_cascaded_style(node, style_map: "StyleMap") -> dict:
    def _callback(style_url: str) -> dict:
        normalized = normalize_id(style_url)
        if normalized in style_map:
            return {"styleUrl": normalized, **style_map[normalized]}
        return {"styleUrl": normalized}

    return val_one(node, "styleUrl", _callback)
