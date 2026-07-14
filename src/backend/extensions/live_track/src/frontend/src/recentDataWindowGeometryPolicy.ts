import { normalizeTimestampMs } from './activeButDeadTrack.js';

const ROLLING_WINDOW_MS = {
  '1min': 60 * 1000,
  '1h': 60 * 60 * 1000,
  '1d': 24 * 60 * 60 * 1000,
  '1w': 7 * 24 * 60 * 60 * 1000,
  '1m': 30 * 24 * 60 * 60 * 1000,
};

export function normalizeRecentDataWindowKey(value) {
  const key = String(value || '').trim().toLowerCase();
  return key || null;
}

export function isRollingRecentDataWindow(value) {
  return Object.prototype.hasOwnProperty.call(ROLLING_WINDOW_MS, normalizeRecentDataWindowKey(value));
}

export function pruneCoordinatesForRecentDataWindow(coordinates, windowKey, nowMs = Date.now()) {
  const normalized = normalizeRecentDataWindowKey(windowKey);
  const windowMs = ROLLING_WINDOW_MS[normalized];
  if (!windowMs || !Array.isArray(coordinates)) return Array.isArray(coordinates) ? coordinates : [];

  const cutoffMs = nowMs - windowMs;
  const pruned = coordinates.filter((coord) => {
    const timestampMs = normalizeTimestampMs(coord?.[2]);
    return timestampMs == null || timestampMs >= cutoffMs;
  });

  if (pruned.length === 0 && coordinates.length > 0) {
    return [coordinates[coordinates.length - 1]];
  }
  return pruned;
}

export function shouldReloadGeometryForSessionTransition(windowKey, activeSessionStartMs, incomingSessionStartMs) {
  const normalized = normalizeRecentDataWindowKey(windowKey);
  return normalized === 'session' &&
    activeSessionStartMs != null &&
    incomingSessionStartMs != null &&
    incomingSessionStartMs > activeSessionStartMs;
}

export function shouldClearGeometryForSessionTransition(windowKey, activeSessionStartMs, incomingSessionStartMs) {
  const normalized = normalizeRecentDataWindowKey(windowKey);
  return normalized === 'current_session' &&
    activeSessionStartMs != null &&
    incomingSessionStartMs != null &&
    incomingSessionStartMs > activeSessionStartMs;
}
