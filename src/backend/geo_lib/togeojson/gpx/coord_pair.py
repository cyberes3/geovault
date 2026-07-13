"""Port of togeojson's `lib/gpx/coord_pair.ts`: extracting a coordinate,
elevation, time, and extension values from a `trkpt`/`rtept`/`wpt` element."""

from __future__ import annotations

import math
from dataclasses import dataclass

from geo_lib.togeojson.gpx.extensions import ExtendedValues, get_extensions
from geo_lib.togeojson.shared import get_one, js_parse_float, node_val, num_one


@dataclass(frozen=True)
class CoordPair:
    coordinates: list[float]
    time: str | None
    extended_values: ExtendedValues


def coord_pair(node) -> CoordPair | None:
    lon = js_parse_float(node.getAttribute("lon"))
    lat = js_parse_float(node.getAttribute("lat"))

    if math.isnan(lon) or math.isnan(lat):
        return None

    coordinates: list[float] = [lon, lat]

    def _elevation_callback(val: float) -> None:
        coordinates.append(val)

    num_one(node, "ele", _elevation_callback)

    time_node = get_one(node, "time")

    return CoordPair(
        coordinates=coordinates,
        time=node_val(time_node) if time_node is not None else None,
        extended_values=get_extensions(get_one(node, "extensions")),
    )
