/**
 * Style Utilities
 *
 * Functions for creating OpenLayers styles for different geometry types.
 */

import {Circle, Fill, Stroke, Style} from 'ol/style';
import {getLength} from 'ol/sphere';
import {getCenter} from 'ol/extent';
import {Point} from 'ol/geom';
import {getIconUrl, createIconStyle, getDefaultIconStyle} from './iconUtils';

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
 * Create a point style at the center of a geometry's extent
 * @param geometry - OpenLayers geometry
 * @param color - Color for the point (fill and stroke)
 * @param radius - Point radius in pixels (default: 3)
 * @returns OpenLayers Style object with a circle at the extent center
 */
export function createPointStyleAtExtentCenter(geometry: any, color: string, radius: number = 3): Style {
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
                return createPointStyleAtExtentCenter(geometry, strokeColor);
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
                return createPointStyleAtExtentCenter(geometry, strokeColor);
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
 * @param feature - OpenLayers feature
 * @param resolution - Map resolution (meters per pixel)
 * @returns OpenLayers Style object with only icon/image, or null to hide feature
 */
export function getFeatureIconStyle(feature: any, resolution?: number): Style | null {
    const properties = feature.get('properties') || {};
    const geometryType = feature.getGeometry().getType();

    if (geometryType === 'Point') {
        // Check if icon previously failed to load
        const iconFailed = feature.get('_iconFailed');
        if (iconFailed) {
            return getDefaultIconStyle(properties);
        }

        // Check for icon URL first
        const iconUrl = getIconUrl(properties);
        if (iconUrl) {
            const icon = createIconStyle(iconUrl, feature, properties);
            if (icon) {
                return new Style({
                    image: icon
                });
            }
        }

        // Fall back to circle style if no icon (no black border for normal points)
        return getDefaultIconStyle(properties);
    } else if (geometryType === 'LineString') {
        return createLineStringStyle(feature, properties, resolution);
    } else if (geometryType === 'Polygon' || geometryType === 'MultiPolygon') {
        return createPolygonStyle(feature, properties, resolution);
    } else {
        // Default style for unknown geometry types
        return createDefaultStyle();
    }
}

