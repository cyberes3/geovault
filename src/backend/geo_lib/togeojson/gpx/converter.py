"""
Port of togeojson's `lib/gpx.ts`, wrapped in a `GpxConverter` class that owns
the per-document namespace list instead of threading it through closures.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Generator

from geo_lib.togeojson.gpx.coord_pair import coord_pair
from geo_lib.togeojson.gpx.line import get_line_style
from geo_lib.togeojson.gpx.properties import NS, extract_properties
from geo_lib.togeojson.shared import find_all, get_multi, get_one

_GPXX_PREFIX = "gpxx"
_GPXX_URI = "http://www.garmin.com/xmlschemas/GpxExtensions/v3"


@dataclass(frozen=True)
class _PointsResult:
    line: list[list[float]]
    times: list[str]
    extended_values: dict[str, list]


def _get_points(node, point_name: str) -> _PointsResult | None:
    """Port of `getPoints()`: extract a `trkseg`/`rte`'s coordinate line,
    times, and extension values.

    Note the upstream shape this faithfully preserves: `line`/`times` are
    compacted (skipping any point `coordPair()` couldn't parse), but each
    `extended_values` array is always sized to the *total* point count and
    indexed by each point's original position -- so it is not guaranteed to
    be positionally aligned with `line` when some points are invalid.
    """
    pts = find_all(node, point_name)
    line: list[list[float]] = []
    times: list[str] = []
    extended_values: dict[str, list] = {}

    for i, pt in enumerate(pts):
        c = coord_pair(pt)
        if c is None:
            continue
        line.append(c.coordinates)
        if c.time:
            times.append(c.time)
        for name, val in c.extended_values:
            plural = name if name == "heart" else f"{name.replace('gpxtpx:', '', 1)}s"
            if plural not in extended_values:
                extended_values[plural] = [None] * len(pts)
            extended_values[plural][i] = val

    if len(line) < 2:
        return None

    return _PointsResult(line=line, times=times, extended_values=extended_values)


def _get_route(ns: NS, node) -> dict | None:
    line = _get_points(node, "rtept")
    if line is None:
        return None

    properties: dict = {"_gpxType": "rte"}
    properties.update(extract_properties(ns, node))
    properties.update(get_line_style(get_one(node, "extensions")))

    return {
        "type": "Feature",
        "properties": properties,
        "geometry": {"type": "LineString", "coordinates": line.line},
    }


def _get_track(ns: NS, node) -> dict | None:
    segments = find_all(node, "trkseg")
    track: list[list[list[float]]] = []
    times: list[list[str]] = []
    extracted_lines: list[_PointsResult] = []

    for segment in segments:
        line = _get_points(segment, "trkpt")
        if line is not None:
            extracted_lines.append(line)
            if line.times:
                times.append(line.times)

    if len(extracted_lines) == 0:
        return None

    multi = len(extracted_lines) > 1

    properties: dict = {"_gpxType": "trk"}
    properties.update(extract_properties(ns, node))
    properties.update(get_line_style(get_one(node, "extensions")))
    if times:
        properties["coordinateProperties"] = {"times": times if multi else times[0]}

    for i, line in enumerate(extracted_lines):
        track.append(line.line)
        if "coordinateProperties" not in properties:
            properties["coordinateProperties"] = {}
        props = properties["coordinateProperties"]
        for name, val in line.extended_values.items():
            if multi:
                if name not in props:
                    props[name] = [[None] * len(l.line) for l in extracted_lines]
                props[name][i] = val
            else:
                props[name] = val

    geometry = (
        {"type": "MultiLineString", "coordinates": track}
        if multi
        else {"type": "LineString", "coordinates": track[0]}
    )

    return {"type": "Feature", "properties": properties, "geometry": geometry}


def _get_point(ns: NS, node) -> dict | None:
    properties = extract_properties(ns, node)
    properties.update(get_multi(node, ["sym"]))

    pair = coord_pair(node)
    if pair is None:
        return None

    return {
        "type": "Feature",
        "properties": properties,
        "geometry": {"type": "Point", "coordinates": pair.coordinates},
    }


class GpxConverter:
    """Converts a single parsed GPX `Document` into a GeoJSON
    `FeatureCollection`. Builds the document's vendor-extension namespace
    list once at construction time, since it's referenced by every feature
    in the document."""

    def __init__(self, document) -> None:
        self.document = document
        self.ns = self._build_namespaces()

    def _build_namespaces(self) -> NS:
        ns: NS = [(_GPXX_PREFIX, _GPXX_URI)]
        gpx_elements = find_all(self.document, "gpx")
        if not gpx_elements:
            return ns
        attributes = gpx_elements[0].attributes
        if not attributes:
            return ns
        for i in range(attributes.length):
            attr = attributes.item(i)
            if attr.name and attr.name.startswith("xmlns:") and attr.value != _GPXX_URI:
                ns.append((attr.name, attr.value))
        return ns

    def iter_features(self) -> Generator[dict, None, None]:
        for track in find_all(self.document, "trk"):
            feature = _get_track(self.ns, track)
            if feature:
                yield feature

        for route in find_all(self.document, "rte"):
            feature = _get_route(self.ns, route)
            if feature:
                yield feature

        for waypoint in find_all(self.document, "wpt"):
            point = _get_point(self.ns, waypoint)
            if point:
                yield point

    def convert(self) -> dict:
        return {"type": "FeatureCollection", "features": list(self.iter_features())}
