/**
 * Map Utilities
 *
 * This utility class provides common helper functions for map operations including
 * coordinate transformations, feature counting, and map configuration.
 */

import type {MapConfig} from '@/types/geospatial';
import {Style, Fill, Stroke, Circle, Icon, Text} from 'ol/style';
import {APIHOST} from '@/config.js';

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

        // For very low zoom levels (world view), use a more precise grid system
        // to avoid overly large grid cells that prevent proper data loading
        let gridSize;
        if (roundedZoom <= 3) {
            // For world view (zoom 0-3), use a fixed smaller grid size
            gridSize = 1000000; // 1 million meters (roughly 1 degree at equator)
        } else if (roundedZoom <= 6) {
            // For continental view (zoom 4-6), use moderate grid size
            gridSize = Math.pow(2, 18 - roundedZoom);
        } else {
            // For local view (zoom 7+), use the original calculation
            gridSize = Math.pow(2, 15 - roundedZoom);
        }

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
    static addFeatureTimestamp(_feature: any, featureTimestamps: Record<string, number>, featureId: string): void {
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

    /**
     * Get icon URL from feature properties
     * Checks multiple common property names for icon URLs
     * @param properties - Feature properties object
     * @returns Icon URL if found, null otherwise
     */
    private static getIconUrl(properties: any): string | null {
        // Common property names that might contain icon hrefs
        const iconPropertyNames = [
            'icon',
            'icon-href',
            'iconUrl',
            'icon_url',
            'marker-icon',
            'marker-symbol',
            'symbol',
        ];

        for (const propName of iconPropertyNames) {
            if (properties[propName] && typeof properties[propName] === 'string') {
                const iconUrl = properties[propName].trim();
                if (iconUrl) {
                    return iconUrl;
                }
            }
        }

        return null;
    }

    /**
     * Resolve icon URL to absolute URL
     * Converts relative URLs (starting with /api/) to absolute URLs using APIHOST
     * @param iconUrl - Icon URL (relative or absolute)
     * @returns Absolute icon URL
     */
    private static resolveIconUrl(iconUrl: string): string {
        // If already absolute URL, return as is
        if (iconUrl.startsWith('http://') || iconUrl.startsWith('https://')) {
            return iconUrl;
        }

        // If relative URL starting with /api/, prepend APIHOST
        // The backend stores icons with path /api/data/icons/{hash}.png
        // and the endpoint is /api/data/icons/{hash} (routed through api.urls)
        if (iconUrl.startsWith('/api/')) {
            return `${APIHOST}${iconUrl}`;
        }

        // Fallback: assume it's a relative path and prepend APIHOST
        return `${APIHOST}${iconUrl.startsWith('/') ? '' : '/'}${iconUrl}`;
    }

    /**
     * Get style for a feature based on its geometry type and properties
     * This is a pure function extracted from Vue component to avoid reactivity overhead
     * @param feature - OpenLayers feature
     * @returns OpenLayers Style object
     */
    static getFeatureStyle(feature: any): Style {
        const properties = feature.get('properties') || {};
        const geometryType = feature.getGeometry().getType();
        const name = properties.name || 'Unnamed Feature';

        // Helper function to convert hex color to CSS color string
        const hexToColor = (hexColor: string | undefined, defaultColor: string = '#ff0000'): string => {
            if (!hexColor || typeof hexColor !== 'string') return defaultColor;
            return hexColor;
        };

        // Create text style for labels (different positioning for each geometry type)
        let textStyle: Text;

        if (geometryType === 'Point') {
            // Points: text below, closer to the point
            textStyle = new Text({
                text: name,
                font: '12px Arial',
                fill: new Fill({
                    color: '#000000'
                }),
                stroke: new Stroke({
                    color: '#ffffff',
                    width: 3
                }),
                offsetY: 8
            });
        } else if (geometryType === 'Polygon') {
            // Polygons: text centered in the polygon
            textStyle = new Text({
                text: name,
                font: '12px Arial',
                fill: new Fill({
                    color: '#000000'
                }),
                stroke: new Stroke({
                    color: '#ffffff',
                    width: 3
                }),
                placement: 'point', // Centers text on polygon centroid
                offsetY: 0
            });
        } else {
            // Lines and other types: text below
            textStyle = new Text({
                text: name,
                font: '12px Arial',
                fill: new Fill({
                    color: '#000000'
                }),
                stroke: new Stroke({
                    color: '#ffffff',
                    width: 3
                }),
                offsetY: 10
            });
        }

        // Create style based on geometry type
        let style: Style;

        if (geometryType === 'Point') {
            // Check for icon URL first
            const iconUrl = this.getIconUrl(properties);
            if (iconUrl) {
                const resolvedIconUrl = this.resolveIconUrl(iconUrl);
                style = new Style({
                    image: new Icon({
                        src: resolvedIconUrl,
                        scale: 0.4,
                        anchor: [0.5, 1.0], // Anchor at bottom center of icon
                    }),
                    text: textStyle
                });
            } else {
                // Fall back to circle style if no icon
                // Note: Circle styles are vector graphics, not PNG files
                const fillColor = hexToColor(properties['marker-color'], '#ff0000');
                // Create separate text style for default circle icons with more offset
                const circleTextStyle = new Text({
                    text: name,
                    font: '12px Arial',
                    fill: new Fill({
                        color: '#000000'
                    }),
                    stroke: new Stroke({
                        color: '#ffffff',
                        width: 3
                    }),
                    offsetY: 15 // Move text further down for default circle icons
                });
                style = new Style({
                    image: new Circle({
                        radius: 6,
                        fill: new Fill({
                            color: fillColor
                        }),
                        stroke: new Stroke({
                            color: fillColor, // Use same color for stroke
                            width: 2
                        })
                    }),
                    text: circleTextStyle
                });
            }
        } else if (geometryType === 'LineString') {
            const strokeColor = hexToColor(properties.stroke, '#ff0000');
            style = new Style({
                stroke: new Stroke({
                    color: strokeColor,
                    width: properties['stroke-width'] || 2
                }),
                text: textStyle
            });
        } else if (geometryType === 'Polygon') {
            const strokeColor = hexToColor(properties.stroke, '#ff0000');
            let fillColor = hexToColor(properties.fill, '#ff0000');

            // Apply fill-opacity if specified
            if (properties['fill-opacity'] !== undefined) {
                // Convert hex to RGB and apply opacity
                const hex = fillColor.replace('#', '');
                const r = parseInt(hex.substr(0, 2), 16);
                const g = parseInt(hex.substr(2, 2), 16);
                const b = parseInt(hex.substr(4, 2), 16);
                fillColor = `rgba(${r}, ${g}, ${b}, ${properties['fill-opacity']})`;
            }

            style = new Style({
                stroke: new Stroke({
                    color: strokeColor,
                    width: properties['stroke-width'] || 2
                }),
                fill: new Fill({
                    color: fillColor
                }),
                text: textStyle
            });
        } else {
            // Default style for unknown geometry types
            style = new Style({
                stroke: new Stroke({
                    color: '#ff0000',
                    width: 2
                }),
                fill: new Fill({
                    color: 'rgba(255, 0, 0, 0.3)'
                }),
                text: textStyle
            });
        }

        return style;
    }

    /**
     * Get icon-only style for a feature (no text labels)
     * Used for rendering icons on a separate layer without decluttering
     * @param feature - OpenLayers feature
     * @returns OpenLayers Style object with only icon/image
     */
    static getFeatureIconStyle(feature: any): Style {
        const properties = feature.get('properties') || {};
        const geometryType = feature.getGeometry().getType();

        // Helper function to convert hex color to CSS color string
        const hexToColor = (hexColor: string | undefined, defaultColor: string = '#ff0000'): string => {
            if (!hexColor || typeof hexColor !== 'string') return defaultColor;
            return hexColor;
        };

        if (geometryType === 'Point') {
            // Check for icon URL first
            const iconUrl = this.getIconUrl(properties);
            if (iconUrl) {
                const resolvedIconUrl = this.resolveIconUrl(iconUrl);
                return new Style({
                    image: new Icon({
                        src: resolvedIconUrl,
                        scale: 0.4,
                        anchor: [0.5, 1.0], // Anchor at bottom center of icon
                    })
                });
            } else {
                // Fall back to circle style if no icon
                const fillColor = hexToColor(properties['marker-color'], '#ff0000');
                return new Style({
                    image: new Circle({
                        radius: 6,
                        fill: new Fill({
                            color: fillColor
                        }),
                        stroke: new Stroke({
                            color: fillColor, // Use same color for stroke
                            width: 2
                        })
                    })
                });
            }
        } else if (geometryType === 'LineString') {
            const strokeColor = hexToColor(properties.stroke, '#ff0000');
            return new Style({
                stroke: new Stroke({
                    color: strokeColor,
                    width: properties['stroke-width'] || 2
                })
            });
        } else if (geometryType === 'Polygon') {
            const strokeColor = hexToColor(properties.stroke, '#ff0000');
            let fillColor = hexToColor(properties.fill, '#ff0000');

            // Apply fill-opacity if specified
            if (properties['fill-opacity'] !== undefined) {
                // Convert hex to RGB and apply opacity
                const hex = fillColor.replace('#', '');
                const r = parseInt(hex.substr(0, 2), 16);
                const g = parseInt(hex.substr(2, 2), 16);
                const b = parseInt(hex.substr(4, 2), 16);
                fillColor = `rgba(${r}, ${g}, ${b}, ${properties['fill-opacity']})`;
            }

            return new Style({
                stroke: new Stroke({
                    color: strokeColor,
                    width: properties['stroke-width'] || 2
                }),
                fill: new Fill({
                    color: fillColor
                })
            });
        } else {
            // Default style for unknown geometry types
            return new Style({
                stroke: new Stroke({
                    color: '#ff0000',
                    width: 2
                }),
                fill: new Fill({
                    color: 'rgba(255, 0, 0, 0.3)'
                })
            });
        }
    }

    /**
     * Get text-only style for a feature (no icon/image)
     * Used for rendering labels on a separate layer with decluttering
     * @param feature - OpenLayers feature
     * @returns OpenLayers Style object with only text
     */
    static getFeatureTextStyle(feature: any): Style {
        const properties = feature.get('properties') || {};
        const geometryType = feature.getGeometry().getType();
        const name = properties.name || 'Unnamed Feature';

        // Create text style for labels (different positioning for each geometry type)
        let textStyle: Text;

        if (geometryType === 'Point') {
            // Points: text below, closer to the point
            const iconUrl = this.getIconUrl(properties);
            textStyle = new Text({
                text: name,
                font: '12px Arial',
                fill: new Fill({
                    color: '#000000'
                }),
                stroke: new Stroke({
                    color: '#ffffff',
                    width: 3
                }),
                offsetY: iconUrl ? 8 : 15 // More offset for default circle icons
            });
        } else if (geometryType === 'Polygon') {
            // Polygons: text centered in the polygon
            textStyle = new Text({
                text: name,
                font: '12px Arial',
                fill: new Fill({
                    color: '#000000'
                }),
                stroke: new Stroke({
                    color: '#ffffff',
                    width: 3
                }),
                placement: 'point', // Centers text on polygon centroid
                offsetY: 0
            });
        } else {
            // Lines and other types: text below
            textStyle = new Text({
                text: name,
                font: '12px Arial',
                fill: new Fill({
                    color: '#000000'
                }),
                stroke: new Stroke({
                    color: '#ffffff',
                    width: 3
                }),
                offsetY: 10
            });
        }

        return new Style({
            text: textStyle
        });
    }
}
