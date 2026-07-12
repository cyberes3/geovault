/**
 * Color conversion utilities for the color picker UI (`components/parts/ColorPicker.vue`).
 *
 * Kept separate from `utils/map/colorUtils.ts` (map-styling-specific helpers like
 * `getInverseColor`) so the two "color utils" concerns don't collide under the same filename.
 */

export interface RgbColor {
    r: number;
    g: number;
    b: number;
}

export interface HslColor {
    h: number;
    s: number;
    l: number;
}

/** Convert a hex color string (e.g. "#FF0000" or "FF0000", 3- or 6-digit) to an RGB object. */
export function hexToRgb(hex: string): RgbColor {
    const cleanHex = hex.replace('#', '');

    if (cleanHex.length === 3) {
        const r = parseInt(cleanHex[0] + cleanHex[0], 16);
        const g = parseInt(cleanHex[1] + cleanHex[1], 16);
        const b = parseInt(cleanHex[2] + cleanHex[2], 16);
        return { r, g, b };
    }

    const r = parseInt(cleanHex.substring(0, 2), 16);
    const g = parseInt(cleanHex.substring(2, 4), 16);
    const b = parseInt(cleanHex.substring(4, 6), 16);

    return { r, g, b };
}

/** Convert RGB (0-255 each) to a hex color string (e.g. "#FF0000"). */
export function rgbToHex(r: number, g: number, b: number): string {
    const toHex = (n: number): string => {
        const hex = Math.round(Math.max(0, Math.min(255, n))).toString(16);
        return hex.length === 1 ? '0' + hex : hex;
    };
    return `#${toHex(r)}${toHex(g)}${toHex(b)}`.toUpperCase();
}

/** Convert RGB (0-255 each) to HSL (h: 0-360, s/l: 0-100). */
export function rgbToHsl(r: number, g: number, b: number): HslColor {
    const rNorm = r / 255;
    const gNorm = g / 255;
    const bNorm = b / 255;

    const max = Math.max(rNorm, gNorm, bNorm);
    const min = Math.min(rNorm, gNorm, bNorm);
    const l = (max + min) / 2;
    let h: number;
    let s: number;

    if (max === min) {
        h = 0;
        s = 0; // achromatic
    } else {
        const d = max - min;
        s = l > 0.5 ? d / (2 - max - min) : d / (max + min);

        switch (max) {
            case rNorm:
                h = ((gNorm - bNorm) / d + (gNorm < bNorm ? 6 : 0)) / 6;
                break;
            case gNorm:
                h = ((bNorm - rNorm) / d + 2) / 6;
                break;
            default:
                h = ((rNorm - gNorm) / d + 4) / 6;
                break;
        }
    }

    return {
        h: Math.round(h * 360),
        s: Math.round(s * 100),
        l: Math.round(l * 100),
    };
}

/** Convert HSL (h: 0-360, s/l: 0-100) to RGB (0-255 each). */
export function hslToRgb(h: number, s: number, l: number): RgbColor {
    const hNorm = h / 360;
    const sNorm = s / 100;
    const lNorm = l / 100;

    let r: number;
    let g: number;
    let b: number;

    if (sNorm === 0) {
        r = g = b = lNorm; // achromatic
    } else {
        const hue2rgb = (p: number, q: number, t: number): number => {
            if (t < 0) t += 1;
            if (t > 1) t -= 1;
            if (t < 1 / 6) return p + (q - p) * 6 * t;
            if (t < 1 / 2) return q;
            if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
            return p;
        };

        const q = lNorm < 0.5 ? lNorm * (1 + sNorm) : lNorm + sNorm - lNorm * sNorm;
        const p = 2 * lNorm - q;
        r = hue2rgb(p, q, hNorm + 1 / 3);
        g = hue2rgb(p, q, hNorm);
        b = hue2rgb(p, q, hNorm - 1 / 3);
    }

    return {
        r: Math.round(r * 255),
        g: Math.round(g * 255),
        b: Math.round(b * 255),
    };
}

/** Validate a hex color string (3- or 6-digit, with or without leading #). */
export function isValidHex(hex: string): boolean {
    const hexPattern = /^#?[0-9A-Fa-f]{3}([0-9A-Fa-f]{3})?$/;
    return hexPattern.test(hex);
}

/** Normalize a hex color string to a 6-digit, uppercase, `#`-prefixed form (defaults to black). */
export function normalizeHex(hex: string | null | undefined): string {
    if (!hex) return '#000000';
    let normalized = hex.replace('#', '').toUpperCase();
    if (normalized.length === 3) {
        normalized = normalized
            .split('')
            .map((c) => c + c)
            .join('');
    }
    if (normalized.length === 6) {
        return '#' + normalized;
    }
    return '#000000';
}
