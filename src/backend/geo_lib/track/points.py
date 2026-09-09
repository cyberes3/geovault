"""Track point identity, timestamps, and latest-by-time."""

from dataclasses import dataclass, field
from typing import Any


COORD_DECIMALS = 5


def timestamp_to_ms(ts: Any) -> int | None:
    if ts is None:
        return None
    try:
        val = int(ts)
    except (TypeError, ValueError):
        return None
    if val < 1e12:
        return val * 1000
    return val


def round_lon_lat(lon: float, lat: float) -> tuple[float, float]:
    return (round(float(lon), COORD_DECIMALS), round(float(lat), COORD_DECIMALS))


def point_identity_key(lon: float, lat: float, timestamp_ms: int) -> tuple[float, float, int]:
    rounded_lon, rounded_lat = round_lon_lat(lon, lat)
    return (rounded_lon, rounded_lat, int(timestamp_ms))


def coord_timestamp_ms(coord: list | tuple | None) -> int | None:
    if not coord or len(coord) < 3:
        return None
    return timestamp_to_ms(coord[2])


def latest_coord_index_by_time(coords: list | None) -> int | None:
    """Index of the freshest coordinate. Missing timestamps lose. Ties use later array order."""
    best_i = None
    best_ts = None
    for i, coord in enumerate(coords or []):
        if not coord or len(coord) < 2:
            continue
        ts = coord_timestamp_ms(coord)
        if best_i is None:
            best_i = i
            best_ts = ts
            continue
        if ts is None:
            if best_ts is None:
                best_i = i
            continue
        if best_ts is None or ts >= best_ts:
            best_i = i
            best_ts = ts
    return best_i


def latest_coord_by_time(coords: list | None):
    index = latest_coord_index_by_time(coords)
    if index is None:
        return None
    return coords[index]


@dataclass(frozen=True)
class TrackPoint:
    lon: float
    lat: float
    timestamp_ms: int
    params: dict[str, Any] = field(default_factory=dict)
    index: int | None = None

    def as_coord(self) -> list:
        return [self.lon, self.lat, self.timestamp_ms]
