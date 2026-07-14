/**
 * User Location Marker Utility
 * Handles the creation and management of the user's location marker on the map.
 */

import type { Map as MapLibreMap, Marker } from 'maplibre-gl'
import { loadMaplibreGl } from './lazyMaplibreGl.js'

export interface LocationMarkerCoords {
  latitude: number
  longitude: number
}

/**
 * Create a user location marker
 */
export async function createUserLocationMarker(map: MapLibreMap | null | undefined, coords: LocationMarkerCoords | null | undefined): Promise<Marker | null> {
  if (!map || !coords) return null
  const maplibregl = await loadMaplibreGl()

  const el = document.createElement('div')
  el.className = 'user-location-marker'
  el.style.width = '20px'
  el.style.height = '20px'
  el.style.borderRadius = '50%'
  el.style.backgroundColor = '#3B82F6' // Blue-500
  el.style.border = '2px solid white'
  el.style.boxShadow = '0 0 5px rgba(0,0,0,0.3)'
  el.style.cursor = 'pointer'

  const marker = new maplibregl.Marker({
    element: el,
    pitchAlignment: 'map',
    rotationAlignment: 'map'
  })
    .setLngLat([coords.longitude, coords.latitude])
    .addTo(map)

  return marker
}

/**
 * Update user location marker position
 */
export function updateUserLocationMarker(marker: Marker | null | undefined, coords: LocationMarkerCoords | null | undefined): void {
  if (marker && coords) {
    marker.setLngLat([coords.longitude, coords.latitude])
  }
}

/**
 * Remove user location marker
 */
export function removeUserLocationMarker(marker: Marker | null | undefined): void {
  if (marker) {
    marker.remove()
  }
}
