import { normalizeTimestampMs } from './activeButDeadTrack';
import { latestParamsForCoordinate, resolveTrackLastCoordinate } from './trackLastPoint';
import type { LiveTrack } from './types/track';

/**
 * One in-memory track shape for LiveTrackView and WorldShare: geometry always present,
 * last_position / last_timestamp_ms from resolveTrackLastCoordinate, point_params kept.
 */
export function normalizeTrackForMemory(track: LiveTrack): LiveTrack {
  const geom = track.geometry ?? { type: 'LineString', coordinates: [] };
  const withGeom: LiveTrack = { ...track, geometry: geom };
  const last = resolveTrackLastCoordinate(withGeom);
  const point_params = Array.isArray(track.point_params) ? track.point_params : [];
  return {
    ...track,
    geometry: geom,
    point_params,
    last_position: last && last.length >= 2 ? { lon: last[0], lat: last[1] } : null,
    last_timestamp_ms: last && last.length >= 3 ? normalizeTimestampMs(last[2]) : null,
    updated_at_ms: normalizeTimestampMs(track.updated_at),
    latestPointParams: latestParamsForCoordinate({ ...withGeom, point_params }, last)
  };
}
