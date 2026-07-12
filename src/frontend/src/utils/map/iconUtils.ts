/**
 * Icon Utilities
 *
 * Functions for handling icon URLs and icon-related operations.
 */

import { APIHOST } from '@/config.js';

/**
 * Canonical set of GeoJSON feature property names that may hold an icon URL/href. Any code
 * reading the icon property on a feature should go through `getIconUrl` below rather than
 * re-declaring its own subset, so a future new alias only needs to be added in one place.
 */
export const ICON_PROPERTY_NAMES = ['icon', 'icon-href', 'iconUrl', 'icon_url', 'marker-icon', 'marker-symbol', 'symbol'] as const;

export type IconPropertyName = (typeof ICON_PROPERTY_NAMES)[number];

/**
 * File extensions the app recognizes as icon references. Every icon we store ourselves
 * (system or user, via `/api/icons/.../`) is matched unconditionally regardless of extension,
 * so this list only gates the fallback case: a raw filename/URL from imported data that isn't
 * one of our own icon URLs but still looks like it points at an image. All icons we store or
 * accept for upload are PNG/JPG, so that's all this needs to cover.
 */
export const VALID_ICON_EXTENSIONS = ['.png', '.jpg', '.jpeg'];

/**
 * Check if an icon URL is a system (built-in) icon, i.e. served from the `/api/icons/system/`
 * endpoint. System icons can be recolored via the `/api/icons/recolor/` endpoint.
 */
export function isSystemIcon(iconUrl: string | null | undefined): boolean {
    return !!iconUrl && iconUrl.includes('/api/icons/system/');
}

/** Check if an icon URL is a user-uploaded icon, served from the `/api/icons/user/` endpoint. */
export function isUserIcon(iconUrl: string | null | undefined): boolean {
    return !!iconUrl && iconUrl.includes('/api/icons/user/');
}

/**
 * Get icon URL from feature properties.
 * Checks every property name in `ICON_PROPERTY_NAMES` and returns the first non-empty match.
 * @param properties - Feature properties object
 * @returns Icon URL if found, null otherwise
 */
export function getIconUrl(properties: Record<string, unknown> | null | undefined): string | null {
    if (!properties) return null;

    for (const propName of ICON_PROPERTY_NAMES) {
        const value = properties[propName];
        if (typeof value === 'string') {
            const iconUrl = value.trim();
            if (iconUrl) {
                return iconUrl;
            }
        }
    }

    return null;
}

/** Check if a feature has a custom icon set under any of `ICON_PROPERTY_NAMES`. */
export function hasCustomIcon(properties: Record<string, unknown> | null | undefined): boolean {
    return getIconUrl(properties) !== null;
}

/** `@error` handler shared by every `<img>` tag rendering a (possibly broken) icon URL: hides the broken image instead of showing the browser's default placeholder. */
export function handleIconError(event: Event): void {
    const target = event.target as HTMLElement | null;
    if (target?.parentElement) {
        target.style.display = 'none';
    }
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
function isColorTooLight(hexColor: string, threshold: number = 0.7): boolean {
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
function darkenColor(hexColor: string, darkenFactor: number = 0.2): string {
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
