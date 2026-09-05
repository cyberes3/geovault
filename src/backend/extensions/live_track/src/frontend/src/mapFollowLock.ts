import type { Map as MapLibreMap } from 'maplibre-gl';

export type FollowLockInteraction = 'pan' | 'rotate' | 'pinch' | 'wheel' | 'dblclick';

export interface MapFollowListenerOptions {
  getLocked: () => boolean;
  setLocked: (value: boolean) => void;
  onUnlock?: () => void;
}

/** Matches GeoVaultMapCameraInteractionGate: pan/rotate unlock; pinch, wheel, double-click keep lock. */
export function followLockUnlocks(interaction: FollowLockInteraction): boolean {
  return interaction === 'pan' || interaction === 'rotate';
}

/**
 * Shared follow-lock behavior for single-track map views.
 * LiveTrackView and WorldShareView both call this one function.
 */
export function setupMapFollowListeners(map: MapLibreMap | null | undefined, { getLocked, setLocked, onUnlock }: MapFollowListenerOptions): void {
  if (!map) return;
  const breakLock = () => {
    if (!getLocked()) return;
    setLocked(false);
    if (typeof onUnlock === 'function') {
      try {
        onUnlock();
      } catch {
        // ignore (e.g. transient map update errors while styles reload)
      }
    }
  };
  const onUnlocking = (interaction: FollowLockInteraction) => {
    if (!followLockUnlocks(interaction)) return;
    breakLock();
  };
  map.on('dragstart', () => onUnlocking('pan'));
  map.on('rotatestart', () => onUnlocking('rotate'));
}
