/**
 * User geolocation: IP-based initial location fetch, live tracking via `geolocationManager`,
 * the user-location marker, and the "center to my location" / "center to home extent" camera
 * actions used by `LocationControl`.
 */
import { ref, shallowRef, type ComputedRef, type Ref, type ShallowRef } from 'vue';
import maplibregl, { type Map as MapLibreMap, type Marker } from 'maplibre-gl';
import { getUserLocation as fetchUserLocation, type UserLocation } from '@/api/services/locationApi';
import { createUserLocationMarker, updateUserLocationMarker, removeUserLocationMarker } from '@/utils/map/maplibre';
import { geolocationManager } from '@/utils/map/geolocationManager';
import { getLocationDisplayName as formatLocationDisplayName, getMapRecenterFromUserLocation } from '@/utils/map/mapConfigUtils';
import { MAX_ZOOM_LEVEL } from '@/utils/map/maplibre/mapInitialization.js';
import { toast } from '@/utils/toast';
import type { TrackingState } from './mapPageTypes';

export interface MapGeolocationDeps {
    map: ShallowRef<MapLibreMap | null>;
    isMapshareRoute: ComputedRef<boolean>;
    /** Performs a camera move then re-triggers the current bbox data load (see `useFeatureData`). */
    navigateAndRefresh: (navigationFn: () => void, clearAllBounds?: boolean) => Promise<void>;
}

/** Forward-geocoding search result shape (search box in `FeatureListSidebar`). */
export interface GeocodingResult {
    coordinates?: number[];
    center?: number[];
    bbox?: number[];
}

