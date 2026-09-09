import type { Map as MapLibreMap, Marker } from 'maplibre-gl';
import { getUserLocation as fetchUserLocation, type UserLocation } from '@/api/services/locationApi';
import { createUserLocationMarker, updateUserLocationMarker, removeUserLocationMarker } from '@/utils/map/maplibre';
import { geolocationManager, type GeolocationErrorLike } from '@/utils/map/geolocationManager';
import { getLocationDisplayName as formatLocationDisplayName } from '@/utils/map/mapConfigUtils';
import { WORLD_VIEW_CENTER_LONLAT, WORLD_VIEW_ZOOM } from '@/utils/map/worldViewDefault';
import type { CameraSnapshot, LocationTrackingMode } from './types';

function cityAwareZoom(location: UserLocation): number {
    if (location.city) return 10;
    if (location.country && !location.state) return 4;
    if (location.state) return 6;
    return 6;
}

export class LocationRuntime {
    ipHint: UserLocation | null = null;
    gpsFix: UserLocation | null = null;
    mode: LocationTrackingMode = 'off';
    onChange: (() => void) | null = null;
    private marker: Marker | null = null;
    private hasFollowZoomed = false;
    private onGpsError: ((error: GeolocationErrorLike) => void) | null = null;

    get displayLocation(): UserLocation | null {
        return this.gpsFix ?? this.ipHint;
    }

    get hasIpHint(): boolean {
        return this.ipHint?.longitude != null
            && Number.isFinite(Number(this.ipHint.longitude))
            && Number.isFinite(Number(this.ipHint.latitude));
    }

    displayName(): string {
        return formatLocationDisplayName(this.displayLocation);
    }

    bootCamera(): CameraSnapshot | null {
        if (!this.hasIpHint || !this.ipHint) return null;
        return {
            center: [this.ipHint.longitude, this.ipHint.latitude],
            zoom: cityAwareZoom(this.ipHint),
            pitch: 0,
            bearing: 0,
        };
    }

    worldCamera(): CameraSnapshot {
        return { center: WORLD_VIEW_CENTER_LONLAT, zoom: WORLD_VIEW_ZOOM, pitch: 0, bearing: 0 };
    }

    async fetchIpHint(): Promise<UserLocation | null> {
        this.ipHint = await fetchUserLocation();
        this.notify();
        return this.ipHint;
    }

    async startWatch(
        map: MapLibreMap | null,
        mode: LocationTrackingMode = 'follow',
        onError?: (error: GeolocationErrorLike) => void,
    ): Promise<void> {
        this.mode = mode;
        this.hasFollowZoomed = false;
        this.onGpsError = onError ?? null;
        this.notify();
        geolocationManager.startTracking(
            (coords) => { void this.onGps(map, coords); },
            (error) => {
                this.onGpsError?.(error);
                this.stopWatch();
            },
        );
    }

    stopWatch(): void {
        geolocationManager.stopTracking();
        this.mode = 'off';
        this.hasFollowZoomed = false;
        this.onGpsError = null;
        if (this.marker) {
            removeUserLocationMarker(this.marker);
            this.marker = null;
        }
        this.notify();
    }

    cleanup(): void {
        this.stopWatch();
    }

    toUiTrackingState(): 'disabled' | 'tracking' | 'locked' {
        if (this.mode === 'follow') return 'locked';
        if (this.mode === 'show-only') return 'tracking';
        return 'disabled';
    }

    unlockFollow(): void {
        if (this.mode === 'follow') {
            this.mode = 'show-only';
            this.notify();
        }
    }

    follow(): void {
        this.mode = 'follow';
        this.notify();
    }

    private notify(): void {
        this.onChange?.();
    }

    private async onGps(map: MapLibreMap | null, coords: UserLocation): Promise<void> {
        this.gpsFix = coords;
        this.notify();
        if (!map) return;
        if (!this.marker) {
            this.marker = await createUserLocationMarker(map, coords) as Marker;
        } else {
            updateUserLocationMarker(this.marker, coords);
        }
        if (this.mode === 'follow') {
            const zoom = this.hasFollowZoomed ? map.getZoom() : 10;
            map.flyTo({ center: [coords.longitude, coords.latitude], zoom, duration: 500 });
            this.hasFollowZoomed = true;
        }
    }
}
