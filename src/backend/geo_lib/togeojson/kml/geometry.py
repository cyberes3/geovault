"""
Port of togeojson's `lib/kml/geometry.ts`: extracting GeoJSON geometries out
of KML `Point` / `LineString` / `LinearRing` / `Polygon` / `MultiGeometry` /
`Track` / `gx:Track` elements.
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass, field

from geo_lib.togeojson.shared import (
    find_all,
    find_all_ns,
    get_one,
    is_element,
    js_parse_float,
    node_val,
)

_WHITESPACE_RUN_RE = re.compile(r"\s+")


def coord1(value: str) -> list[float]:
    """Port of `coord1()`: parse a single `lon,lat[,alt]` coordinate tuple,
    stripping all whitespace (not just leading/trailing) before splitting."""
    stripped = _WHITESPACE_RUN_RE.sub("", value)
    numbers = [js_parse_float(part) for part in stripped.split(",")]
    numbers = [n for n in numbers if not math.isnan(n)]
    return numbers[:3]


def coord(value: str) -> list[list[float]]:
    """Port of `coord()`: parse a whitespace-separated list of coordinate
    tuples, dropping any tuple with fewer than 2 valid numbers."""
    trimmed = value.strip()
    parts = _WHITESPACE_RUN_RE.split(trimmed)
    coords = [coord1(part) for part in parts]
    return [c for c in coords if len(c) >= 2]


def fix_ring(ring: list[list[float]]) -> list[list[float]]:
    """Port of `fixRing()`: close a ring by duplicating its first point onto
    the end, unless it's already closed."""
    if len(ring) == 0:
        return ring
    first = ring[0]
    last = ring[-1]
    equal = True
    for i in range(max(len(first), len(last))):
        first_val = first[i] if i < len(first) else None
        last_val = last[i] if i < len(last) else None
        if first_val != last_val:
            equal = False
            break
    if not equal:
        return ring + [ring[0]]
    return ring


def get_coordinates(node) -> str:
    """Port of `getCoordinates()`: the text of a node's `coordinates` child."""
    return node_val(get_one(node, "coordinates"))


@dataclass(frozen=True)
class GeometryResult:
    geometries: list[dict] = field(default_factory=list)
    coord_times: list[list[str]] = field(default_factory=list)


def _gx_coords(node) -> dict | None:
    """Port of `gxCoords()`: extract a `gx:Track`'s coordinates and
    timestamps from its `<gx:coord>`/`<coord>` and `<when>` children."""
    elems = find_all(node, "coord")
    if not elems:
        elems = find_all_ns(node, "coord", "*")

    coordinates = [
        [js_parse_float(part) for part in node_val(elem).split(" ")] for elem in elems
    ]

    if not coordinates:
        return None

    if len(coordinates) > 2:
        geometry = {"type": "LineString", "coordinates": coordinates}
    else:
        geometry = {"type": "Point", "coordinates": coordinates[0]}

    times = [node_val(elem) for elem in find_all(node, "when")]
    return {"geometry": geometry, "times": times}


def get_geometry(node) -> GeometryResult:
    """Port of `getGeometry()`: walk a node's children looking for KML
    geometry elements, flattening `MultiGeometry`/`MultiTrack`.

    Implemented iteratively (an explicit stack of sibling iterators, rather
    than recursing into `MultiGeometry`/`MultiTrack` children) so that a
    document with pathologically deep `MultiGeometry` nesting degrades
    gracefully instead of raising `RecursionError` -- confirmed exploitable
    with as few as ~1000 nested levels in a KML file only tens of KB in
    size, well under any existing file-size validation. Upstream's
    recursive JS implementation has the same underlying flaw (it just takes
    ~4x deeper nesting to trip `RangeError: Maximum call stack size
    exceeded`, since V8's default stack is deeper than CPython's); this
    port fixes it outright rather than reproducing the bug at a lower
    threshold. The traversal order (and thus the flattened output order)
    is unchanged from the recursive version.
    """
    geometries: list[dict] = []
    coord_times: list[list[str]] = []

    stack = [iter(node.childNodes)]
    while stack:
        try:
            child = next(stack[-1])
        except StopIteration:
            stack.pop()
            continue

        if not is_element(child):
            continue

        tag = child.tagName

        if tag in ("MultiGeometry", "MultiTrack", "gx:MultiTrack"):
            # Defer to the next loop iteration instead of recursing: push
            # this element's children so they're processed next (mirroring
            # "recurse into it, then continue with the next sibling").
            stack.append(iter(child.childNodes))

        elif tag == "Point":
            coordinates = coord1(get_coordinates(child))
            if len(coordinates) >= 2:
                geometries.append({"type": "Point", "coordinates": coordinates})

        elif tag in ("LinearRing", "LineString"):
            coordinates = coord(get_coordinates(child))
            if len(coordinates) >= 2:
                geometries.append({"type": "LineString", "coordinates": coordinates})

        elif tag == "Polygon":
            coords = []
            for linear_ring in find_all(child, "LinearRing"):
                ring = fix_ring(coord(get_coordinates(linear_ring)))
                if len(ring) >= 4:
                    coords.append(ring)
            if coords:
                geometries.append({"type": "Polygon", "coordinates": coords})

        elif tag in ("Track", "gx:Track"):
            gx = _gx_coords(child)
            if gx is None:
                continue
            geometries.append(gx["geometry"])
            if gx["times"]:
                coord_times.append(gx["times"])

    return GeometryResult(geometries=geometries, coord_times=coord_times)
