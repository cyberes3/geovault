import type { Ref } from 'vue';
import type { GeoVaultSocketInstance } from '@geovault/extension-sdk';
import { normalizeTrackForMemory } from './trackNormalization';
import { createSessionStartCache } from './sessionStartCache';
import { latestCoordByTime } from './trackLastPoint';
import {
  isRollingRecentDataWindow,
  pruneCoordinatesForRecentDataWindow,
  shouldClearGeometryForSessionTransition,
  shouldReloadGeometryForSessionTransition
} from './recentDataWindowGeometryPolicy';
import type { ExtensionApi } from '@geovault/extension-sdk';
import type { LiveTrack, PointParams, TrackCoordinate, TrackGeometry } from './types/track';
import type { LiveTrackSession } from './liveTrackSession';

interface TrackUpdatedPointUpdate {
  point: TrackCoordinate;
  props?: PointParams;
  index?: number;
}

interface TrackUpdatedEventData {
  track_id: string | number;
  revision?: number;
  updates?: TrackUpdatedPointUpdate[];
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
  session?: LiveTrackSession;
}

function trackersLiveUrl(): string {
  if (typeof window === 'undefined') return '';
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}/ws/extensions/live-track/trackers-live/`;
}

function insertAligned(
  coords: TrackCoordinate[],
  params: PointParams[],
  index: number | undefined,
  point: TrackCoordinate,
  props: PointParams,
): void {
  while (params.length < coords.length) params.push({});
  if (params.length > coords.length) params.length = coords.length;
  if (typeof index === 'number' && Number.isInteger(index)) {
    coords.splice(index, 0, point);
    params.splice(index, 0, props);
    return;
  }
  coords.push(point);
  params.push(props);
}

/**
 * Live-track socket on core GeoVaultSocket. Wire is updates[] only; both arrays stay aligned.
 */
export function useLiveTrackSocket({
  api,
  trackers,
  selectedId,
  followLocked,
  updateMapFeatures,
  scheduleCenterOnSelectedTrack,
  fetchAndMergeTracker,
  onReconnect,
  session,
}: UseLiveTrackSocketDeps) {
  const sessionCache = createSessionStartCache();
  const socket: GeoVaultSocketInstance = new window.gv_core.GeoVaultSocket({
    url: trackersLiveUrl,
    pingPayload: { module: 'live_track', type: 'ping' },
  });

  function handleConnected(info: unknown): void {
    const reconnect = Boolean(
      info && typeof info === 'object' && (info as { reconnect?: boolean }).reconnect,
    );
    if (reconnect) {
      onReconnect?.();
    }
  }

  function refreshSessionCache(trackList: LiveTrack[] | null | undefined): void {
    sessionCache.refreshFromTrackers(trackList);
  }

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
    const updates = Array.isArray(data.updates) ? data.updates : [];
    if (!updates.length) return;
    if (session?.revisionGap(String(data.track_id), data.revision)) {
      fetchAndMergeTracker?.(data.track_id);
      session.noteRevision(String(data.track_id), data.revision);
      return;
    }
    session?.noteRevision(String(data.track_id), data.revision);
    const idx = trackers.value.findIndex((t) => t.id === data.track_id);
    if (idx < 0) return;
    const track = trackers.value[idx];
    const geom: TrackGeometry = track.geometry
      ? { ...track.geometry, coordinates: [...track.geometry.coordinates] }
      : { type: 'LineString', coordinates: [] };
    const params: PointParams[] = Array.isArray(track.point_params) ? [...track.point_params] : [];
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
          params.length = 0;
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
      insertAligned(geom.coordinates, params, u.index, point, u.props && typeof u.props === 'object' ? u.props : {});
      appliedUpdateCount += 1;
      session?.heads.upsert(String(data.track_id), point, u.props && typeof u.props === 'object' ? u.props : {});
    }
    if (appliedUpdateCount === 0) return;

    if (isRollingRecentDataWindow(windowKey)) {
      const kept = pruneCoordinatesForRecentDataWindow(geom.coordinates, windowKey);
      if (kept.length !== geom.coordinates.length && params.length === geom.coordinates.length) {
        const keepIdx = new Set(kept.map((c) => geom.coordinates.indexOf(c)));
        const nextParams = params.filter((_, i) => keepIdx.has(i));
        geom.coordinates = kept;
        params.length = 0;
        params.push(...nextParams);
      } else {
        geom.coordinates = kept;
      }
    }
    sessionCache.setKnownStartMs(track.id, isSessionWindow ? activeSessionStartMs : null);

    const last = latestCoordByTime(geom.coordinates);
    const lastIdx = last ? geom.coordinates.findIndex((c) => c === last) : -1;
    const nextParams = lastIdx >= 0 && lastIdx < params.length ? params[lastIdx] : {};
    const updated: LiveTrack = normalizeTrackForMemory({
      ...track,
      geometry: geom,
      point_params: params,
      last_point: last ?? track.last_point,
      latestPointParams: nextParams,
    });
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
    socket.on('connected', handleConnected);
    socket.on('track_updated', handleTrackUpdated);
    socket.connect();
  }

  function disconnect(): void {
    socket.off('connected', handleConnected);
    socket.off('track_updated', handleTrackUpdated);
    socket.disconnect();
  }

  return {
    connect,
    disconnect,
    refreshSessionCache
  };
}
