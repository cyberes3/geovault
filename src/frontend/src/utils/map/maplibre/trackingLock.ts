import type { Map as MaplibreMap, MapLibreEvent } from 'maplibre-gl'

interface UserGestureTrackingUnlockOptions {
  isLocked: () => boolean
  onUnlock: () => void
}

/** Register map listeners that unlock location tracking only on explicit user gestures. */
export function setupUserGestureTrackingUnlock(map: MaplibreMap | null | undefined, { isLocked, onUnlock }: UserGestureTrackingUnlockOptions): () => void {
  if (!map) return () => {}

  const unlock = () => {
    if (!isLocked()) return
    onUnlock()
  }

  const onDragStart = () => { unlock() }
  const onWheel = () => { unlock() }
  const onDoubleClick = () => { unlock() }
  const onZoomStart = (e: MapLibreEvent<MouseEvent | TouchEvent | WheelEvent | undefined>) => {
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
