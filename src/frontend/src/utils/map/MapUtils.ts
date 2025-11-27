/**
 * Map Utilities
 *
 * This utility class provides common helper functions for map operations including
 * coordinate transformations, feature counting, and map configuration.
 */

import type {MapConfig} from '@/types/geospatial';
import {Circle, Fill, Icon, Stroke, Style, Text} from 'ol/style';
import {getLength} from 'ol/sphere';
import {getCenter} from 'ol/extent';
import {Point, LineString, Polygon} from 'ol/geom';
import {APIHOST} from '@/config.js';

export class MapUtils {

    /**
     * Check if an icon URL is a system (built-in) icon
     * @param iconUrl - Icon URL to check
     * @returns true if the icon is a system icon
     */
    private static isSystemIcon(iconUrl: string): boolean {
        return iconUrl.startsWith('/api/icons/system/');
    }

    /**
     * Check if an icon URL is a user (uploaded) icon
     * @param iconUrl - Icon URL to check
     * @returns true if the icon is a user icon
     */
    private static isUserIcon(iconUrl: string): boolean {
        return iconUrl.startsWith('/api/icons/user/');
    }

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
     * Get icon-only style for a feature (no text labels)
     * Used for rendering icons on a separate layer without decluttering
     * @param feature - OpenLayers feature
     * @param resolution - Map resolution (meters per pixel)
     * @returns OpenLayers Style object with only icon/image, or null to hide feature
     */
    static getFeatureIconStyle(feature: any, resolution?: number): Style | null {
        const properties = feature.get('properties') || {};
        const geometryType = feature.getGeometry().getType();

        if (geometryType === 'Point') {
            // Check if icon previously failed to load
            const iconFailed = feature.get('_iconFailed');
            if (iconFailed) {
                return this.getDefaultIconStyle(properties);
            }

            // Check for icon URL first
            const iconUrl = this.getIconUrl(properties);
            if (iconUrl) {
                const icon = this.createIconStyle(iconUrl, feature, properties);
                if (icon) {
                    return new Style({
                        image: icon
                    });
                }
            }

            // Fall back to circle style if no icon (no black border for normal points)
            return this.getDefaultIconStyle(properties);
        } else if (geometryType === 'LineString') {
            return this.createLineStringStyle(feature, properties, resolution);
        } else if (geometryType === 'Polygon' || geometryType === 'MultiPolygon') {
            return this.createPolygonStyle(feature, properties, resolution);
        } else {
            // Default style for unknown geometry types
            return this.createDefaultStyle();
        }
    }

    /**
     * Calculate distance from a point to a line segment
     * @param point - Point [x, y]
     * @param lineStart - Line segment start [x, y]
     * @param lineEnd - Line segment end [x, y]
     * @returns Distance in map units (meters)
     */
    private static distanceToLineSegment(point: number[], lineStart: number[], lineEnd: number[]): number {
        const dx = lineEnd[0] - lineStart[0];
        const dy = lineEnd[1] - lineStart[1];
        const lengthSquared = dx * dx + dy * dy;

        if (lengthSquared === 0) {
            // Line segment is a point
            const dx2 = point[0] - lineStart[0];
            const dy2 = point[1] - lineStart[1];
            return Math.sqrt(dx2 * dx2 + dy2 * dy2);
        }

        // Calculate parameter t (position along line segment)
        const t = Math.max(0, Math.min(1, 
            ((point[0] - lineStart[0]) * dx + (point[1] - lineStart[1]) * dy) / lengthSquared
        ));

        // Find closest point on line segment
        const closestX = lineStart[0] + t * dx;
        const closestY = lineStart[1] + t * dy;

        // Calculate distance
        const dx2 = point[0] - closestX;
        const dy2 = point[1] - closestY;
        return Math.sqrt(dx2 * dx2 + dy2 * dy2);
    }

