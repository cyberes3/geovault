/**
 * User Location Marker Utility
 * Handles the creation and management of the user's location marker on the map.
 */

import maplibregl from 'maplibre-gl'

/**
 * Create a user location marker
 * @param {Object} map - MapLibre map instance
 * @param {Object} coords - {latitude, longitude}
 * @returns {Object} MapLibre Marker instance
 */
export function createUserLocationMarker(map, coords) {
  if (!map || !coords) return null;

  const el = document.createElement('div');
  el.className = 'user-location-marker';
  el.style.width = '20px';
  el.style.height = '20px';
  el.style.borderRadius = '50%';
  el.style.backgroundColor = '#3B82F6'; // Blue-500
  el.style.border = '2px solid white';
  el.style.boxShadow = '0 0 5px rgba(0,0,0,0.3)';
  el.style.cursor = 'pointer';

  const marker = new maplibregl.Marker({
    element: el,
    pitchAlignment: 'map',
    rotationAlignment: 'map'
  })
  .setLngLat([coords.longitude, coords.latitude])
  .addTo(map);

  return marker;
}

/**
 * Update user location marker position
 * @param {Object} marker - MapLibre Marker instance
 * @param {Object} coords - {latitude, longitude}
 */
export function updateUserLocationMarker(marker, coords) {
  if (marker && coords) {
    marker.setLngLat([coords.longitude, coords.latitude]);
  }
}

/**
 * Remove user location marker
 * @param {Object} marker - MapLibre Marker instance
 */
export function removeUserLocationMarker(marker) {
  if (marker) {
    marker.remove();
  }
}
