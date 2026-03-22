/**
 * Map Configuration Utilities
 *
 * Functions for map initialization, zoom level calculation, and location display.
 */

import type {MapConfig} from '@/types/geospatial';

/**
 * Calculate appropriate zoom level based on location type
 * @param location - Location data
 * @returns Zoom level
 */
function calculateZoomLevel(location: any): number {
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
function getStateExtentConfig(location: any): MapConfig {
    const zoomLevel = calculateZoomLevel(location);

    return {
        center: [location.longitude, location.latitude],
        zoom: zoomLevel
    };
}

/**
 * First paint: origin (0,0) at low zoom. MapPage then recenters from geolocation on the main map,
 * or mapshare loads data and fits features to the viewport.
 */
export function getInitialMapConfig(_userLocation?: unknown): MapConfig {
    return {
        center: [0, 0],
        zoom: 2
    };
}

/**
 * After IP / geolocation resolves, center and zoom for the authenticated main map (not used on mapshare).
 */
export function getMapRecenterFromUserLocation(userLocation: any): MapConfig | null {
    if (
        userLocation == null ||
        userLocation.longitude == null ||
        userLocation.latitude == null ||
        !Number.isFinite(Number(userLocation.longitude)) ||
        !Number.isFinite(Number(userLocation.latitude))
    ) {
        return null;
    }
    return getStateExtentConfig(userLocation);
}

/**
 * Get display name for user location
 * @param userLocation - User location data
 * @returns Formatted location string
 */
export function getLocationDisplayName(userLocation: any): string {
    if (!userLocation) return 'Unknown Location';

    const parts = [];
    if (userLocation.city) parts.push(userLocation.city);
    if (userLocation.state) parts.push(userLocation.state);
    if (userLocation.country) parts.push(userLocation.country);

    return parts.length > 0 ? parts.join(', ') : userLocation.country || 'Unknown Location';
}