    /**
     * Check if a polygon label would intersect with the polygon's border
     * @param geometry - Polygon or MultiPolygon geometry
     * @param text - Label text
     * @param resolution - Map resolution (meters per pixel)
     * @param strokeWidth - Stroke width in pixels (default: 2)
     * @returns true if label would intersect with border
     */
    private static checkLabelBorderIntersection(
        geometry: any,
        text: string,
        resolution: number,
        strokeWidth: number = 2
    ): boolean {
        if (!geometry || resolution <= 0) {
            return false;
        }

        const geometryType = geometry.getType();
        if (geometryType !== 'Polygon' && geometryType !== 'MultiPolygon') {
            return false;
        }

        // Estimate text dimensions
        // Font is 12px Arial, approximate character width is 7px, height is 12px
        const fontHeightPixels = 12;
        const avgCharWidthPixels = 7;
        const textWidthPixels = text.length * avgCharWidthPixels;
        const textHeightPixels = fontHeightPixels;
        
        // Convert to meters
        const textHeightMeters = textHeightPixels * resolution;
        const textWidthMeters = textWidthPixels * resolution;
        const strokeWidthMeters = strokeWidth * resolution;

        // Get polygon extent and centroid
        const extent = geometry.getExtent();
        const centroid = getCenter(extent);
        const widthMeters = extent[2] - extent[0];
        const heightMeters = extent[3] - extent[1];
        const minDimensionMeters = Math.min(widthMeters, heightMeters);

        // Check if polygon is too small to fit label without intersection
        // Label needs space: text height/2 on each side + stroke width
        const minRequiredDimension = textHeightMeters + (strokeWidthMeters * 2);
        
        if (minDimensionMeters < minRequiredDimension) {
            return true;
        }

        // For more accurate check, calculate distance from centroid to boundary
        // Get the exterior ring(s) of the polygon
        let exteriorRings: any[] = [];
        
        if (geometryType === 'Polygon') {
            const coordinates = geometry.getCoordinates();
            if (coordinates && coordinates.length > 0) {
                exteriorRings.push(new LineString(coordinates[0]));
            }
        } else if (geometryType === 'MultiPolygon') {
            const coordinates = geometry.getCoordinates();
            if (coordinates && Array.isArray(coordinates)) {
                for (const polygonCoords of coordinates) {
                    if (polygonCoords && polygonCoords.length > 0) {
                        exteriorRings.push(new LineString(polygonCoords[0]));
                    }
                }
            }
        }

        // Check distance from centroid to nearest point on boundary
        // Calculate distance to each line segment and take the minimum
        let minDistanceToBoundary = Infinity;
        for (const ring of exteriorRings) {
            const coords = ring.getCoordinates();
            for (let i = 0; i < coords.length - 1; i++) {
                const p1 = coords[i];
                const p2 = coords[i + 1];
                const distance = this.distanceToLineSegment(centroid, p1, p2);
                minDistanceToBoundary = Math.min(minDistanceToBoundary, distance);
            }
        }

        // If centroid is too close to boundary (less than text height/2 + stroke), label would intersect
        const requiredDistance = (textHeightMeters / 2) + strokeWidthMeters;
        return minDistanceToBoundary < requiredDistance;
    }

