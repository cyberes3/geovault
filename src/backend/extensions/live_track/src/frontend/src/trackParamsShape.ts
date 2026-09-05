/**
 * Build the track shape expected by LatestParamsModal from a track-like object
 * (raw API response or normalized track with geometry.coordinates and point_params).
 * Used by WorldShareView and can be used by LiveTrackView for consistency.
 */
import { normalizeTimestampMs } from './activeButDeadTrack';
import { latestParamsForCoordinate, resolveTrackLastCoordinate } from './trackLastPoint';
import type { LiveTrack, PointParams, TrackPosition } from './types/track';

export interface ParamsModalTrackShape {
  name?: string;
  last_position: TrackPosition | null;
  last_timestamp_ms: number | null;
  latestPointParams: PointParams;
}

export function trackToParamsModalShape(track: LiveTrack | null | undefined): ParamsModalTrackShape | null {
  if (!track) return null;
  const last = resolveTrackLastCoordinate(track);
  return {
    name: track.name,
    last_position: last && last.length >= 2 ? { lon: last[0], lat: last[1] } : null,
    last_timestamp_ms: last && last.length >= 3 ? normalizeTimestampMs(last[2]) : (track.last_timestamp_ms ?? null),
    latestPointParams: latestParamsForCoordinate(track, last)
  };
}
