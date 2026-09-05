import { normalizeTimestampMs } from './activeButDeadTrack';
import { latestCoordByTime } from './trackLastPoint';
import type { TrackCoordinate } from './types/track';

const ROLLING_WINDOW_MS: Record<string, number> = {
  '1min': 60 * 1000,
  '1h': 60 * 60 * 1000,
  '1d': 24 * 60 * 60 * 1000,
  '1w': 7 * 24 * 60 * 60 * 1000,
  '1m': 30 * 24 * 60 * 60 * 1000,
};

export function normalizeRecentDataWindowKey(value: string | null | undefined): string | null {
  const key = String(value ?? '').trim().toLowerCase();
  return key || null;
}

export function isRollingRecentDataWindow(value: string | null | undefined): boolean {
  return Object.prototype.hasOwnProperty.call(ROLLING_WINDOW_MS, normalizeRecentDataWindowKey(value) ?? '');
}

export function pruneCoordinatesForRecentDataWindow(coordinates: TrackCoordinate[], windowKey: string | null | undefined, nowMs: number = Date.now()): TrackCoordinate[] {
  const normalized = normalizeRecentDataWindowKey(windowKey);
  const windowMs = normalized != null ? ROLLING_WINDOW_MS[normalized] : undefined;
  if (!windowMs || !Array.isArray(coordinates)) return Array.isArray(coordinates) ? coordinates : [];

  const cutoffMs = nowMs - windowMs;
  const pruned = coordinates.filter((coord) => {
    const timestampMs = normalizeTimestampMs(coord[2]);
    return timestampMs == null || timestampMs >= cutoffMs;
  });

  if (pruned.length === 0 && coordinates.length > 0) {
    const latest = latestCoordByTime(coordinates);
    return latest ? [latest] : [coordinates[coordinates.length - 1]];
  }
  return pruned;
}

export function shouldReloadGeometryForSessionTransition(windowKey: string | null | undefined, activeSessionStartMs: number | null | undefined, incomingSessionStartMs: number | null | undefined): boolean {
  const normalized = normalizeRecentDataWindowKey(windowKey);
  return normalized === 'session' &&
    activeSessionStartMs != null &&
    incomingSessionStartMs != null &&
    incomingSessionStartMs > activeSessionStartMs;
}

export function shouldClearGeometryForSessionTransition(windowKey: string | null | undefined, activeSessionStartMs: number | null | undefined, incomingSessionStartMs: number | null | undefined): boolean {
  const normalized = normalizeRecentDataWindowKey(windowKey);
  return normalized === 'current_session' &&
    activeSessionStartMs != null &&
    incomingSessionStartMs != null &&
    incomingSessionStartMs > activeSessionStartMs;
}
