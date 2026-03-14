/**
 * Shared follow-lock behavior for single-track map views.
 * When the user drags, zooms, or double-clicks, the lock is cleared.
 * Used by LiveTrackView and WorldShareView.
 *
 * @param {import('maplibre-gl').Map} map - MapLibre map instance
 * @param {{ getLocked: () => boolean, setLocked: (value: boolean) => void, onUnlock?: () => void }} options
 */
export function setupMapFollowListeners(map, { getLocked, setLocked, onUnlock }) {
  if (!map) return;
  const breakLock = () => {
    if (!getLocked()) return;
    setLocked(false);
    if (typeof onUnlock === 'function') {
      try {
        onUnlock();
      } catch (_) {
        // ignore (e.g. transient map update errors while styles reload)
      }
    }
  };
  map.on('dragstart', breakLock);
  map.on('wheel', breakLock);
  map.on('dblclick', breakLock);
  map.on('zoomstart', (e) => {
    const type = e.originalEvent?.type;
    if (type === 'touchstart' || type === 'touchmove' || type === 'wheel') {
      breakLock();
    }
  });
}
