import type { Ref } from 'vue';
import { trackersLiveSocket } from './trackersLiveSocket';
import type { TrackersLiveSocketHandler } from './trackersLiveSocket';
import { normalizeTrackForMemory } from './trackNormalization';
import { createSessionStartCache } from './sessionStartCache';
import { normalizeTimestampMs } from './activeButDeadTrack';
import {
  isRollingRecentDataWindow,
  pruneCoordinatesForRecentDataWindow,
  shouldClearGeometryForSessionTransition,
  shouldReloadGeometryForSessionTransition
} from './recentDataWindowGeometryPolicy';
import type { ExtensionApi } from './types/extension-api';
import type { LiveTrack, PointParams, TrackCoordinate, TrackGeometry } from './types/track';

interface TrackUpdatedPointUpdate {
  point: TrackCoordinate;
  props?: PointParams;
  index?: number;
}

interface TrackUpdatedEventData {
  track_id: string | number;
  updates?: TrackUpdatedPointUpdate[];
  point?: TrackCoordinate;
  props?: PointParams;
  index?: number;
}

export interface UseLiveTrackSocketDeps {
  api: ExtensionApi;
  trackers: Ref<LiveTrack[]>;
  selectedId: Ref<string | number | null>;
  followLocked: Ref<boolean>;
  updateMapFeatures: () => Promise<void>;
  scheduleCenterOnSelectedTrack: () => void;
  fetchAndMergeTracker?: (trackId: string | number) => void;
  onReconnect?: () => void;
}

/**
 * Wires the `trackers-live` websocket (`trackersLiveSocket`) into `trackers.value`, applying
 * incremental `track_updated` events in place instead of a full refetch, and coalescing map
 * redraws via the caller-supplied `updateMapFeatures` (see {@link createCoalescedTask} in
 * `asyncTaskCoalescer.ts`).
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
}: UseLiveTrackSocketDeps) {
  const sessionCache = createSessionStartCache();
  let trackUpdatedHandler: TrackersLiveSocketHandler | null = null;

  /** Rebuild the session-start cache from a freshly-fetched track list, e.g. after `fetchTrackers()`. */
  function refreshSessionCache(trackList: LiveTrack[] | null | undefined): void {
    sessionCache.refreshFromTrackers(trackList);
  }

  /** A point arrived with an index outside the currently-known geometry; re-fetch just the geometry to reconcile. */
  async function reconcileOutOfBoundsPoint(trackId: string | number): Promise<void> {
    try {
      const geomRes = await api.get(`/trackers/${trackId}/geometry/`);
      const trackIdx = trackers.value.findIndex((t) => t.id === trackId);
      if (trackIdx < 0) return;
      const existing = trackers.value[trackIdx];
      const normalized = normalizeTrackForMemory({
        ...(geomRes.data as Partial<LiveTrack>),
        is_owner: existing.is_owner,
        owner_email: existing.owner_email,
        visibility: existing.visibility
      } as LiveTrack);
      trackers.value = trackers.value.slice(0, trackIdx).concat(normalized).concat(trackers.value.slice(trackIdx + 1));
      void updateMapFeatures();
      if (trackId === selectedId.value && followLocked.value) {
        scheduleCenterOnSelectedTrack();
      }
    } catch {
      // Keep existing in-memory geometry when the reconciling fetch fails.
    }
  }

  function handleTrackUpdated(rawData: unknown): void {
    const data = rawData as TrackUpdatedEventData | null | undefined;
    if (!data?.track_id) return;
    const updates: TrackUpdatedPointUpdate[] | null = Array.isArray(data.updates)
      ? data.updates
      : (data.point != null ? [{ point: data.point, props: data.props, index: data.index }] : null);
    if (!updates?.length) return;
    const idx = trackers.value.findIndex((t) => t.id === data.track_id);
    if (idx < 0) return;
    const track = trackers.value[idx];
    const geom: TrackGeometry = track.geometry
      ? { ...track.geometry, coordinates: [...track.geometry.coordinates] }
      : { type: 'LineString', coordinates: [] };
    let latestPointParams: PointParams = {};
    const windowKey = sessionCache.getRecentDataWindow(track);
    const isSessionWindow = sessionCache.isSessionWindowTrack(track);
    let reloadAfterApply = false;
    let activeSessionStartMs = sessionCache.getKnownStartMs(track);
    let appliedUpdateCount = 0;

    for (const u of updates) {
      const point = u.point;
      if (!Array.isArray(point)) continue;
      const incomingSessionStartMs = sessionCache.getStartTimestampMsFromProps(u.props);
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
        void reconcileOutOfBoundsPoint(data.track_id);
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

    const newPoint = updates[updates.length - 1].point;
    const last_position = { lon: newPoint[0], lat: newPoint[1] };
    const rawLastTs = newPoint.length >= 3 ? newPoint[2] : null;
    const last_timestamp_ms = rawLastTs != null ? normalizeTimestampMs(rawLastTs) : null;
    const updated: LiveTrack = {
      ...track,
      geometry: geom,
      last_position,
      last_timestamp_ms,
      updated_at_ms: normalizeTimestampMs(track.updated_at),
      latestPointParams
    };
    trackers.value = trackers.value.slice(0, idx).concat(updated).concat(trackers.value.slice(idx + 1));
    void updateMapFeatures();
    if (data.track_id === selectedId.value && followLocked.value) {
      scheduleCenterOnSelectedTrack();
    }
    if (reloadAfterApply) {
      fetchAndMergeTracker?.(data.track_id);
    }
  }

  function connect(): void {
    trackUpdatedHandler = handleTrackUpdated;
    trackersLiveSocket.onReconnect = () => onReconnect?.();
    trackersLiveSocket.connect();
    trackersLiveSocket.unsubscribe('track_updated', trackUpdatedHandler);
    trackersLiveSocket.subscribe('track_updated', trackUpdatedHandler);
  }

  function disconnect(): void {
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
