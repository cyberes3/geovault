import { normalizeTimestampMs } from './activeButDeadTrack';
import type { LiveTrack, PointParams, TrackCoordinate } from './types/track';

function lastTimeSortedGeometry(track: LiveTrack): TrackCoordinate | undefined {
  const coords = track.geometry?.coordinates ?? [];
  if (coords.length === 0) return undefined;
  if (coords.length === 1) return coords[0];
  const sorted = [...coords].sort((a, b) => {
    const ta = typeof a[2] === 'number' ? a[2] : 0;
    const tb = typeof b[2] === 'number' ? b[2] : 0;
    return ta - tb;
  });
  return sorted[sorted.length - 1];
}

function timestampMsOf(coord: TrackCoordinate | undefined): number | null {
  if (!coord || coord.length < 3) return null;
  return normalizeTimestampMs(coord[2]);
}

/**
 * Freshest last coordinate. Missing timestamps lose to dated ones. Ties and all-missing
 * prefer time-sorted geometry, then last_position, then last_point.
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
  const geomLast = lastTimeSortedGeometry(track);
  const lastPos = track.last_position;
  const lastPosCoord: TrackCoordinate | undefined =
    lastPos != null && Number.isFinite(lastPos.lon) && Number.isFinite(lastPos.lat)
      ? [lastPos.lon, lastPos.lat, track.last_timestamp_ms ?? undefined]
      : undefined;
  const lastPoint = track.last_point;

  const candidates: Array<{ coord: TrackCoordinate; ts: number | null; rank: number }> = [];
  if (geomLast && geomLast.length >= 2) {
    candidates.push({ coord: geomLast, ts: timestampMsOf(geomLast), rank: 0 });
  }
  if (lastPosCoord && lastPosCoord.length >= 2) {
    candidates.push({ coord: lastPosCoord, ts: timestampMsOf(lastPosCoord), rank: 1 });
  }
  if (lastPoint && lastPoint.length >= 2) {
    candidates.push({ coord: lastPoint, ts: timestampMsOf(lastPoint), rank: 2 });
  }
  if (candidates.length === 0) return null;
  return candidates.reduce((best, current) => {
    const bestTs = best.ts ?? Number.NEGATIVE_INFINITY;
    const currentTs = current.ts ?? Number.NEGATIVE_INFINITY;
    if (currentTs > bestTs) return current;
    if (currentTs === bestTs && current.rank < best.rank) return current;
    return best;
  }).coord;
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
