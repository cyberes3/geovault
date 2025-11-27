/**
 * Icon Utilities
 *
 * Functions for handling icon URLs, icon style creation, and icon-related operations.
 */

import {Circle, Fill, Icon, Stroke, Style} from 'ol/style';
import {APIHOST} from '@/config.js';

/**
 * Check if an icon URL is a system (built-in) icon
 * @param iconUrl - Icon URL to check
 * @returns true if the icon is a system icon
 */
export function isSystemIcon(iconUrl: string): boolean {
    return iconUrl.startsWith('/api/icons/system/');
}

/**
 * Check if an icon URL is a user (uploaded) icon
 * @param iconUrl - Icon URL to check
 * @returns true if the icon is a user icon
 */
export function isUserIcon(iconUrl: string): boolean {
    return iconUrl.startsWith('/api/icons/user/');
}

/**
 * Get icon URL from feature properties
 * Checks multiple common property names for icon URLs
 * @param properties - Feature properties object
 * @returns Icon URL if found, null otherwise
 */
export function getIconUrl(properties: any): string | null {
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
export function resolveIconUrl(iconUrl: string): string {
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
function hexToColor(hexColor: string | undefined, defaultColor: string = '#ff0000'): string {
    if (!hexColor || typeof hexColor !== 'string') return defaultColor;
    return hexColor;
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
export function createIconStyle(iconUrl: string, feature: any, properties: any, minSize: number = 20): Icon | null {
    const builtInIcon = isSystemIcon(iconUrl);
    const markerColor = properties['marker-color'];

    // Check if feature already has a calculated scale from previous load
    let calculatedScale = feature.get('_iconScale');

    // Determine icon source URL
    let iconSrc: string;
    
    if (builtInIcon && markerColor) {
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
        iconSrc = resolveIconUrl(iconUrl);
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
 * Get default fallback icon style (red circle)
 * Used when custom icon fails to load or no icon is specified
 * @param properties - Feature properties
 * @param iconFailed - Whether the icon failed to load (unused, kept for API compatibility)
 * @returns OpenLayers Style with default circle icon
 */
export function getDefaultIconStyle(properties: any): Style {
    const fillColor = hexToColor(properties['marker-color'], '#ff0000');
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

