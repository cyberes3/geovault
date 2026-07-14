import { ref } from 'vue';
import type { Map as MapLibreMap, Marker } from 'maplibre-gl';
import type { LocationMarkerCoords } from './types/gv-core';

const { createUserLocationMarker, updateUserLocationMarker, removeUserLocationMarker, geolocationManager } = window.gv_core;

export interface UseLiveTrackGeolocationDeps {
  getMap: () => MapLibreMap | null;
  onError?: (message: string) => void;
}

/**
 * "Show my location" tracking + marker for LiveTrackView, built on core's shared
 * `geolocationManager` singleton (so no stale bundled copy of the geolocation logic).
 */
export function useLiveTrackGeolocation({ getMap, onError }: UseLiveTrackGeolocationDeps) {
  const trackingEnabled = ref(false);
  const userLocation = ref<LocationMarkerCoords | null>(null);
  const locationMarker = ref<Marker | null>(null);

  function reportError(message: string): void {
    if (typeof onError === 'function') {
      onError(message);
    } else {
      window.gv_core.GeoVault.toast.error(message);
    }
  }

  /** Re-sync the marker onto the current map instance - call after a map style (re)load. */
  async function syncUserLocationMarker(): Promise<void> {
    const map = getMap();
    if (!trackingEnabled.value || !userLocation.value || !map) return;
    if (locationMarker.value) {
      removeUserLocationMarker(locationMarker.value);
    }
    locationMarker.value = await createUserLocationMarker(map, userLocation.value);
  }

  function stopLocationTracking(): void {
    geolocationManager.stopTracking();
    trackingEnabled.value = false;
    userLocation.value = null;
    if (locationMarker.value) {
      removeUserLocationMarker(locationMarker.value);
      locationMarker.value = null;
    }
  }

  async function handleLocationUpdate(coords: LocationMarkerCoords): Promise<void> {
    userLocation.value = coords;
    const map = getMap();
    if (!map) return;
    if (!locationMarker.value) {
      locationMarker.value = await createUserLocationMarker(map, coords);
      return;
    }
    updateUserLocationMarker(locationMarker.value, coords);
  }

  function handleLocationError(error: unknown): void {
    console.error('Geolocation error:', error);
    stopLocationTracking();
    const code = (error as { code?: number } | null)?.code;
    reportError(code === 1 ? 'Location permission denied.' : 'Failed to get your location.');
  }

  function toggleLocationTracking(): void {
    if (trackingEnabled.value) {
      stopLocationTracking();
      return;
    }
    // Use getCurrentPosition first to trigger the browser's permission prompt (more reliable
    // on localhost and in some browsers). Then start watchPosition for ongoing updates.
    trackingEnabled.value = true;
    void geolocationManager.getCurrentPosition()
      .then((coords) => {
        void handleLocationUpdate(coords);
        geolocationManager.startTracking((c) => void handleLocationUpdate(c), handleLocationError);
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
