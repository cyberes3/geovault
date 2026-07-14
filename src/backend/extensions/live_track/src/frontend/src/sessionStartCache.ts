import { normalizeTimestampMs } from './activeButDeadTrack.js';

/**
 * Tracks the most recently seen session-start timestamp (ms) per track id, for tracks using a
 * "session"/"current_session" recent-data-window. Session start can arrive either from a fresh
 * fetch (`track.latestPointParams`) or from live socket updates; this cache lets the socket
 * handler compare an incoming point's session start against the last known one without
 * re-deriving it from the full track list on every point.
 */
export function createSessionStartCache() {
  const startMsByTrackId = new Map();

  function getRecentDataWindow(track) {
    return typeof track?.settings?.recent_data_window === 'string'
      ? track.settings.recent_data_window
      : null;
  }

  function isSessionWindowTrack(track) {
    const windowKey = getRecentDataWindow(track);
    return windowKey === 'session' || windowKey === 'current_session';
  }

  function getStartTimestampMsFromProps(props) {
    if (!props || typeof props !== 'object') return null;
    return normalizeTimestampMs(props.starttimestamp);
  }

  /** Known session-start ms for a track: cached live value first, else derived from its last-known point params. */
  function getKnownStartMs(track) {
    const id = track?.id;
    if (id == null) return null;
    const fromCache = startMsByTrackId.get(String(id));
    if (fromCache != null) return fromCache;
    return getStartTimestampMsFromProps(track?.latestPointParams);
  }

  function setKnownStartMs(trackId, startMs) {
    if (trackId == null) return;
    if (startMs == null) {
      startMsByTrackId.delete(String(trackId));
    } else {
      startMsByTrackId.set(String(trackId), startMs);
    }
  }

  /** Rebuild the whole cache from a freshly-fetched track list (e.g. after `fetchTrackers()`). */
  function refreshFromTrackers(trackList) {
    startMsByTrackId.clear();
    for (const track of trackList || []) {
      if (!isSessionWindowTrack(track)) continue;
      const startMs = getStartTimestampMsFromProps(track.latestPointParams);
      if (startMs != null && track?.id != null) {
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
