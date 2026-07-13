import { ref } from 'vue';

const { createUserLocationMarker, updateUserLocationMarker, removeUserLocationMarker, geolocationManager } = window.gv_core;

/**
 * "Show my location" tracking + marker for LiveTrackView, built on core's shared
 * `geolocationManager` singleton (so no stale bundled copy of the geolocation logic).
 *
 * @param {object} deps
 * @param {() => import('maplibre-gl').Map | null} deps.getMap
 * @param {(message: string) => void} [deps.onError]
 */
export function useLiveTrackGeolocation({ getMap, onError }) {
  const trackingEnabled = ref(false);
  const userLocation = ref(null);
  const locationMarker = ref(null);

  function reportError(message) {
    if (typeof onError === 'function') {
      onError(message);
    } else if (window.gv_core?.GeoVault?.toast) {
      window.gv_core.GeoVault.toast.error(message);
    }
  }

  /** Re-sync the marker onto the current map instance - call after a map style (re)load. */
  async function syncUserLocationMarker() {
    const map = getMap();
    if (!trackingEnabled.value || !userLocation.value || !map) return;
    if (locationMarker.value) {
      removeUserLocationMarker(locationMarker.value);
    }
    locationMarker.value = await createUserLocationMarker(map, userLocation.value);
  }

  function stopLocationTracking() {
    geolocationManager.stopTracking();
    trackingEnabled.value = false;
    userLocation.value = null;
    if (locationMarker.value) {
      removeUserLocationMarker(locationMarker.value);
      locationMarker.value = null;
    }
  }

  async function handleLocationUpdate(coords) {
    userLocation.value = coords;
    const map = getMap();
    if (!map || !coords) return;
    if (!locationMarker.value) {
      locationMarker.value = await createUserLocationMarker(map, coords);
      return;
    }
    updateUserLocationMarker(locationMarker.value, coords);
  }

  function handleLocationError(error) {
    console.error('Geolocation error:', error);
    stopLocationTracking();
    reportError(error?.code === 1 ? 'Location permission denied.' : 'Failed to get your location.');
  }

  function toggleLocationTracking() {
    if (trackingEnabled.value) {
      stopLocationTracking();
      return;
    }
    // Use getCurrentPosition first to trigger the browser's permission prompt (more reliable
    // on localhost and in some browsers). Then start watchPosition for ongoing updates.
    trackingEnabled.value = true;
    geolocationManager.getCurrentPosition()
      .then((coords) => {
        void handleLocationUpdate(coords);
        geolocationManager.startTracking(handleLocationUpdate, handleLocationError);
      })
      .catch(handleLocationError);
  }

  return {
    trackingEnabled,
    syncUserLocationMarker,
    stopLocationTracking,
    toggleLocationTracking
  };
}
