"""recent_data_window filtering. Empty string is invalid; use 'all'."""

import time

from geo_lib.track.points import latest_coord_index_by_time, timestamp_to_ms


RECENT_WINDOW_MS = {
    "1min": 60 * 1000,
    "1h": 3600 * 1000,
    "1d": 24 * 3600 * 1000,
    "1w": 7 * 24 * 3600 * 1000,
    "1m": 30 * 24 * 3600 * 1000,
}

WINDOW_ALL = "all"
VALID_WINDOWS = frozenset({WINDOW_ALL, "1min", "1h", "1d", "1w", "1m", "session", "current_session"})


def normalize_recent_data_window(window_key: str | None) -> str:
    if window_key is None or window_key == WINDOW_ALL:
        return WINDOW_ALL
    if window_key == "":
        raise ValueError("recent_data_window empty-string is invalid; use 'all'")
    if window_key not in VALID_WINDOWS:
        return WINDOW_ALL
    return window_key


def filter_coords_by_recent_window(coords, point_params, window_key: str | None):
    """Keep coords (and matching point_params) inside the window. Latest-point fallback if empty."""

    def _with_latest_point_fallback(filtered_coords, filtered_params):
        if coords and not filtered_coords:
            idx = latest_coord_index_by_time(coords)
            if idx is None:
                return filtered_coords, filtered_params
            if len(coords) == len(point_params):
                return [coords[idx]], [point_params[idx]]
            return [coords[idx]], filtered_params
        return filtered_coords, filtered_params

    try:
        key = normalize_recent_data_window(window_key)
    except ValueError:
        key = WINDOW_ALL
    if key == WINDOW_ALL:
        return coords, point_params
    if key == "current_session":
        filtered_coords, filtered_params = _filter_coords_by_latest_session_start(coords, point_params)
        return _with_latest_point_fallback(filtered_coords, filtered_params)
    if key == "session":
        filtered_coords, filtered_params = _filter_coords_by_last_and_current_session(coords, point_params)
        return _with_latest_point_fallback(filtered_coords, filtered_params)
    if key not in RECENT_WINDOW_MS:
        return coords, point_params
    cutoff_ms = int(time.time() * 1000) - RECENT_WINDOW_MS[key]
    n = len(coords)
    if n != len(point_params):
        return coords, point_params
    kept_coords = []
    kept_params = []
    for i, c in enumerate(coords):
        if len(c) >= 3:
            ts_ms = timestamp_to_ms(c[2])
            if ts_ms is not None and ts_ms >= cutoff_ms:
                kept_coords.append(c)
                kept_params.append(point_params[i])
        else:
            kept_coords.append(c)
            kept_params.append(point_params[i])
    return _with_latest_point_fallback(kept_coords, kept_params)


def _filter_coords_by_latest_session_start(coords, point_params):
    if len(coords) != len(point_params):
        return coords, point_params

    latest_start_ms = None
    for params in point_params:
        if not isinstance(params, dict):
            continue
        start_ms = timestamp_to_ms(params.get("starttimestamp"))
        if start_ms is None:
            continue
        if latest_start_ms is None or start_ms > latest_start_ms:
            latest_start_ms = start_ms

    if latest_start_ms is None:
        return coords, point_params

    kept_coords = []
    kept_params = []
    for i, params in enumerate(point_params):
        start_ms = (
            timestamp_to_ms(params.get("starttimestamp"))
            if isinstance(params, dict)
            else None
        )
        if start_ms == latest_start_ms:
            kept_coords.append(coords[i])
            kept_params.append(params)
            continue
        coord = coords[i]
        coord_ts_ms = timestamp_to_ms(coord[2]) if len(coord) >= 3 else None
        if start_ms is None and coord_ts_ms is not None and coord_ts_ms >= latest_start_ms:
            kept_coords.append(coord)
            kept_params.append(params)
    return kept_coords, kept_params


def _filter_coords_by_last_and_current_session(coords, point_params):
    if len(coords) != len(point_params):
        return coords, point_params

    session_starts_ms = []
    for params in point_params:
        if not isinstance(params, dict):
            continue
        start_ms = timestamp_to_ms(params.get("starttimestamp"))
        if start_ms is None:
            continue
        session_starts_ms.append(start_ms)

    unique_starts_desc = sorted(set(session_starts_ms), reverse=True)
    if not unique_starts_desc:
        return coords, point_params

    latest_start_ms = unique_starts_desc[0]
    previous_start_ms = unique_starts_desc[1] if len(unique_starts_desc) > 1 else None

    allowed_starts = {latest_start_ms}
    if previous_start_ms is not None:
        allowed_starts.add(previous_start_ms)

    fallback_cutoff_ms = previous_start_ms if previous_start_ms is not None else latest_start_ms

    kept_coords = []
    kept_params = []
    for i, params in enumerate(point_params):
        start_ms = (
            timestamp_to_ms(params.get("starttimestamp"))
            if isinstance(params, dict)
            else None
        )
        if start_ms in allowed_starts:
            kept_coords.append(coords[i])
            kept_params.append(params)
            continue
        coord = coords[i]
        coord_ts_ms = timestamp_to_ms(coord[2]) if len(coord) >= 3 else None
        if start_ms is None and coord_ts_ms is not None and coord_ts_ms >= fallback_cutoff_ms:
            kept_coords.append(coord)
            kept_params.append(params)
    return kept_coords, kept_params