    /**
     * Get text-only style for a feature (no icon/image)
     * Used for rendering labels on a separate layer with decluttering
     * @param feature - OpenLayers feature
     * @param resolution - Map resolution (meters per pixel), optional
     * @returns OpenLayers Style object with only text, or null to hide text
     */
    static getFeatureTextStyle(feature: any, resolution?: number): Style | null {
        const properties = feature.get('properties') || {};
        const geometry = feature.getGeometry();
        const geometryType = geometry.getType();
        let name = properties.name || 'Unnamed Feature';

        // Truncate long feature names for map labels
        const maxNameLength = 30;
        if (name.length > maxNameLength) {
            name = name.substring(0, maxNameLength) + '...';
        }

        // For lines, check if they're too small when zoomed out
        // Note: If line is < 2 pixels, it will be rendered as a point, so we still show the label
        if ((geometryType === 'LineString' || geometryType === 'MultiLineString') && resolution !== undefined && resolution > 0) {
            // Calculate line length in meters
            const lengthMeters = getLength(geometry);
            
            // Convert to pixels
            const lengthPixels = lengthMeters / resolution;
            
            // Only hide text for lines that are small but not rendered as points
            // Lines < 2 pixels are rendered as points, so keep their labels
            // Hide text for lines between 2-50 pixels when zoomed out
            const minLineLengthPixels = 50;
            const pointThresholdPixels = 2; // Same threshold as point rendering
            const maxResolutionForSmallLines = 1500; // meters per pixel
            
            if (lengthPixels >= pointThresholdPixels && lengthPixels < minLineLengthPixels && resolution > maxResolutionForSmallLines) {
                return null; // Hide text for small lines when zoomed out (but not if rendered as point)
            }
        }

        // For polygons, check if they're too small when zoomed out
        // Note: If polygon is < 2 pixels, it will be rendered as a point, so we still show the label
        if ((geometryType === 'Polygon' || geometryType === 'MultiPolygon') && resolution !== undefined && resolution > 0) {
            const extent = geometry.getExtent();
            const widthMeters = extent[2] - extent[0];  // maxX - minX
            const heightMeters = extent[3] - extent[1]; // maxY - minY
            
            // Convert to screen pixels (meters / meters per pixel = pixels)
            const widthPixels = widthMeters / resolution;
            const heightPixels = heightMeters / resolution;
            
            // Only hide text for polygons that are small but not rendered as points
            // Polygons < 2 pixels are rendered as points, so keep their labels
            // Hide text for polygons between 2-50 pixels when zoomed out
            const minPolygonSizePixels = 50;
            const pointThresholdPixels = 2; // Same threshold as point rendering
            const maxResolutionForSmallPolygons = 1500; // meters per pixel
            
            const minDimensionPixels = Math.min(widthPixels, heightPixels);
            if (minDimensionPixels >= pointThresholdPixels && minDimensionPixels < minPolygonSizePixels && resolution > maxResolutionForSmallPolygons) {
                return null; // Hide text for small polygons when zoomed out (but not if rendered as point)
            }
        }

        // Create text style for labels (different positioning for each geometry type)
        let textStyle: Text;
        let styleGeometry: any = undefined;

        if (geometryType === 'Point') {
            // Points: text below, closer to the point
            // Use offsetY: 8 for PNG icons, offsetY: 15 for default circle icons (non-PNG)
            const iconUrl = this.getIconUrl(properties);
            const iconFailed = feature.get('_iconFailed');
            const hasWorkingIcon = iconUrl && !iconFailed;
            const offsetY = hasWorkingIcon ? 8 : 15;
            textStyle = this.createTextStyle(name, geometryType, offsetY);
        } else if (geometryType === 'Polygon' || geometryType === 'MultiPolygon') {
            // For polygons, check if label would intersect with borders
            // If so, place label below polygon (like points) instead of at centroid
            let shouldPlaceBelow = false;
            let offsetY = 0;
            let placement: string | undefined = 'point';

            if (resolution !== undefined && resolution > 0) {
                const strokeWidth = properties['stroke-width'] || 2;
                shouldPlaceBelow = this.checkLabelBorderIntersection(geometry, name, resolution, strokeWidth);
                
                if (shouldPlaceBelow) {
                    // Place label below polygon, similar to points
                    offsetY = 15; // Same offset as default circle icons for points
                    placement = null; // Remove 'point' placement to use extent-based positioning
                    
                    // Use the bottom of the polygon extent for label placement
                    const extent = geometry.getExtent();
                    const bottomCenter = [getCenter(extent)[0], extent[1]]; // [centerX, minY]
                    styleGeometry = new Point(bottomCenter);
                }
            }

            textStyle = this.createTextStyle(name, geometryType, offsetY, placement);
        } else {
            textStyle = this.createTextStyle(name, geometryType);
        }

        const styleConfig: any = {
            text: textStyle
        };
        
        // Use custom geometry for text placement when label is placed below polygon
        if (styleGeometry) {
            styleConfig.geometry = styleGeometry;
        }

        return new Style(styleConfig);
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
     * Converts relative URLs to absolute URLs using APIHOST
     * @param iconUrl - Icon URL (relative or absolute)
     * @returns Absolute icon URL
     */
    private static resolveIconUrl(iconUrl: string): string {
        // If already absolute URL, return as is
        if (iconUrl.startsWith('http://') || iconUrl.startsWith('https://')) {
            return iconUrl;
        }

        // If relative URL starting with /api/, prepend APIHOST
        // The backend stores icons with path /api/icons/{hash}.png
        // and the endpoint is /api/icons/{hash} (routed through api.urls)
        if (iconUrl.startsWith('/api/')) {
            return `${APIHOST}${iconUrl}`;
        }

        // If relative URL starting with /assets/, prepend APIHOST (for non-icon assets)
        if (iconUrl.startsWith('/assets/')) {
            return `${APIHOST}${iconUrl}`;
        }

        // If relative URL starting with assets/, prepend /assets/ (for non-icon assets)
        if (iconUrl.startsWith('assets/')) {
            return `${APIHOST}/${iconUrl}`;
        }

        // Fallback: assume it's a relative path and prepend APIHOST
        return `${APIHOST}${iconUrl.startsWith('/') ? '' : '/'}${iconUrl}`;
    }

    /**
     * Convert hex color to CSS color string
     * @param hexColor - Hex color string (e.g., '#ff0000')
     * @param defaultColor - Default color if hexColor is invalid
     * @returns CSS color string
     */
    private static hexToColor(hexColor: string | undefined, defaultColor: string = '#ff0000'): string {
        if (!hexColor || typeof hexColor !== 'string') return defaultColor;
        return hexColor;
    }

    /**
     * Create text style for feature labels
     * @param name - Feature name text
     * @param geometryType - Geometry type (Point, Polygon, LineString, etc.)
     * @param offsetY - Vertical offset for text (default based on geometry type)
     * @param placement - Text placement option ('point' for polygon centroid)
     * @returns OpenLayers Text style
     */
    private static createTextStyle(
        name: string,
        geometryType: string,
        offsetY?: number,
        placement?: string | null
    ): Text {
        // Default offsets based on geometry type
        let defaultOffsetY: number;
        let defaultPlacement: string | undefined;

        if (geometryType === 'Point') {
            defaultOffsetY = offsetY !== undefined ? offsetY : 8;
        } else if (geometryType === 'Polygon') {
            defaultOffsetY = offsetY !== undefined ? offsetY : 0;
            // If placement is null, don't set it (allows custom geometry placement)
            // If placement is undefined (not provided), default to 'point' for centroid placement
            // Otherwise, use the provided placement value
            if (placement === null) {
                defaultPlacement = undefined;
            } else {
                defaultPlacement = placement !== undefined ? placement : 'point';
            }
        } else {
            defaultOffsetY = offsetY !== undefined ? offsetY : 10;
        }

        const textStyleConfig: any = {
            text: name,
            font: '12px Arial',
            fill: new Fill({
                color: '#000000'
            }),
            stroke: new Stroke({
                color: '#ffffff',
                width: 3
            }),
            offsetY: defaultOffsetY
        };

        if (defaultPlacement) {
            textStyleConfig.placement = defaultPlacement;
        }

        return new Text(textStyleConfig);
    }

    /**
     * Apply fill-opacity to a hex color, converting it to RGBA
     * @param hexColor - Hex color string (e.g., '#ff0000')
     * @param opacity - Opacity value (0-1)
     * @returns RGBA color string
     */
    private static applyFillOpacity(hexColor: string, opacity: number): string {
        const hex = hexColor.replace('#', '');
        const r = parseInt(hex.slice(0, 2), 16);
        const g = parseInt(hex.slice(2, 4), 16);
        const b = parseInt(hex.slice(4, 6), 16);
        return `rgba(${r}, ${g}, ${b}, ${opacity})`;
    }

    /**
     * Create icon style with error handling
     * Preloads image to detect loading failures and marks feature accordingly
     * Ensures icon has a minimum size by calculating appropriate scale
     * Supports server-side recoloring for built-in icons if marker-color is specified
     * @param iconUrl - Icon URL (relative or absolute)
     * @param feature - OpenLayers feature
     * @param properties - Feature properties (for marker-color)
     * @param minSize - Minimum size in pixels (default: 20)
     * @returns Icon style or null if icon failed to load
     */
    private static createIconStyle(iconUrl: string, feature: any, properties: any, minSize: number = 20): Icon | null {
        const isBuiltInIcon = this.isSystemIcon(iconUrl);
        const markerColor = properties['marker-color'];

        // Check if feature already has a calculated scale from previous load
        let calculatedScale = feature.get('_iconScale');

        // Determine icon source URL
        let iconSrc: string;
        
        if (isBuiltInIcon && markerColor) {
            // NOTE: Can't recolor in JS, it's fucked. Must use server-side PIL processing.
            // Even with binary PNG decoding (bypassing Canvas rendering), semi-transparent
            // edge pixels with low alpha values appear as black spots. CalTopo also uses
            // backend recoloring: https://caltopo.com/icon.png?cfg=campfire%2CFF0000%231
            
            // Extract icon path relative to assets/icons/ for recolor endpoint
            // Extract path after /api/icons/system/ (e.g., 'caltopo/4wd.png')
            const iconPathForRecolor = iconUrl.replace('/api/icons/system/', '');
            
            // Construct server-side recoloring URL
            const encodedColor = encodeURIComponent(markerColor);
            const encodedIcon = encodeURIComponent(iconPathForRecolor);
            iconSrc = `${APIHOST}/api/icons/recolor/?icon=${encodedIcon}&color=${encodedColor}`;
        } else {
            // Use original icon URL
            iconSrc = this.resolveIconUrl(iconUrl);
        }

        // Preload image to detect loading failures and get dimensions
        const img = new Image();
        
        img.onload = () => {
            const naturalSize = Math.max(img.naturalWidth, img.naturalHeight);
            if (naturalSize > 0) {
                // Calculate scale needed to reach minimum size
                const scale = Math.max(minSize / naturalSize, 0.4);
                feature.set('_iconScale', scale);
                // Trigger style update to apply new scale
                feature.changed();
            }
        };
        
        img.onerror = () => {
            // Mark feature as having failed icon load
            feature.set('_iconFailed', true);
            // Trigger style update by changing a property
            feature.changed();
        };
        
        img.src = iconSrc;

        // If image is already loaded (cached), calculate scale immediately
        if (img.complete && img.naturalWidth > 0) {
            const naturalSize = Math.max(img.naturalWidth, img.naturalHeight);
            if (naturalSize > 0) {
                calculatedScale = Math.max(minSize / naturalSize, 0.4);
                feature.set('_iconScale', calculatedScale);
            }
        }

        // Use stored scale if available, otherwise use default
        const finalScale = calculatedScale !== undefined ? calculatedScale : 0.4;

        return new Icon({
            src: iconSrc,
            scale: finalScale,
            anchor: [0.5, 1.0], // Anchor at bottom center of icon
        });
    }

    /**
     * Create a point style at the center of a geometry's extent
     * @param geometry - OpenLayers geometry
     * @param color - Color for the point (fill and stroke)
     * @param radius - Point radius in pixels (default: 3)
     * @returns OpenLayers Style object with a circle at the extent center
     */
    private static createPointStyleAtExtentCenter(geometry: any, color: string, radius: number = 3): Style {
        const extent = geometry.getExtent();
        const center = getCenter(extent);
        const centerPoint = new Point(center);
        
        return new Style({
            geometry: centerPoint,
            image: new Circle({
                radius: radius,
                fill: new Fill({
                    color: color
                }),
                stroke: new Stroke({
                    color: color,
                    width: 1
                })
            })
        });
    }

    /**
     * Create LineString style
     * @param feature - OpenLayers feature
     * @param properties - Feature properties
     * @param resolution - Map resolution (meters per pixel), optional
     * @param textStyle - Optional text style for labels
     * @returns OpenLayers Style object
     */
    private static createLineStringStyle(feature: any, properties: any, resolution?: number, textStyle?: Text): Style {
        // Check minimum size threshold to prevent flickering at low zoom levels
        // If resolution is provided and line is smaller than 2 pixels, render as point
        if (resolution !== undefined && resolution > 0) {
            const geometry = feature.getGeometry();
            if (geometry) {
                // Calculate line length in meters
                const lengthMeters = getLength(geometry);
                
                // Convert to pixels
                const lengthPixels = lengthMeters / resolution;
                
                // Render as point if line is less than 2 pixels
                const minPixelSize = 2;
                if (lengthPixels < minPixelSize) {
                    const strokeColor = this.hexToColor(properties.stroke, '#ff0000');
                    return this.createPointStyleAtExtentCenter(geometry, strokeColor);
                }
            }
        }

        const strokeColor = this.hexToColor(properties.stroke, '#ff0000');
        const styleConfig: any = {
            stroke: new Stroke({
                color: strokeColor,
                width: properties['stroke-width'] || 2
            })
        };
        if (textStyle) {
            styleConfig.text = textStyle;
        }
        return new Style(styleConfig);
    }

    /**
     * Create Polygon style
     * @param feature - OpenLayers feature
     * @param properties - Feature properties
     * @param resolution - Map resolution (meters per pixel), optional
     * @param textStyle - Optional text style for labels
     * @returns OpenLayers Style object, or point style if too small
     */
    private static createPolygonStyle(feature: any, properties: any, resolution?: number, textStyle?: Text): Style {
        // Check minimum size threshold to prevent flickering at low zoom levels
        // If resolution is provided and polygon is smaller than 2 pixels, render as point
        if (resolution !== undefined && resolution > 0) {
            const geometry = feature.getGeometry();
            if (geometry) {
                const extent = geometry.getExtent();
                const widthMeters = extent[2] - extent[0];  // maxX - minX
                const heightMeters = extent[3] - extent[1]; // maxY - minY
                
                // Convert to screen pixels (meters / meters per pixel = pixels)
                const widthPixels = widthMeters / resolution;
                const heightPixels = heightMeters / resolution;
                
                // Render as point if either dimension is less than 2 pixels
                // This prevents flickering when polygons fold into themselves at low zoom
                const minPixelSize = 2;
                if (widthPixels < minPixelSize || heightPixels < minPixelSize) {
                    const strokeColor = this.hexToColor(properties.stroke, '#ff0000');
                    return this.createPointStyleAtExtentCenter(geometry, strokeColor);
                }
            }
        }

        const strokeColor = this.hexToColor(properties.stroke, '#ff0000');
        let fillColor = this.hexToColor(properties.fill, '#ff0000');

        // Apply fill-opacity if specified
        if (properties['fill-opacity'] !== undefined) {
            fillColor = this.applyFillOpacity(fillColor, properties['fill-opacity']);
        }

        const styleConfig: any = {
            stroke: new Stroke({
                color: strokeColor,
                width: properties['stroke-width'] || 2
            }),
            fill: new Fill({
                color: fillColor
            })
        };
        if (textStyle) {
            styleConfig.text = textStyle;
        }
        return new Style(styleConfig);
    }

    /**
     * Create default style for unknown geometry types
     * @param textStyle - Optional text style for labels
     * @returns OpenLayers Style object
     */
    private static createDefaultStyle(textStyle?: Text): Style {
        const styleConfig: any = {
            stroke: new Stroke({
                color: '#ff0000',
                width: 2
            }),
            fill: new Fill({
                color: 'rgba(255, 0, 0, 0.3)'
            })
        };
        if (textStyle) {
            styleConfig.text = textStyle;
        }
        return new Style(styleConfig);
    }

    /**
     * Get default fallback icon style (red circle)
     * Used when custom icon fails to load or no icon is specified
     * @param properties - Feature properties
     * @param iconFailed - Whether the icon failed to load (unused, kept for API compatibility)
     * @returns OpenLayers Style with default circle icon
     */
    private static getDefaultIconStyle(properties: any): Style {
        const fillColor = this.hexToColor(properties['marker-color'], '#ff0000');
        return new Style({
            image: new Circle({
                radius: 3,
                fill: new Fill({
                    color: fillColor
                }),
                stroke: new Stroke({
                    color: fillColor, // Same color as fill for all cases
                    width: 2
                })
            })
        });
    }
}
