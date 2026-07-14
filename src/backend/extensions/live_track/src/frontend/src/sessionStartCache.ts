import { normalizeTimestampMs } from './activeButDeadTrack';
import type { LiveTrack, PointParams } from './types/track';

/**
 * Tracks the most recently seen session-start timestamp (ms) per track id, for tracks using a
 * "session"/"current_session" recent-data-window. Session start can arrive either from a fresh
 * fetch (`track.latestPointParams`) or from live socket updates; this cache lets the socket
 * handler compare an incoming point's session start against the last known one without
 * re-deriving it from the full track list on every point.
 */
export function createSessionStartCache() {
  const startMsByTrackId = new Map<string, number>();

  function getRecentDataWindow(track: LiveTrack | null | undefined): string | null {
    return typeof track?.settings?.recent_data_window === 'string'
      ? track.settings.recent_data_window
      : null;
  }

  function isSessionWindowTrack(track: LiveTrack | null | undefined): boolean {
    const windowKey = getRecentDataWindow(track);
    return windowKey === 'session' || windowKey === 'current_session';
  }

  function getStartTimestampMsFromProps(props: PointParams | null | undefined): number | null {
    if (!props || typeof props !== 'object') return null;
    return normalizeTimestampMs(props.starttimestamp);
  }

  /** Known session-start ms for a track: cached live value first, else derived from its last-known point params. */
  function getKnownStartMs(track: LiveTrack | null | undefined): number | null {
    const id = track?.id;
    if (id == null) return null;
    const fromCache = startMsByTrackId.get(String(id));
    if (fromCache != null) return fromCache;
    return getStartTimestampMsFromProps(track?.latestPointParams);
  }

  function setKnownStartMs(trackId: string | number | null | undefined, startMs: number | null | undefined): void {
    if (trackId == null) return;
    if (startMs == null) {
      startMsByTrackId.delete(String(trackId));
    } else {
      startMsByTrackId.set(String(trackId), startMs);
    }
  }

  /** Rebuild the whole cache from a freshly-fetched track list (e.g. after `fetchTrackers()`). */
  function refreshFromTrackers(trackList: LiveTrack[] | null | undefined): void {
    startMsByTrackId.clear();
    for (const track of trackList ?? []) {
      if (!isSessionWindowTrack(track)) continue;
      const startMs = getStartTimestampMsFromProps(track.latestPointParams);
      if (startMs != null) {
        startMsByTrackId.set(String(track.id), startMs);
      }
    }
  }

  return {
    getRecentDataWindow,
    isSessionWindowTrack,
    getStartTimestampMsFromProps,
    getKnownStartMs,
    setKnownStartMs,
    refreshFromTrackers
  };
}

export type SessionStartCache = ReturnType<typeof createSessionStartCache>;
