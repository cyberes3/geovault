"""Port of togeojson's `lib/kml/fixColor.ts`: KML's `aabbggrr` color format
to GeoJSON simplestyle `#rrggbb` + opacity."""

from __future__ import annotations


def fix_color(v: str, prefix: str) -> dict:
    properties: dict = {}
    color_prop = prefix if prefix in ("stroke", "fill") else f"{prefix}-color"

    if v[:1] == "#":
        v = v[1:]

    if len(v) == 6 or len(v) == 3:
        properties[color_prop] = f"#{v}"
    elif len(v) == 8:
        # JS's parseInt("...", 16) is lenient and yields NaN (-> JSON null)
        # rather than raising on malformed hex; mirror that instead of
        # crashing the whole conversion on a bad color string.
        try:
            opacity = int(v[0:2], 16) / 255
        except ValueError:
            opacity = None
        properties[f"{prefix}-opacity"] = opacity
        properties[color_prop] = f"#{v[6:8]}{v[4:6]}{v[2:4]}"

    return properties
