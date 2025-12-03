/**
 * Style Utilities
 *
 * Functions for creating OpenLayers styles for different geometry types.
 */

import {Circle, Fill, Stroke, Style} from 'ol/style';
import {getLength} from 'ol/sphere';
import {getCenter} from 'ol/extent';
import {Point} from 'ol/geom';
import {getIconUrl, createIconStyle, getDefaultIconStyle, isSystemIcon, detectPrimaryColor, getZoomFromResolution, resolveIconUrl, preloadIconImage} from './iconUtils';

/**
 * Convert hex color to CSS color string
 * @param hexColor - Hex color string (e.g., '#ff0000')
 * @param defaultColor - Default color if hexColor is invalid
 * @returns CSS color string
 */
export function hexToColor(hexColor: string | undefined, defaultColor: string = '#ff0000'): string {
    if (!hexColor || typeof hexColor !== 'string') return defaultColor;
    return hexColor;
}

/**
 * Apply fill-opacity to a hex color, converting it to RGBA
 * @param hexColor - Hex color string (e.g., '#ff0000')
 * @param opacity - Opacity value (0-1)
 * @returns RGBA color string
 */
export function applyFillOpacity(hexColor: string, opacity: number): string {
    const hex = hexColor.replace('#', '');
    const r = parseInt(hex.slice(0, 2), 16);
    const g = parseInt(hex.slice(2, 4), 16);
    const b = parseInt(hex.slice(4, 6), 16);
    return `rgba(${r}, ${g}, ${b}, ${opacity})`;
}

/**
 * Calculate exponential scale multiplier based on zoom level
 * Icons are at normal size at zoom 10 and above (city level), with exponential scaling only when zoomed out
 * Icons get smaller when zoomed out below baseZoom, but stay at normal size when zoomed in
 * @param zoom - Current zoom level
 * @param baseZoom - Base zoom level where icons are normal size (default: 10, city level)
 * @param exponentFactor - Exponential scaling factor (default: 0.6 for aggressive scaling)
 * @returns Scale multiplier (1.0 at baseZoom and above, < 1.0 when zoomed out)
 */
function getZoomScaleMultiplier(zoom: number, baseZoom: number = 10, exponentFactor: number = 0.6): number {
    // At city level (zoom 10) and above, icons stay at default size
    if (zoom >= baseZoom) {
        return 1.0;
    }
    // When zoomed out below city level, apply exponential scaling
    return Math.pow(2, (zoom - baseZoom) * exponentFactor);
}

/**
 * Create a point style at the center of a geometry's extent
 * Scales with zoom level to match actual point icons
 * @param geometry - OpenLayers geometry
 * @param color - Color for the point (fill and stroke)
 * @param radius - Base point radius in pixels (default: 3)
 * @param resolution - Map resolution in meters per pixel (optional, for zoom-based scaling)
 * @returns OpenLayers Style object with a circle at the extent center
 */
