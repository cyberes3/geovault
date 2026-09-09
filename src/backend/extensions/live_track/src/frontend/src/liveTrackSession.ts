import type { Ref } from 'vue';
import { ref } from 'vue';
import { catalogRefreshLeavesTrunk, mergeTrackerGeometry } from './trackerGeometryMergePolicy';
import { LiveTrackHead } from './liveTrackHead';
import { normalizeTrackForMemory } from './trackNormalization';
import {
  SHARE_SOURCE_MODES,
  fetchShareJson,
  isShareNotAvailableStatus,
  shareDataUrlForInfo,
} from './shareDiscoveryUrls';
import type { LiveTrack, LiveTrackGroup } from './types/track';

export type SidebarKind = 'none' | 'track' | 'group' | 'layers' | 'settings' | 'groups';

export interface LiveTrackSessionHooks {
  destroyMap: () => void;
  disconnectSocket: () => void;
  stopLocation: () => void;
}

/**
 * Catalog, selection, sidebar stack, and keep-alive for the live-track view.
 */
export class LiveTrackSession {
  readonly trackers: Ref<LiveTrack[]>;
  readonly groups: Ref<LiveTrackGroup[]>;
  readonly selectedId: Ref<string | number | null>;
  readonly sidebar: Ref<SidebarKind>;
  readonly heads = new LiveTrackHead();
  private readonly revisions = new Map<string, number>();
  private hooks: LiveTrackSessionHooks | null = null;
  private active = false;

  constructor(trackers?: Ref<LiveTrack[]>, groups?: Ref<LiveTrackGroup[]>, selectedId?: Ref<string | number | null>) {
    this.trackers = trackers ?? ref([]);
    this.groups = groups ?? ref([]);
    this.selectedId = selectedId ?? ref(null);
    this.sidebar = ref('none');
  }

  attachHooks(hooks: LiveTrackSessionHooks): void {
    this.hooks = hooks;
  }

  activate(): void {
    this.active = true;
  }

  deactivate(): void {
    this.active = false;
    this.hooks?.disconnectSocket();
    this.hooks?.stopLocation();
    this.hooks?.destroyMap();
  }

  isActive(): boolean {
    return this.active;
  }

  noteRevision(trackId: string, revision: number | undefined): void {
    if (revision == null || !Number.isFinite(revision)) return;
    const id = String(trackId);
    const prev = this.revisions.get(id);
    if (prev == null || revision > prev) {
      this.revisions.set(id, revision);
    }
  }

  revisionGap(trackId: string, incoming: number | undefined): boolean {
    if (incoming == null || !Number.isFinite(incoming)) return false;
    const prev = this.revisions.get(String(trackId));
    if (prev == null) return false;
    return incoming > prev + 1;
  }

  replaceCatalog(list: LiveTrack[], options?: { wipeTrunk?: boolean }): void {
    const wipeTrunk = options?.wipeTrunk === true;
    const existingById = new Map(this.trackers.value.map((t) => [String(t.id), t]));
    this.trackers.value = list.map((incoming) => {
      const existing = existingById.get(String(incoming.id));
      if (!existing) return normalizeTrackForMemory(incoming);
      if (!wipeTrunk && existing.geometry) {
        return normalizeTrackForMemory(catalogRefreshLeavesTrunk(existing, incoming));
      }
      return normalizeTrackForMemory(mergeTrackerGeometry(existing, incoming));
    });
  }

  upsertTracker(incoming: LiveTrack): void {
    const idStr = String(incoming.id);
    const idx = this.trackers.value.findIndex((t) => String(t.id) === idStr);
    const existing = idx >= 0 ? this.trackers.value[idx] : null;
    const merged = normalizeTrackForMemory(mergeTrackerGeometry(existing, incoming));
    if (idx >= 0) {
      this.trackers.value = this.trackers.value.slice(0, idx).concat(merged).concat(this.trackers.value.slice(idx + 1));
      return;
    }
    this.trackers.value = [...this.trackers.value, merged];
  }

  removeTracker(trackId: string | number): void {
    const idStr = String(trackId);
    this.trackers.value = this.trackers.value.filter((t) => String(t.id) !== idStr);
    this.heads.remove(idStr);
    this.revisions.delete(idStr);
    if (String(this.selectedId.value) === idStr) {
      this.selectedId.value = null;
    }
  }

  openSidebar(kind: SidebarKind): void {
    this.sidebar.value = kind;
  }

  closeSidebar(): void {
    this.sidebar.value = 'none';
  }
}

export interface PublicShareSessionLike {
  status: 'idle' | 'loading' | 'ready' | 'invalid';
  info: unknown;
  error: string | null;
  shareId: string | null;
  resetForShareIdChange(shareId?: string | null): void;
  ensureInfo(signal?: AbortSignal): Promise<boolean>;
}

function createCoreShareSession(): PublicShareSessionLike {
  return new window.gv_core.sharing.PublicShareSession();
}

export class PublicTrackSession {
  readonly share: PublicShareSessionLike;
  status: 'idle' | 'loading' | 'ready' | 'invalid' = 'idle';
  error: string | null = null;
  sourceMode: string | null = null;
  dataUrl = '';
  payload: unknown = null;
  private pollTimer: ReturnType<typeof setInterval> | null = null;
  private onInvalidate: (() => void) | null = null;

  constructor(share?: PublicShareSessionLike) {
    this.share = share ?? createCoreShareSession();
  }

  async ensureSource(shareId: string, signal?: AbortSignal): Promise<boolean> {
    this.share.resetForShareIdChange(shareId);
    this.sourceMode = null;
    this.dataUrl = '';
    this.payload = null;
    const ok = await this.share.ensureInfo(signal);
    if (!ok || !this.share.info) {
      this.status = 'invalid';
      this.error = this.share.error ?? 'Invalid share link';
      return false;
    }
    const info = this.share.info as { share_access?: string };
    this.sourceMode = info.share_access || SHARE_SOURCE_MODES.WORLD;
    this.dataUrl = shareDataUrlForInfo(shareId, info);
    const dataResult = await fetchShareJson(this.dataUrl);
    if (!dataResult.ok) {
      this.status = 'invalid';
      this.error = isShareNotAvailableStatus(dataResult.status) ? 'Invalid share link' : 'Failed to load share';
      return false;
    }
    this.status = 'ready';
    this.error = null;
    this.payload = dataResult.data;
    return true;
  }

  watchPoll(poll: () => Promise<'ok' | 'invalid'>, intervalMs: number, onInvalidate: () => void): void {
    this.onInvalidate = onInvalidate;
    this.stopPoll();
    this.pollTimer = setInterval(() => {
      void poll().then((result) => {
        if (result === 'invalid') {
          this.status = 'invalid';
          this.error = 'Invalid share link';
          this.stopPoll();
          this.onInvalidate?.();
        }
      });
    }, intervalMs);
  }

  stopPoll(): void {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
  }

  deactivate(destroy: () => void): void {
    this.stopPoll();
    destroy();
  }
}
