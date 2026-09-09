"""Aligned coordinate / point_params store. One latest(): max timestamp_ms, tie → last insert."""

import time
from dataclasses import dataclass
from typing import Any

from geo_lib.track.points import (
    TrackPoint,
    coord_timestamp_ms,
    latest_coord_index_by_time,
    point_identity_key,
    timestamp_to_ms,
)
from geo_lib.track.window import (
    RECENT_WINDOW_MS,
    WINDOW_ALL,
    filter_coords_by_recent_window,
    normalize_recent_data_window,
)


def _align_pairs(coordinates: list, params: list) -> tuple[list, list]:
    coords = [list(c) for c in (coordinates or [])]
    raw_params = list(params or [])
    if len(raw_params) != len(coords):
        return coords, [{} for _ in coords]
    aligned = []
    for item in raw_params:
        aligned.append(dict(item) if isinstance(item, dict) else {})
    return coords, aligned


def _coord_timestamp_ms(coord) -> int:
    if not coord or len(coord) < 3:
        return 0
    try:
        return int(coord[2])
    except (TypeError, ValueError):
        return 0


@dataclass
class InsertedPoint:
    index: int
    point: list
    props: dict[str, Any]


class PointStore:
    def __init__(self, coordinates: list | None = None, params: list | None = None):
        self._coords, self._params = _align_pairs(coordinates or [], params or [])

    @classmethod
    def from_track(cls, track) -> "PointStore":
        manager = getattr(track, "point_rows", None)
        if manager is None:
            return cls([], [])
        cache = getattr(track, "_prefetched_objects_cache", None) or {}
        if "point_rows" in cache:
            raw_rows = sorted(cache["point_rows"], key=lambda row: (row.timestamp_ms, row.seq))
            rows = [(row.lon, row.lat, row.timestamp_ms, row.params) for row in raw_rows]
        else:
            rows = manager.order_by("timestamp_ms", "seq").values_list(
                "lon", "lat", "timestamp_ms", "params"
            )
        coords = []
        params = []
        for lon, lat, timestamp_ms, raw_params in rows:
            coords.append([float(lon), float(lat), int(timestamp_ms)])
            params.append(dict(raw_params) if isinstance(raw_params, dict) else {})
        return cls(coords, params)

    @staticmethod
    def _point_from_row(row) -> TrackPoint:
        return TrackPoint(
            lon=float(row.lon),
            lat=float(row.lat),
            timestamp_ms=int(row.timestamp_ms),
            params=dict(row.params) if isinstance(row.params, dict) else {},
        )

    @classmethod
    def latest_from_track(cls, track, window_key: str | None = None) -> TrackPoint | None:
        """SQL latest: max timestamp_ms, tie → last insert (seq DESC). Session windows load history."""
        manager = getattr(track, "point_rows", None)
        if manager is None:
            return None
        try:
            key = normalize_recent_data_window(window_key)
        except ValueError:
            key = WINDOW_ALL
        if key in ("session", "current_session"):
            return cls.from_track(track).latest()
        cache = getattr(track, "_prefetched_objects_cache", None) or {}
        rows = list(cache["point_rows"]) if "point_rows" in cache else None
        if key in RECENT_WINDOW_MS:
            cutoff_ms = int(time.time() * 1000) - RECENT_WINDOW_MS[key]
            if rows is not None:
                windowed = [row for row in rows if int(row.timestamp_ms) >= cutoff_ms]
                chosen = windowed or rows
                if not chosen:
                    return None
                return cls._point_from_row(max(chosen, key=lambda row: (row.timestamp_ms, row.seq)))
            row = manager.filter(timestamp_ms__gte=cutoff_ms).order_by("-timestamp_ms", "-seq").first()
            if row is None:
                row = manager.order_by("-timestamp_ms", "-seq").first()
            return cls._point_from_row(row) if row is not None else None
        if rows is not None:
            if not rows:
                return None
            return cls._point_from_row(max(rows, key=lambda row: (row.timestamp_ms, row.seq)))
        row = manager.order_by("-timestamp_ms", "-seq").first()
        return cls._point_from_row(row) if row is not None else None

    @property
    def coordinates(self) -> list:
        return self._coords

    @property
    def params(self) -> list:
        return self._params

    @property
    def params_align_with_coords(self) -> bool:
        return len(self._coords) == len(self._params)

    def as_geometry(self) -> dict:
        return {"type": "LineString", "coordinates": [list(c) for c in self._coords]}

    def wire_params(self) -> list:
        if not self.params_align_with_coords:
            return []
        return [dict(p) for p in self._params]

    def _stored_timestamp_ms(self, coord) -> int | None:
        """PointStore timestamps are already unix ms; do not apply seconds heuristics again."""
        if not coord or len(coord) < 3:
            return None
        try:
            return int(coord[2])
        except (TypeError, ValueError):
            return None

    def _existing_keys(self) -> set[tuple[float, float, int]]:
        keys = set()
        for coord in self._coords:
            ts = self._stored_timestamp_ms(coord)
            if ts is None or len(coord) < 2:
                continue
            keys.add(point_identity_key(coord[0], coord[1], ts))
        return keys

    def _insert_index_for_timestamp(self, timestamp_ms: int) -> int:
        """Bisect on present timestamps only; missing timestamps sort as older than dated points."""
        lo = 0
        hi = len(self._coords)
        while lo < hi:
            mid = (lo + hi) // 2
            mid_ts = self._stored_timestamp_ms(self._coords[mid])
            if mid_ts is None or mid_ts <= timestamp_ms:
                lo = mid + 1
            else:
                hi = mid
        return lo

    def append(self, lon: float, lat: float, timestamp_ms: int, extra: dict | None = None) -> InsertedPoint | None:
        extra = dict(extra or {})
        try:
            ts = int(timestamp_ms)
        except (TypeError, ValueError):
            return None
        key = point_identity_key(lon, lat, ts)
        if key in self._existing_keys():
            return None
        new_point = [float(lon), float(lat), ts]
        idx = self._insert_index_for_timestamp(ts)
        self._coords.insert(idx, new_point)
        self._params.insert(idx, extra)
        return InsertedPoint(index=idx, point=list(new_point), props=dict(extra))

    def append_many(self, points: list[dict]) -> list[InsertedPoint]:
        inserted: list[InsertedPoint] = []
        seen = self._existing_keys()
        for point_data in points:
            lon = float(point_data["lon"])
            lat = float(point_data["lat"])
            try:
                ts = int(point_data["timestamp"])
            except (TypeError, ValueError, KeyError):
                continue
            key = point_identity_key(lon, lat, ts)
            if key in seen:
                continue
            extra = {k: v for k, v in point_data.items() if k not in ("lat", "lon", "timestamp")}
            new_point = [lon, lat, ts]
            idx = self._insert_index_for_timestamp(ts)
            self._coords.insert(idx, new_point)
            self._params.insert(idx, extra)
            seen.add(key)
            inserted.append(InsertedPoint(index=idx, point=list(new_point), props=dict(extra)))
        return inserted

    def latest(self) -> TrackPoint | None:
        idx = latest_coord_index_by_time(self._coords)
        if idx is None:
            return None
        coord = self._coords[idx]
        ts = coord_timestamp_ms(coord)
        if ts is None:
            ts = 0
        params = self._params[idx] if idx < len(self._params) else {}
        return TrackPoint(
            lon=float(coord[0]),
            lat=float(coord[1]),
            timestamp_ms=ts,
            params=dict(params) if isinstance(params, dict) else {},
            index=idx,
        )

    def window(self, window_key: str | None) -> tuple[list, list]:
        key = normalize_recent_data_window(window_key)
        if key == WINDOW_ALL:
            return [list(c) for c in self._coords], [dict(p) for p in self._params]
        coords, params = filter_coords_by_recent_window(self._coords, self._params, key)
        return [list(c) for c in coords], [dict(p) if isinstance(p, dict) else {} for p in params]

    def trim_to_latest(self) -> None:
        latest = self.latest()
        if latest is None or latest.index is None:
            self._coords = []
            self._params = []
            return
        idx = latest.index
        self._coords = [list(self._coords[idx])]
        self._params = [dict(self._params[idx]) if idx < len(self._params) else {}]

    def persist_to_track(self, track) -> None:
        manager = getattr(track, "point_rows", None)
        if manager is None:
            raise TypeError("track has no point_rows relation")
        PointRow = manager.model
        manager.all().delete()
        rows = []
        seq = 0
        for coord, extra in zip(self._coords, self._params):
            if not coord or len(coord) < 2:
                continue
            try:
                lon = float(coord[0])
                lat = float(coord[1])
            except (TypeError, ValueError):
                continue
            rows.append(
                PointRow(
                    track=track,
                    seq=seq,
                    timestamp_ms=_coord_timestamp_ms(coord),
                    lon=lon,
                    lat=lat,
                    params=dict(extra) if isinstance(extra, dict) else {},
                )
            )
            seq += 1
        if rows:
            PointRow.objects.bulk_create(rows, batch_size=1000)

    def persist_appended(self, track, inserted: list[InsertedPoint]) -> None:
        if not inserted:
            return
        manager = getattr(track, "point_rows", None)
        if manager is None:
            raise TypeError("track has no point_rows relation")
        PointRow = manager.model
        last = manager.order_by("-seq").values_list("seq", flat=True).first()
        next_seq = 0 if last is None else int(last) + 1
        rows = []
        for offset, item in enumerate(inserted):
            coord = item.point
            rows.append(
                PointRow(
                    track=track,
                    seq=next_seq + offset,
                    timestamp_ms=_coord_timestamp_ms(coord),
                    lon=float(coord[0]),
                    lat=float(coord[1]),
                    params=dict(item.props),
                )
            )
        PointRow.objects.bulk_create(rows, batch_size=1000)


def last_point_from_store(store: PointStore, window_key: str | None = None) -> list | None:
    if window_key:
        coords, _params = store.window(window_key)
        idx = latest_coord_index_by_time(coords)
        if idx is None:
            return None
        lp = coords[idx]
    else:
        latest = store.latest()
        if latest is None:
            return None
        lp = latest.as_coord()
    if not lp or len(lp) < 2:
        return None
    out = [round(float(lp[0]), 5), round(float(lp[1]), 5)]
    ts = timestamp_to_ms(lp[2]) if len(lp) >= 3 else None
    if ts is not None:
        out.append(int(ts))
    elif len(lp) >= 3:
        out.append(lp[2])
    if len(lp) > 3:
        out.extend(lp[3:])
    return out