export function useMapGeolocation(deps: MapGeolocationDeps) {
    const { map, isMapshareRoute, navigateAndRefresh } = deps;

    const userLocation: Ref<UserLocation | null> = ref(null);
    const trackingState: Ref<TrackingState> = ref('disabled');
    const locationMarker: ShallowRef<Marker | null> = shallowRef(null);
    const hasInitialZoomed = ref(false);
    const geocodingMarker: ShallowRef<Marker | null> = shallowRef(null);

    async function getUserLocation(): Promise<void> {
        userLocation.value = await fetchUserLocation();
    }

    function getLocationDisplayName(): string {
        return formatLocationDisplayName(userLocation.value);
    }

    /**
     * Step 2 of boot: move from world (0,0) to geolocation-based view on the main map only.
     * `/mapshare` skips this (no location fetch); camera is set by fitting loaded share features.
     */
    function applyInitialUserLocationRecenter(): void {
        if (isMapshareRoute.value || !map.value) return;
        const cfg = getMapRecenterFromUserLocation(userLocation.value);
        if (!cfg) return;
        map.value.jumpTo({ center: cfg.center, zoom: cfg.zoom });
    }

    /** True when the main map (non-mapshare) has a usable geolocation-based recenter target. */
    function hasUsableUserLocationForRecenter(): boolean {
        return !isMapshareRoute.value && !!getMapRecenterFromUserLocation(userLocation.value);
    }

    function centerToUserLocation(shouldZoom = true): void {
        if (!map.value || !userLocation.value) return;

        const { latitude, longitude } = userLocation.value;

        if (shouldZoom) {
            map.value.flyTo({ center: [longitude, latitude], zoom: 10, duration: 500 });
        } else {
            map.value.panTo([longitude, latitude], { duration: 500 });
        }
    }

    function centerToHomeExtent(): void {
        if (!map.value) return;

        const stateLevelZoom = 6;

        if (userLocation.value) {
            const { latitude, longitude } = userLocation.value;
            void navigateAndRefresh(() => {
                map.value?.flyTo({
                    center: [longitude, latitude],
                    zoom: stateLevelZoom,
                    pitch: 0,
                    bearing: 0,
                    duration: 500,
                });
            });
            return;
        }

        void navigateAndRefresh(() => {
            map.value?.flyTo({
                zoom: stateLevelZoom,
                pitch: 0,
                bearing: 0,
                duration: 500,
            });
        });
    }

    function handleLocationUpdate(coords: UserLocation): void {
        userLocation.value = coords;

        if (!locationMarker.value && map.value) {
            locationMarker.value = createUserLocationMarker(map.value, coords) as Marker;
        } else if (locationMarker.value) {
            updateUserLocationMarker(locationMarker.value, coords);
        }

        if (trackingState.value === 'locked') {
            const shouldZoom = !hasInitialZoomed.value;
            centerToUserLocation(shouldZoom);
            if (shouldZoom) {
                hasInitialZoomed.value = true;
            }
        }
    }

    function handleLocationError(error: { code?: number; message?: string }): void {
        console.error('Geolocation error:', error);
        trackingState.value = 'disabled';
        hasInitialZoomed.value = false;
        if (locationMarker.value) {
            removeUserLocationMarker(locationMarker.value);
            locationMarker.value = null;
        }

        if (error.code === 1) {
            toast.error('Location permission denied.');
        } else {
            toast.error('Failed to get your location.');
        }
    }

    async function toggleLocationTracking(): Promise<void> {
        if (trackingState.value === 'locked') {
            return;
        }

        if (trackingState.value === 'disabled') {
            const permission = await geolocationManager.checkPermission();
            if (permission === 'denied') {
                toast.error('Location permission denied. Please enable it in your browser settings.');
                return;
            }

            trackingState.value = 'locked';
            hasInitialZoomed.value = false;
            geolocationManager.startTracking(
                (coords: UserLocation) => { handleLocationUpdate(coords); },
                (error: { code?: number; message?: string }) => { handleLocationError(error); },
            );
        } else {
            trackingState.value = 'locked';
            if (userLocation.value) {
                centerToUserLocation(false);
            }
        }
    }

    /** Forward-geocoding search result marker (search box in `FeatureListSidebar`), not the user's own location. */
    async function handleGeocodingResult(result: GeocodingResult | null): Promise<void> {
        if (!map.value || !result) return;
        const mapInstance = map.value;

        let coordinates: number[] | null = null;
        if (result.coordinates && Array.isArray(result.coordinates)) {
            coordinates = result.coordinates;
        } else if (result.center && Array.isArray(result.center)) {
            coordinates = result.center;
        } else {
            console.error('Forward reverse_geocoding result missing coordinates:', result);
            return;
        }

        const bbox = result.bbox;
        if (!bbox || !Array.isArray(bbox) || bbox.length !== 4) {
            console.error('Forward reverse_geocoding result missing bbox:', result);
            return;
        }

        if (geocodingMarker.value) {
            geocodingMarker.value.remove();
            geocodingMarker.value = null;
        }

        let iconUrl: string | null = null;
        try {
            const iconModule = (await import('@/assets/img/search.png')) as { default?: string };
            iconUrl = iconModule.default ?? null;
        } catch (error) {
            console.warn('Could not import search icon, using fallback marker:', error);
        }

        const el = document.createElement('div');
        el.style.cursor = 'pointer';

        if (iconUrl) {
            const img = document.createElement('img');
            img.src = iconUrl;
            img.style.width = '32px';
            img.style.height = '32px';
            img.style.display = 'block';
            el.appendChild(img);
        } else {
            el.style.width = '20px';
            el.style.height = '20px';
            el.style.borderRadius = '50%';
            el.style.backgroundColor = '#3b82f6';
            el.style.border = '2px solid white';
            el.style.boxShadow = '0 2px 4px rgba(0,0,0,0.3)';
        }

        el.addEventListener('click', () => {
            clearGeocodingMarker();
        });

        geocodingMarker.value = new maplibregl.Marker({ element: el, anchor: 'bottom' }).setLngLat([coordinates[0], coordinates[1]]).addTo(mapInstance);

        const [minLon, minLat, maxLon, maxLat] = bbox;
        const isDegenerate = minLon === maxLon && minLat === maxLat;

        if (isDegenerate) {
            await navigateAndRefresh(() => {
                mapInstance.flyTo({ center: [coordinates[0], coordinates[1]], zoom: 15, duration: 500 });
            });
        } else {
            await navigateAndRefresh(() => {
                const bounds = new maplibregl.LngLatBounds([minLon, minLat], [maxLon, maxLat]);
                mapInstance.fitBounds(bounds, { padding: { top: 50, bottom: 50, left: 50, right: 50 }, duration: 500, maxZoom: MAX_ZOOM_LEVEL });
            });
        }
    }

    function clearGeocodingMarker(): void {
        if (geocodingMarker.value) {
            geocodingMarker.value.remove();
            geocodingMarker.value = null;
        }
    }

    /** Stop tracking and remove the location marker; call from `beforeUnmount`. */
    function cleanup(): void {
        geolocationManager.stopTracking();
        hasInitialZoomed.value = false;
        if (locationMarker.value) {
            removeUserLocationMarker(locationMarker.value);
            locationMarker.value = null;
        }
    }

    return {
        userLocation,
        trackingState,
        locationMarker,
        geocodingMarker,
        getUserLocation,
        getLocationDisplayName,
        applyInitialUserLocationRecenter,
        hasUsableUserLocationForRecenter,
        centerToUserLocation,
        centerToHomeExtent,
        handleLocationUpdate,
        handleLocationError,
        toggleLocationTracking,
        handleGeocodingResult,
        clearGeocodingMarker,
        cleanup,
    };
}
