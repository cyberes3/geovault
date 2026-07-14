import { normalizeTimestampMs } from './activeButDeadTrack';
import type { LiveTrack } from './types/track';

/**
 * Normalizes a raw tracker payload (from the trackers list, the geometry endpoint, or a socket
 * merge) into the shape LiveTrackView keeps in memory: a `geometry` that's always present,
 * derived `last_position`/`last_timestamp_ms`/`updated_at_ms`, and the latest point's params.
 */
export function normalizeTrackForMemory(track: LiveTrack): LiveTrack {
  const geom = track.geometry ?? { type: 'LineString', coordinates: [] };
  const coords = geom.coordinates;
  const last = coords.length ? coords[coords.length - 1] : undefined;
  // Use last_point from metadata when geometry has no coordinates (e.g. list or failed geometry fetch)
  const lastPoint = last ?? track.last_point;
  const { point_params, last_point, ...rest } = track;
  void last_point;
  const latestPointParams = (point_params?.length)
    ? point_params[point_params.length - 1]
    : {};
  return {
    ...rest,
    geometry: geom,
    last_position: lastPoint && lastPoint.length >= 2 ? { lon: lastPoint[0], lat: lastPoint[1] } : null,
    last_timestamp_ms: (() => {
      if (!lastPoint || lastPoint.length < 3) return null;
      return normalizeTimestampMs(lastPoint[2]);
    })(),
    updated_at_ms: normalizeTimestampMs(track.updated_at),
    latestPointParams
  };
}
