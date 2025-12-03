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
 * Calculate zoom level from resolution (Web Mercator)
 * Uses equator approximation for simplicity
 * @param resolution - Map resolution in meters per pixel
 * @returns Zoom level
 */
export function getZoomFromResolution(resolution: number): number {
    if (resolution <= 0) return 10; // Default to base zoom if invalid
    // Web Mercator resolution formula: resolution = 156543.03392 / 2^zoom
    // Solving for zoom: zoom = log2(156543.03392 / resolution)
    const baseResolution = 156543.03392;
    return Math.log2(baseResolution / resolution);
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
 * Get the icon source URL, handling system icon recoloring if needed
 * @param iconUrl - Icon URL (relative or absolute)
 * @param properties - Feature properties (for marker-color)
 * @returns Resolved icon source URL
 */
function getIconSourceUrl(iconUrl: string, properties: any): string {
    const builtInIcon = isSystemIcon(iconUrl);
    const markerColor = properties['marker-color'];
    
    if (builtInIcon && markerColor) {
        // NOTE: Can't recolor in JS, it's fucked. Must use server-side PIL processing.
        // Even with binary PNG decoding (bypassing Canvas rendering), semi-transparent
        // edge pixels with low alpha values appear as black spots. CalTopo also uses
        // backend recoloring: https://caltopo.com/icon.png?cfg=campfire%2CFF0000%231
        
        // Extract icon path relative to assets/icons/ for recolor endpoint
        // Extract path after /api/icons/system/ (e.g., 'caltopo/4wd.png')
        const iconPathForRecolor = iconUrl.replace('/api/icons/system/', '');
        const encodedColor = encodeURIComponent(markerColor);
        const encodedIcon = encodeURIComponent(iconPathForRecolor);
        return `${APIHOST}/api/icons/recolor/?icon=${encodedIcon}&color=${encodedColor}`;
    }
    
    return resolveIconUrl(iconUrl);
}

/**
 * Create icon style with error handling
 * Preloads image to detect loading failures and marks feature accordingly
 * Ensures icon has a minimum size by calculating appropriate scale
 * Supports server-side recoloring for built-in icons if marker-color is specified
 * Scales exponentially with zoom level (smaller when zoomed out, larger when zoomed in)
 * @param iconUrl - Icon URL (relative or absolute)
 * @param feature - OpenLayers feature
 * @param properties - Feature properties (for marker-color)
 * @param minSize - Minimum size in pixels (default: 20)
 * @param resolution - Map resolution in meters per pixel (optional, for zoom-based scaling)
 * @returns Icon style or null if icon failed to load
 */
export function createIconStyle(iconUrl: string, feature: any, properties: any, minSize: number = 20, resolution?: number): Icon | null {
    // Check if feature already has a calculated scale from previous load
    let calculatedScale = feature.get('_iconScale');

    // Determine icon source URL
    const iconSrc = getIconSourceUrl(iconUrl, properties);

    // Check if we're already loading this icon to prevent duplicate network requests
    const existingIconSrc = feature.get('_iconSrc');
    const iconSrcChanged = existingIconSrc !== iconSrc;
    
    // Only create new Image if icon source changed or hasn't been loaded yet
    if (iconSrcChanged || !existingIconSrc) {
        // Store the icon source URL on the feature to track what we're loading
        feature.set('_iconSrc', iconSrc);
        
        // Clear previous failure state if icon source changed
        if (iconSrcChanged) {
            feature.set('_iconFailed', false);
            // Reset scale so it can be recalculated for new icon
            calculatedScale = undefined;
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
    }

    // Use stored scale if available, otherwise use default
    let finalScale = calculatedScale !== undefined ? calculatedScale : 0.4;

    // Apply exponential zoom-based scaling if resolution is provided
    if (resolution !== undefined && resolution > 0) {
        const zoom = getZoomFromResolution(resolution);
        const zoomMultiplier = getZoomScaleMultiplier(zoom);
        finalScale *= zoomMultiplier;
    }

    return new Icon({
        src: iconSrc,
        scale: finalScale,
        anchor: [0.5, 1.0], // Anchor at bottom center of icon
    });
}

/**
 * Preload an icon image to cache it for faster loading later
 * @param iconUrl - Icon URL (relative or absolute)
 * @param feature - OpenLayers feature
 * @param properties - Feature properties (for marker-color)
 */
export function preloadIconImage(iconUrl: string, feature: any, properties: any): void {
    const iconSrc = getIconSourceUrl(iconUrl, properties);

    // Check if we're already loading this icon to prevent duplicate network requests
    const existingIconSrc = feature.get('_iconSrc');
    const iconSrcChanged = existingIconSrc !== iconSrc;
    
    // Only preload if icon source changed or hasn't been loaded yet
    if (iconSrcChanged || !existingIconSrc) {
        // Store the icon source URL on the feature to track what we're loading
        feature.set('_iconSrc', iconSrc);
        
        // Clear previous failure state if icon source changed
        if (iconSrcChanged) {
            feature.set('_iconFailed', false);
        }
        
        // Preload image to cache it
        const img = new Image();
        
        img.onload = () => {
            // Image is now cached and ready for use
            const naturalSize = Math.max(img.naturalWidth, img.naturalHeight);
            if (naturalSize > 0) {
                // Calculate scale needed for minimum size (same as createIconStyle)
                const minSize = 20;
                const scale = Math.max(minSize / naturalSize, 0.4);
                feature.set('_iconScale', scale);
            }
        };
        
        img.onerror = () => {
            // Mark feature as having failed icon load
            feature.set('_iconFailed', true);
        };
        
        img.src = iconSrc;
    }
}

/**
 * Parse hex color to RGB components
 * @param hexColor - Hex color string (e.g., '#ff0000')
 * @returns Object with r, g, b values (0-255)
 */
function parseHexColor(hexColor: string): { r: number; g: number; b: number } {
    const hex = hexColor.replace('#', '');
    return {
        r: parseInt(hex.slice(0, 2), 16),
        g: parseInt(hex.slice(2, 4), 16),
        b: parseInt(hex.slice(4, 6), 16)
    };
}

/**
 * Check if a color is too light (high brightness)
 * Uses relative luminance to determine if color is hard to see on typical map backgrounds
 * @param hexColor - Hex color string (e.g., '#ff0000')
 * @param threshold - Brightness threshold (0-1, default: 0.7)
 * @returns true if color is too light
 */
export function isColorTooLight(hexColor: string, threshold: number = 0.7): boolean {
    const { r, g, b } = parseHexColor(hexColor);
    
    // Calculate relative luminance (perceived brightness)
    // Formula from WCAG: https://www.w3.org/WAI/GL/wiki/Relative_luminance
    const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    
    return luminance > threshold;
}

/**
 * Darken a color to make it more visible
 * Reduces brightness while preserving hue
 * @param hexColor - Hex color string (e.g., '#ff0000')
 * @param darkenFactor - How much to darken (0-1, default: 0.2 means reduce to 80% brightness)
 * @returns Darkened hex color string
 */
export function darkenColor(hexColor: string, darkenFactor: number = 0.2): string {
    let { r, g, b } = parseHexColor(hexColor);
    
    // Darken by reducing each component
    // Keep at least 20% of original brightness to maintain color identity
    const minBrightness = 0.2;
    const actualDarkenFactor = Math.min(darkenFactor, 1 - minBrightness);
    
    r = Math.max(0, Math.floor(r * (1 - actualDarkenFactor)));
    g = Math.max(0, Math.floor(g * (1 - actualDarkenFactor)));
    b = Math.max(0, Math.floor(b * (1 - actualDarkenFactor)));
    
    // Ensure minimum visibility - if too dark, lighten slightly
    const minComponent = 40; // Minimum RGB value for visibility
    if (r < minComponent && g < minComponent && b < minComponent) {
        const maxComponent = Math.max(r, g, b);
        const scale = minComponent / maxComponent;
        r = Math.min(255, Math.floor(r * scale));
        g = Math.min(255, Math.floor(g * scale));
        b = Math.min(255, Math.floor(b * scale));
    }
    
    return `#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}`;
}

/**
 * Detect primary color from an image
 * Analyzes image pixels to find the dominant color, ignoring transparent/white/black pixels
 * Automatically darkens light colors for better visibility on maps
 * @param imageUrl - URL of the image to analyze
 * @returns Promise that resolves to hex color string (e.g., '#ff0000')
 */
export function detectPrimaryColor(imageUrl: string): Promise<string> {
    return new Promise((resolve, reject) => {
        const img = new Image();
        img.crossOrigin = 'anonymous';
        
        img.onload = () => {
            try {
                // Create canvas to analyze image
                const canvas = document.createElement('canvas');
                const ctx = canvas.getContext('2d');
                if (!ctx) {
                    reject(new Error('Could not get canvas context'));
                    return;
                }
                
                canvas.width = img.naturalWidth;
                canvas.height = img.naturalHeight;
                ctx.drawImage(img, 0, 0);
                
                // Get image data
                const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
                const data = imageData.data;
                
                // Count color frequencies, ignoring transparent, very light, and very dark pixels
                const colorCounts: { [key: string]: number } = {};
                
                for (let i = 0; i < data.length; i += 4) {
                    const r = data[i];
                    const g = data[i + 1];
                    const b = data[i + 2];
                    const a = data[i + 3];
                    
                    // Skip transparent pixels (alpha < 128)
                    if (a < 128) continue;
                    
                    // Skip very light pixels (likely background/white)
                    if (r > 240 && g > 240 && b > 240) continue;
                    
                    // Skip very dark pixels (likely outline/black)
                    if (r < 20 && g < 20 && b < 20) continue;
                    
                    // Quantize colors to reduce noise (group similar colors)
                    const quantizedR = Math.floor(r / 16) * 16;
                    const quantizedG = Math.floor(g / 16) * 16;
                    const quantizedB = Math.floor(b / 16) * 16;
                    
                    const colorKey = `${quantizedR},${quantizedG},${quantizedB}`;
                    colorCounts[colorKey] = (colorCounts[colorKey] || 0) + 1;
                }
                
                // Find the most frequent color
                let maxCount = 0;
                let dominantColor = '#ff0000'; // Default fallback
                
                for (const [colorKey, count] of Object.entries(colorCounts)) {
                    if (count > maxCount) {
                        maxCount = count;
                        const [r, g, b] = colorKey.split(',').map(Number);
                        // Convert to hex
                        dominantColor = `#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}`;
                    }
                }
                
                // Darken color if it's too light for visibility
                if (isColorTooLight(dominantColor)) {
                    dominantColor = darkenColor(dominantColor);
                }
                
                resolve(dominantColor);
            } catch (error) {
                reject(error);
            }
        };
        
        img.onerror = () => {
            reject(new Error('Failed to load image for color detection'));
        };
        
        img.src = imageUrl;
    });
}

/**
 * Get default fallback icon style (red circle)
 * Used when custom icon fails to load or no icon is specified
 * Scales exponentially with zoom level (smaller when zoomed out, larger when zoomed in)
 * @param properties - Feature properties
 * @param resolution - Map resolution in meters per pixel (optional, for zoom-based scaling)
 * @param overrideColor - Optional color to override marker-color property
 * @returns OpenLayers Style with default circle icon
 */
export function getDefaultIconStyle(properties: any, resolution?: number, overrideColor?: string): Style {
    const fillColor = overrideColor || hexToColor(properties['marker-color'], '#ff0000');
    const baseRadius = 3;
    
    // Apply exponential zoom-based scaling if resolution is provided
    let radius = baseRadius;
    if (resolution !== undefined && resolution > 0) {
        const zoom = getZoomFromResolution(resolution);
        const zoomMultiplier = getZoomScaleMultiplier(zoom);
        radius = baseRadius * zoomMultiplier;
    }
    
    return new Style({
        image: new Circle({
            radius: radius,
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

