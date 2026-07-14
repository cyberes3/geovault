import type { LiveTrack } from './types/track';

const RECENT_METADATA_WINDOW_MS = 3 * 60 * 60 * 1000;
const STALE_DATA_THRESHOLD_MS = 10 * 60 * 1000;
/** Row must be this much newer than last point time (same write is within a few seconds; settings-only saves are minutes+). */
const MIN_METADATA_AHEAD_OF_LAST_DATA_MS = 60 * 1000;

/** @returns Epoch milliseconds */
export function normalizeTimestampMs(value: unknown): number | null {
  if (value == null) return null;
  const n = Number(value);
  if (!Number.isFinite(n)) return null;
  const intVal = Math.trunc(n);
  if (intVal > 0 && intVal < 1e12) return intVal * 1000;
  return intVal;
}

/**
 * Row was updated recently and more than a minute after the last point time, but the last
 * point is over 10 minutes old (e.g. settings/visibility change without new GPS). Idle devices
 * where the row and last point aged together are not flagged.
 */
export function isActiveButDeadTrack(track: LiveTrack | null | undefined, nowMs: number = Date.now()): boolean {
  if (!track) return false;
  const lastData = track.last_timestamp_ms;
  if (lastData == null) return false;
  const updated = track.updated_at_ms ?? normalizeTimestampMs(track.updated_at);
  if (updated == null) return false;
  if (nowMs - lastData <= STALE_DATA_THRESHOLD_MS) return false;
  if (nowMs - updated >= RECENT_METADATA_WINDOW_MS) return false;
  if (nowMs < updated) return false;
  if (updated - lastData <= MIN_METADATA_AHEAD_OF_LAST_DATA_MS) return false;
  return true;
}

export {
  RECENT_METADATA_WINDOW_MS,
  STALE_DATA_THRESHOLD_MS,
  MIN_METADATA_AHEAD_OF_LAST_DATA_MS
};
