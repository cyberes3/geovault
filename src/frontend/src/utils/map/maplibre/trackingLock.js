/**
 * Register map listeners that unlock location tracking only on explicit user gestures.
 *
 * @param {import('maplibre-gl').Map} map
 * @param {{ isLocked: () => boolean, onUnlock: () => void }} options
 * @returns {() => void} teardown function
 */
export function setupUserGestureTrackingUnlock(map, { isLocked, onUnlock }) {
  if (!map) return () => {}

  const unlock = () => {
    if (!isLocked()) return
    onUnlock()
  }

  const onDragStart = () => unlock()
  const onWheel = () => unlock()
  const onDoubleClick = () => unlock()
  const onZoomStart = (e) => {
    const type = e.originalEvent?.type
    if (type === 'touchstart' || type === 'touchmove' || type === 'wheel') {
      unlock()
    }
  }

  map.on('dragstart', onDragStart)
  map.on('wheel', onWheel)
  map.on('dblclick', onDoubleClick)
  map.on('zoomstart', onZoomStart)

  return () => {
    map.off('dragstart', onDragStart)
    map.off('wheel', onWheel)
    map.off('dblclick', onDoubleClick)
    map.off('zoomstart', onZoomStart)
  }
}
