import { normalizeTimestampMs } from './activeButDeadTrack';
import type { LiveTrack, PointParams, TrackCoordinate } from './types/track';

function timestampMsOf(coord: TrackCoordinate | undefined): number | null {
  if (!coord || coord.length < 3) return null;
  return normalizeTimestampMs(coord[2]);
}

/**
 * Freshest last coordinate. Missing timestamps lose to dated ones. Ties use later array order.
 */
export function latestCoordByTime(coordinates: TrackCoordinate[]): TrackCoordinate | null {
  let best: TrackCoordinate | null = null;
  let bestTs: number | null = null;
  for (const coord of coordinates) {
    if (!coord || coord.length < 2) continue;
    const ts = timestampMsOf(coord);
    if (best == null) {
      best = coord;
      bestTs = ts;
      continue;
    }
    if (ts == null) {
      if (bestTs == null) {
        best = coord;
      }
      continue;
    }
    if (bestTs == null || ts >= bestTs) {
      best = coord;
      bestTs = ts;
    }
  }
  return best;
}

export function resolveTrackLastCoordinate(track: LiveTrack | null | undefined): TrackCoordinate | null {
  if (!track) return null;
  const fromGeometry = latestCoordByTime(track.geometry?.coordinates ?? []);
  const lastPoint = track.last_point && track.last_point.length >= 2 ? track.last_point : null;
  if (!fromGeometry) return lastPoint;
  if (!lastPoint) return fromGeometry;
  const geomTs = timestampMsOf(fromGeometry) ?? Number.NEGATIVE_INFINITY;
  const pointTs = timestampMsOf(lastPoint) ?? Number.NEGATIVE_INFINITY;
  return pointTs > geomTs ? lastPoint : fromGeometry;
}

export function latestParamsForCoordinate(
  track: LiveTrack | null | undefined,
  coord: TrackCoordinate | null,
): PointParams {
  if (!track) return {};
  const params = track.point_params ?? [];
  const coords = track.geometry?.coordinates ?? [];
  if (coord && params.length === coords.length && coords.length > 0) {
    const idx = coords.findIndex((c) => c[0] === coord[0] && c[1] === coord[1] && c[2] === coord[2]);
    if (idx >= 0) return params[idx] ?? {};
  }
  if (track.latestPointParams && typeof track.latestPointParams === 'object') {
    return track.latestPointParams;
  }
  return params.length ? params[params.length - 1] : {};
}

export function coordinatesEqual(a: TrackCoordinate | null | undefined, b: TrackCoordinate | null | undefined): boolean {
  if (!a || !b || a.length < 2 || b.length < 2) return false;
  return a[0] === b[0] && a[1] === b[1] && a[2] === b[2];
}
