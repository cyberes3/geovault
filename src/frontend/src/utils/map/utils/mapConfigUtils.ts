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
 * Get initial map configuration based on user location
 * @param userLocation - User location data
 * @returns Map configuration with center and zoom
 */
export function getInitialMapConfig(userLocation: any): MapConfig {
    if (userLocation && userLocation.longitude && userLocation.latitude) {
        return getStateExtentConfig(userLocation);
    }

    // Default to Colorado state extent (geolocation failure fallback)
    return getStateExtentConfig({
        state: 'Colorado',
        state_code: 'CO',
        country: 'United States',
        country_code: 'US',
        latitude: 39.0, // Center of Colorado
        longitude: -105.5 // Center of Colorado
    });
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

