import type { Map as MapLibreMap, LngLatLike } from 'maplibre-gl'

/**
 * Restore map view (center, zoom, pitch, bearing) after layer switch
 */
export function restoreMapView(
  map: MapLibreMap | null | undefined,
  center: LngLatLike,
  zoom: number,
  pitch: number,
  bearing: number
): void {
  if (!map) return
  map.setCenter(center)
  map.setZoom(zoom)
  map.setPitch(pitch)
  map.setBearing(bearing)
}

export interface MapState {
  center: ReturnType<MapLibreMap['getCenter']>
  zoom: number
  pitch: number
  bearing: number
}

/**
 * Extract current map state for restoration
 */
export function getMapState(map: MapLibreMap | null | undefined): MapState | null {
  if (!map) return null

  return {
    center: map.getCenter(),
    zoom: map.getZoom(),
    pitch: map.getPitch(),
    bearing: map.getBearing()
  }
}
