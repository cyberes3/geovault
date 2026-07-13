import { trackersLiveSocket } from './trackersLiveSocket.js';
import { normalizeTrackForMemory } from './trackNormalization.js';
import { createSessionStartCache } from './sessionStartCache.js';
import { normalizeTimestampMs } from './activeButDeadTrack.js';
import {
  isRollingRecentDataWindow,
  pruneCoordinatesForRecentDataWindow,
  shouldClearGeometryForSessionTransition,
  shouldReloadGeometryForSessionTransition
} from './recentDataWindowGeometryPolicy.js';

/**
 * Wires the `trackers-live` websocket (`trackersLiveSocket`) into `trackers.value`, applying
 * incremental `track_updated` events in place instead of a full refetch, and coalescing map
 * redraws via the caller-supplied `updateMapFeatures` (see {@link import('./asyncTaskCoalescer.js').createCoalescedTask}).
 *
 * @param {object} deps
 * @param {import('./extensionApi').ExtensionApi} deps.api
 * @param {import('vue').Ref<Array>} deps.trackers
 * @param {import('vue').Ref} deps.selectedId
 * @param {import('vue').Ref<boolean>} deps.followLocked
 * @param {() => Promise<void>} deps.updateMapFeatures
 * @param {() => void} deps.scheduleCenterOnSelectedTrack
 * @param {(trackId: unknown) => void} deps.fetchAndMergeTracker - full meta+geometry refetch, used when a session transition requires reloading a track's geometry.
 * @param {() => void} deps.onReconnect - called after the socket reconnects (e.g. to refetch trackers/groups).
 */
export function useLiveTrackSocket({
  api,
  trackers,
  selectedId,
  followLocked,
  updateMapFeatures,
  scheduleCenterOnSelectedTrack,
  fetchAndMergeTracker,
  onReconnect
}) {
  const sessionCache = createSessionStartCache();
  let trackUpdatedHandler = null;

  /** Rebuild the session-start cache from a freshly-fetched track list, e.g. after `fetchTrackers()`. */
  function refreshSessionCache(trackList) {
    sessionCache.refreshFromTrackers(trackList);
  }

  /** A point arrived with an index outside the currently-known geometry; re-fetch just the geometry to reconcile. */
  async function reconcileOutOfBoundsPoint(trackId) {
    try {
      const geomRes = await api.get(`/trackers/${trackId}/geometry/`);
      const trackIdx = trackers.value.findIndex((t) => t.id === trackId);
      if (trackIdx < 0) return;
      const existing = trackers.value[trackIdx];
      const normalized = normalizeTrackForMemory({
        ...geomRes.data,
        is_owner: existing.is_owner,
        owner_email: existing.owner_email,
        visibility: existing.visibility
      });
      trackers.value = trackers.value.slice(0, trackIdx).concat(normalized).concat(trackers.value.slice(trackIdx + 1));
      updateMapFeatures();
      if (trackId === selectedId.value && followLocked.value) {
        scheduleCenterOnSelectedTrack();
      }
    } catch {
      // Keep existing in-memory geometry when the reconciling fetch fails.
    }
  }

  function handleTrackUpdated(data) {
    if (!data || !data.track_id) return;
    const updates = Array.isArray(data.updates)
      ? data.updates
      : (data.point != null ? [{ point: data.point, props: data.props, index: data.index }] : null);
    if (!updates?.length) return;
    const idx = trackers.value.findIndex((t) => t.id === data.track_id);
    if (idx < 0) return;
    const track = trackers.value[idx];
    const geom = track.geometry
      ? { ...track.geometry, coordinates: [...(track.geometry.coordinates || [])] }
      : { type: 'LineString', coordinates: [] };
    if (!geom.coordinates) geom.coordinates = [];
    let latestPointParams = {};
    const windowKey = sessionCache.getRecentDataWindow(track);
    const isSessionWindow = sessionCache.isSessionWindowTrack(track);
    let reloadAfterApply = false;
    let activeSessionStartMs = sessionCache.getKnownStartMs(track);
    let appliedUpdateCount = 0;

    for (const u of updates) {
      const point = u?.point;
      if (!Array.isArray(point)) continue;
      const incomingSessionStartMs = sessionCache.getStartTimestampMsFromProps(u?.props);
      if (isSessionWindow && incomingSessionStartMs != null) {
        if (activeSessionStartMs != null && incomingSessionStartMs < activeSessionStartMs) {
          continue;
        }
        if (shouldReloadGeometryForSessionTransition(windowKey, activeSessionStartMs, incomingSessionStartMs)) {
          reloadAfterApply = true;
        }
        if (shouldClearGeometryForSessionTransition(windowKey, activeSessionStartMs, incomingSessionStartMs)) {
          geom.coordinates = [];
        }
        activeSessionStartMs = incomingSessionStartMs;
      }
      const indexOutOfBounds =
        typeof u.index === 'number' &&
        Number.isInteger(u.index) &&
        (u.index < 0 || u.index > geom.coordinates.length);
      if (indexOutOfBounds) {
        reconcileOutOfBoundsPoint(data.track_id);
        return;
      }
      if (typeof u.index === 'number' && Number.isInteger(u.index)) {
        geom.coordinates.splice(u.index, 0, point);
      } else {
        geom.coordinates.push(point);
      }
      appliedUpdateCount += 1;
      if (u.props && typeof u.props === 'object') latestPointParams = u.props;
    }
    if (appliedUpdateCount === 0) return;

    if (isRollingRecentDataWindow(windowKey)) {
      geom.coordinates = pruneCoordinatesForRecentDataWindow(geom.coordinates, windowKey);
    }
    sessionCache.setKnownStartMs(track.id, isSessionWindow ? activeSessionStartMs : null);

    const last = geom.coordinates[geom.coordinates.length - 1];
    const newPoint = updates[updates.length - 1]?.point;
    const last_position = newPoint && newPoint.length >= 2
      ? { lon: newPoint[0], lat: newPoint[1] }
      : (last && last.length >= 2 ? { lon: last[0], lat: last[1] } : null);
    const rawLastTs = newPoint && newPoint.length >= 3 ? newPoint[2] : (last && last.length >= 3 ? last[2] : null);
    const last_timestamp_ms = rawLastTs != null ? normalizeTimestampMs(rawLastTs) : null;
    const updated = {
      ...track,
      geometry: geom,
      last_position,
      last_timestamp_ms,
      updated_at_ms: normalizeTimestampMs(track.updated_at),
      latestPointParams
    };
    trackers.value = trackers.value.slice(0, idx).concat(updated).concat(trackers.value.slice(idx + 1));
    updateMapFeatures();
    if (data.track_id === selectedId.value && followLocked.value) {
      scheduleCenterOnSelectedTrack();
    }
    if (reloadAfterApply) {
      fetchAndMergeTracker?.(data.track_id);
    }
  }

  function connect() {
    trackUpdatedHandler = handleTrackUpdated;
    trackersLiveSocket.onReconnect = () => onReconnect?.();
    trackersLiveSocket.connect();
    trackersLiveSocket.unsubscribe('track_updated', trackUpdatedHandler);
    trackersLiveSocket.subscribe('track_updated', trackUpdatedHandler);
  }

  function disconnect() {
    trackersLiveSocket.onReconnect = null;
    if (trackUpdatedHandler) {
      trackersLiveSocket.unsubscribe('track_updated', trackUpdatedHandler);
    }
    trackersLiveSocket.disconnect();
  }

  return {
    connect,
    disconnect,
    refreshSessionCache
  };
}