export function createPointStyleAtExtentCenter(geometry: any, color: string, radius: number = 3, resolution?: number): Style {
    const extent = geometry.getExtent();
    const center = getCenter(extent);
    const centerPoint = new Point(center);
    
    // Apply exponential zoom-based scaling if resolution is provided
    let finalRadius = radius;
    if (resolution !== undefined && resolution > 0) {
        const zoom = getZoomFromResolution(resolution);
        const zoomMultiplier = getZoomScaleMultiplier(zoom);
        finalRadius = radius * zoomMultiplier;
    }
    
    return new Style({
        geometry: centerPoint,
        image: new Circle({
            radius: finalRadius,
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
export function createLineStringStyle(feature: any, properties: any, resolution?: number, textStyle?: any): Style {
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
                const strokeColor = hexToColor(properties.stroke, '#ff0000');
                return createPointStyleAtExtentCenter(geometry, strokeColor, 3, resolution);
            }
        }
    }

    const strokeColor = hexToColor(properties.stroke, '#ff0000');
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
export function createPolygonStyle(feature: any, properties: any, resolution?: number, textStyle?: any): Style {
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
                const strokeColor = hexToColor(properties.stroke, '#ff0000');
                return createPointStyleAtExtentCenter(geometry, strokeColor, 3, resolution);
            }
        }
    }

    const strokeColor = hexToColor(properties.stroke, '#ff0000');
    let fillColor = hexToColor(properties.fill, '#ff0000');

    // Apply fill-opacity if specified
    if (properties['fill-opacity'] !== undefined) {
        fillColor = applyFillOpacity(fillColor, properties['fill-opacity']);
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
export function createDefaultStyle(textStyle?: any): Style {
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
 * Get icon-only style for a feature (no text labels)
 * Used for rendering icons on a separate layer without decluttering
 * At zoom level 8 or below, replaces image-based icons with colored default points
 * @param feature - OpenLayers feature
 * @param resolution - Map resolution (meters per pixel)
 * @param replaceIconsLowZoom - Whether to replace icons with default points at low zoom (default: true)
 * @returns OpenLayers Style object with only icon/image, or null to hide feature
 */
export function getFeatureIconStyle(feature: any, resolution?: number, replaceIconsLowZoom: boolean = true): Style | null {
    const properties = feature.get('properties') || {};
    const geometryType = feature.getGeometry().getType();

    if (geometryType === 'Point') {
        // Check if icon previously failed to load
        const iconFailed = feature.get('_iconFailed');
        if (iconFailed) {
            return getDefaultIconStyle(properties, resolution);
        }

        // Check for icon URL first
        const iconUrl = getIconUrl(properties);
        if (iconUrl) {
            // Check if icon URL changed and clear detected color if so
            const storedIconUrl = feature.get('_iconUrlForColorDetection');
            if (storedIconUrl !== iconUrl) {
                feature.set('_iconUrlForColorDetection', iconUrl);
                feature.set('_detectedIconColor', null);
                feature.set('_colorDetectionInProgress', false);
            }
            
            // At zoom level 8 or below, replace image-based icons (not system icons) with colored default points
            // Only if the setting is enabled
            const isLowZoom = resolution !== undefined && resolution > 0 && getZoomFromResolution(resolution) <= 8;
            
            if (isLowZoom && replaceIconsLowZoom && !isSystemIcon(iconUrl)) {
                // Preload the original icon image so it's ready when user zooms in
                preloadIconImage(iconUrl, feature, properties);
                
                // Check if we already have a detected color stored
                const detectedColor = feature.get('_detectedIconColor');
                if (detectedColor) {
                    return getDefaultIconStyle(properties, resolution, detectedColor);
                }
                
                // Start color detection if not already in progress
                if (!feature.get('_colorDetectionInProgress')) {
                    feature.set('_colorDetectionInProgress', true);
                    const resolvedUrl = resolveIconUrl(iconUrl);
                    const fallbackColor = properties['marker-color'] || '#ff0000';
                    
                    detectPrimaryColor(resolvedUrl)
                        .then(color => {
                            feature.set('_detectedIconColor', color);
                            feature.set('_colorDetectionInProgress', false);
                            feature.changed();
                        })
                        .catch(() => {
                            feature.set('_detectedIconColor', fallbackColor);
                            feature.set('_colorDetectionInProgress', false);
                            feature.changed();
                        });
                }
                
                // Don't render until color detection is complete
                return null;
            }
            
            // At higher zoom or for system icons, use the image icon
            const icon = createIconStyle(iconUrl, feature, properties, 20, resolution);
            if (icon) {
                return new Style({
                    image: icon
                });
            }
        }

        // Fall back to circle style if no icon (no black border for normal points)
        return getDefaultIconStyle(properties, resolution);
    } else if (geometryType === 'LineString') {
        return createLineStringStyle(feature, properties, resolution);
    } else if (geometryType === 'Polygon' || geometryType === 'MultiPolygon') {
        return createPolygonStyle(feature, properties, resolution);
    } else {
        // Default style for unknown geometry types
        return createDefaultStyle();
    }
}

