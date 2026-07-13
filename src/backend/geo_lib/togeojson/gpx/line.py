"""Port of togeojson's `lib/gpx/line.ts`: extracting line style from a
route/track's `<extensions><line>` element."""

from __future__ import annotations

from geo_lib.togeojson.shared import get, num_prop, val_one


def get_line_style(node) -> dict:
    def _callback(line_style, _properties: dict) -> dict:
        def _color_callback(color: str) -> dict:
            return {"stroke": f"#{color}"}

        def _opacity_callback(opacity: float) -> dict:
            return {"stroke-opacity": opacity}

        def _width_callback(width: float) -> dict:
            # GPX width is in mm; convert to px at 96 px per inch.
            return {"stroke-width": (width * 96) / 25.4}

        result: dict = {}
        result.update(val_one(line_style, "color", _color_callback))
        result.update(num_prop(line_style, "opacity", _opacity_callback))
        result.update(num_prop(line_style, "width", _width_callback))
        return result

    return get(node, "line", _callback)
