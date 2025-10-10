/**
 * Map Utilities
 *
 * This utility class provides common helper functions for map operations including
 * coordinate transformations, feature counting, and map configuration.
 */

import type {MapConfig} from '@/types/geospatial';

export class MapUtils {
    /**
     * Count only polygon and line features (exclude points)
     * This is used for the tier system which only considers complex geometries
     * @param features - Array of OpenLayers features
     * @returns Number of polygon and line features
     */
    static countPolyLineFeatures(features: any[]): number {
        return features.filter(feature => {
            const geometryType = feature.getGeometry().getType();
            return ['LineString', 'Polygon', 'MultiLineString', 'MultiPolygon'].indexOf(geometryType) !== -1;
        }).length;
    }

    /**
     * Generate a unique ID for a feature
     * @param feature - OpenLayers feature
     * @param counter - Counter for generating unique IDs
     * @returns Unique feature ID
     */
    static getFeatureId(feature: any, counter: { value: number }): string {
        // Try to get existing ID or create a new one
        if (!feature._geoJsonMapId) {
            feature._geoJsonMapId = `feature_${++counter.value}_${Date.now()}`;
        }
        return feature._geoJsonMapId;
    }

    /**
     * Get initial map configuration based on user location
     * @param userLocation - User location data
     * @returns Map configuration with center and zoom
     */
    static getInitialMapConfig(userLocation: any): MapConfig {
        if (userLocation && userLocation.longitude && userLocation.latitude) {
            return this.getStateExtentConfig(userLocation);
        }

        // Default to Colorado state extent (geolocation failure fallback)
        return this.getStateExtentConfig({
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
    static getLocationDisplayName(userLocation: any): string {
        if (!userLocation) return 'Unknown Location';

        const parts = [];
        if (userLocation.city) parts.push(userLocation.city);
        if (userLocation.state) parts.push(userLocation.state);
        if (userLocation.country) parts.push(userLocation.country);

        return parts.length > 0 ? parts.join(', ') : userLocation.country || 'Unknown Location';
    }

    /**
     * Create a grid-based key for bounding box tracking
     * @param extent - Map extent [minX, minY, maxX, maxY]
     * @param zoom - Current zoom level
     * @returns Grid-based key string
     */
    static getBoundingBoxKey(extent: number[], zoom: number): string {
        const [minX, minY, maxX, maxY] = extent;
        const roundedZoom = Math.round(zoom);

        // Create a grid system - divide the world into grid cells
        // Use a larger grid size to reduce precision issues and overlap
        const gridSize = Math.pow(2, 15 - roundedZoom); // Adjust grid size based on zoom
        const gridMinX = Math.floor(minX / gridSize) * gridSize;
        const gridMinY = Math.floor(minY / gridSize) * gridSize;
        const gridMaxX = Math.ceil(maxX / gridSize) * gridSize;
        const gridMaxY = Math.ceil(maxY / gridSize) * gridSize;

        return `${gridMinX},${gridMinY},${gridMaxX},${gridMaxY}_${roundedZoom}`;
    }

    /**
     * Convert Web Mercator extent to geographic coordinates
     * @param extent - Map extent in Web Mercator
     * @param toLonLat - OpenLayers toLonLat function
     * @returns Bounding box string in geographic coordinates
     */
    static getBoundingBoxString(extent: number[], toLonLat: (coords: number[]) => number[]): string {
        const [minX, minY, maxX, maxY] = extent;

        // Use OpenLayers' built-in coordinate transformation
        const minLonLat = toLonLat([minX, minY]);
        const maxLonLat = toLonLat([maxX, maxY]);

        return `${minLonLat[0]},${minLonLat[1]},${maxLonLat[0]},${maxLonLat[1]}`;
    }

    /**
     * Add timestamp to feature for tracking
     * @param feature - OpenLayers feature
     * @param featureTimestamps - Timestamp storage object
     * @param featureId - Feature ID
     */
    static addFeatureTimestamp(feature: any, featureTimestamps: Record<string, number>, featureId: string): void {
        featureTimestamps[featureId] = Date.now();
    }

    /**
     * Remove feature timestamp
     * @param featureId - Feature ID
     * @param featureTimestamps - Timestamp storage object
     */
    static removeFeatureTimestamp(featureId: string, featureTimestamps: Record<string, number>): void {
        delete featureTimestamps[featureId];
    }

    /**
     * Sort features by timestamp (oldest first)
     * @param features - Array of OpenLayers features
     * @param featureTimestamps - Timestamp storage object
     * @param getFeatureId - Function to get feature ID
     * @returns Sorted features with timestamps
     */
    static sortFeaturesByTimestamp(
        features: any[],
        featureTimestamps: Record<string, number>,
        getFeatureId: (feature: any) => string
    ): Array<{ feature: any; featureId: string; timestamp: number }> {
        return features.map(feature => {
            const featureId = getFeatureId(feature);
            return {
                feature,
                featureId,
                timestamp: featureTimestamps[featureId] || 0
            };
        }).sort((a, b) => a.timestamp - b.timestamp);
    }

    /**
     * Get state extent configuration
     * @param location - Location data
     * @returns Map configuration
     */
    private static getStateExtentConfig(location: any): MapConfig {
        const zoomLevel = this.calculateZoomLevel(location);

        return {
            center: [location.longitude, location.latitude],
            zoom: zoomLevel
        };
    }

    /**
     * Calculate appropriate zoom level based on location type
     * @param location - Location data
     * @returns Zoom level
     */
    private static calculateZoomLevel(location: any): number {
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
}
