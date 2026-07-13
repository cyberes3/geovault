"""Port of togeojson's `lib/kml/extractStyle.ts`: extracting simplestyle
properties from KML `IconStyle`/`LabelStyle`/`LineStyle`/`PolyStyle`."""

from __future__ import annotations

import math

from geo_lib.togeojson.kml.fix_color import fix_color
from geo_lib.togeojson.shared import get, js_parse_float, node_val, num_one, val_one


def _numeric_property(node, source: str, target: str) -> dict:
    properties: dict = {}

    def _callback(val: float) -> None:
        properties[target] = val

    num_one(node, source, _callback)
    return properties


def _get_color(node, output: str) -> dict:
    def _callback(elem, _properties: dict) -> dict:
        return fix_color(node_val(elem), output)

    return get(node, "color", _callback)


def extract_icon_href(node) -> dict:
    def _callback(icon, properties: dict) -> dict:
        def _href_callback(href: str) -> None:
            properties["icon"] = href

        val_one(icon, "href", _href_callback)
        return properties

    return get(node, "Icon", _callback)


def _extract_hotspot(icon_style) -> dict:
    def _callback(hotspot, _properties: dict) -> dict:
        left = js_parse_float(hotspot.getAttribute("x"))
        top = js_parse_float(hotspot.getAttribute("y"))
        xunits = hotspot.getAttribute("xunits")
        yunits = hotspot.getAttribute("yunits")
        if not math.isnan(left) and not math.isnan(top):
            return {
                "icon-offset": [left, top],
                "icon-offset-units": [xunits, yunits],
            }
        return {}

    return get(icon_style, "hotSpot", _callback)


def extract_icon(node) -> dict:
    def _callback(icon_style, _properties: dict) -> dict:
        result: dict = {}
        result.update(_get_color(icon_style, "icon"))
        result.update(_numeric_property(icon_style, "scale", "icon-scale"))
        result.update(_numeric_property(icon_style, "heading", "icon-heading"))
        result.update(_extract_hotspot(icon_style))
        result.update(extract_icon_href(icon_style))
        return result

    return get(node, "IconStyle", _callback)


def extract_label(node) -> dict:
    def _callback(label_style, _properties: dict) -> dict:
        result: dict = {}
        result.update(_get_color(label_style, "label"))
        result.update(_numeric_property(label_style, "scale", "label-scale"))
        return result

    return get(node, "LabelStyle", _callback)


def extract_line(node) -> dict:
    def _callback(line_style, _properties: dict) -> dict:
        result: dict = {}
        result.update(_get_color(line_style, "stroke"))
        result.update(_numeric_property(line_style, "width", "stroke-width"))
        return result

    return get(node, "LineStyle", _callback)


def extract_poly(node) -> dict:
    def _callback(poly_style, properties: dict) -> dict:
        def _color_callback(elem, _p: dict) -> dict:
            return fix_color(node_val(elem), "fill")

        def _fill_callback(fill: str) -> dict | None:
            if fill == "0":
                return {"fill-opacity": 0}
            return None

        def _outline_callback(outline: str) -> dict | None:
            if outline == "0":
                return {"stroke-opacity": 0}
            return None

        result = dict(properties)
        result.update(get(poly_style, "color", _color_callback))
        result.update(val_one(poly_style, "fill", _fill_callback))
        result.update(val_one(poly_style, "outline", _outline_callback))
        return result

    return get(node, "PolyStyle", _callback)


def extract_style(node) -> dict:
    result: dict = {}
    result.update(extract_poly(node))
    result.update(extract_line(node))
    result.update(extract_label(node))
    result.update(extract_icon(node))
    return result
