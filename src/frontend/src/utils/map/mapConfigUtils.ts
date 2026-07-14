/**
 * Map Configuration Utilities
 *
 * Functions for map initialization, zoom level calculation, and location display.
 */

import type {MapConfig} from '@/types/geospatial';
import type {UserLocation} from '@/api/services/locationApi';
import {WORLD_VIEW_CENTER_LONLAT, WORLD_VIEW_ZOOM} from '@/utils/map/worldViewDefault';
import store from '@/assets/js/store';

/**
 * Calculate appropriate zoom level based on location type
 * @param location - Location data
 * @returns Zoom level
 */
function calculateZoomLevel(location: UserLocation): number {
    // Base zoom levels for different administrative levels
    const baseZooms = {
        'city': 10,      // City level - close up
        'state': 6,      // State/province level - shows entire state
        'country': 4     // Country level - shows entire country
    };

    // If we have city data, we're likely in a state/province
    if (location.city) {
        return baseZooms.state;
    }

    // If we only have country data, show the country
    if (location.country && !location.state) {
        return baseZooms.country;
    }

    // Default to state level if we have state data
    if (location.state) {
        return baseZooms.state;
    }

    // Fallback to moderate zoom
    return 6;
}

/**
 * Get state extent configuration
 * @param location - Location data
 * @returns Map configuration
 */
function getStateExtentConfig(location: UserLocation): MapConfig {
    const zoomLevel = calculateZoomLevel(location);

    return {
        center: [location.longitude, location.latitude],
        zoom: zoomLevel
    };
}

/**
 * Fallback world view: origin (0,0) at low zoom, used only when there's no better answer yet
 * (no geolocation, URL-driven view, or mapshare fit target). Callers resolve the real initial
 * camera (geolocation recenter, feature-extent fit, etc.) upfront and pass it straight into map
 * construction, so this is not the guaranteed first-paint state - just the last resort.
 */
export function getInitialMapConfig(): MapConfig {
    return {
        center: WORLD_VIEW_CENTER_LONLAT,
        zoom: WORLD_VIEW_ZOOM
    };
}

/**
 * After IP / geolocation resolves, center and zoom for the authenticated main map (not used on mapshare).
 */
export function getMapRecenterFromUserLocation(userLocation: UserLocation | null | undefined): MapConfig | null {
    if (
        userLocation?.longitude == null ||
        !Number.isFinite(Number(userLocation.longitude)) ||
        !Number.isFinite(Number(userLocation.latitude))
    ) {
        return null;
    }
    return getStateExtentConfig(userLocation);
}

/**
 * Read the user's preferred default basemap (`map.default_basemap`) straight from the Vuex
 * store singleton, for call sites that aren't Vue components with `useStore()` access (e.g. the
 * OpenLayers preview dialogs' `useOpenLayersPreviewMap()` factory calls).
 */
export function getDefaultBasemapFromStore(): string | undefined {
    const getters = store.getters as Record<string, unknown>;
    const settings = getters['userSettings/userSettings'] as { map?: { default_basemap?: string } } | null;
    return settings?.map?.default_basemap;
}

/**
 * Get display name for user location
 * @param userLocation - User location data
 * @returns Formatted location string
 */
export function getLocationDisplayName(userLocation: UserLocation | null | undefined): string {
    if (!userLocation) return 'Unknown Location';

    const parts: string[] = [];
    if (userLocation.city) parts.push(userLocation.city);
    if (userLocation.state) parts.push(userLocation.state);
    if (userLocation.country) parts.push(userLocation.country);

    return parts.length > 0 ? parts.join(', ') : userLocation.country ?? 'Unknown Location';
}

