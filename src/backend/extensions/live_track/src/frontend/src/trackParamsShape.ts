/**
 * Build the track shape expected by LatestParamsModal from a track-like object
 * (raw API response or normalized track with geometry.coordinates and point_params).
 * Used by WorldShareView and can be used by LiveTrackView for consistency.
 */
import type { LiveTrack, PointParams, TrackPosition } from './types/track';

export interface ParamsModalTrackShape {
  name?: string;
  last_position: TrackPosition | null;
  last_timestamp_ms: number | null;
  latestPointParams: PointParams;
}

export function trackToParamsModalShape(track: LiveTrack | null | undefined): ParamsModalTrackShape | null {
  if (!track) return null;
  const coords = track.geometry?.coordinates ?? [];
  const lastCoord = coords[coords.length - 1] ?? track.last_point;
  const pointParams = track.point_params ?? [];
  const latestParams = track.latestPointParams && typeof track.latestPointParams === 'object'
    ? track.latestPointParams
    : (pointParams.length ? pointParams[pointParams.length - 1] : {});
  return {
    name: track.name,
    last_position: coords.length || track.last_point ? { lon: lastCoord[0], lat: lastCoord[1] } : null,
    last_timestamp_ms: track.last_timestamp_ms ?? lastCoord[2] ?? null,
    latestPointParams: latestParams
  };
}
