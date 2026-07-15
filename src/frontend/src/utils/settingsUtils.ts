/**
 * Utility functions for handling nested settings objects.
 */

/** Path segments that would allow prototype pollution; must not be used as keys. */
const UNSAFE_PATH_SEGMENTS = new Set(['__proto__', 'constructor', 'prototype']);

function isUnsafePathSegment(segment: string): boolean {
    return UNSAFE_PATH_SEGMENTS.has(segment);
}

/**
 * Convert a dot-notation key (e.g., "map.default_basemap") to an array of path segments.
 */
export function keyToPath(key: string): string[] {
    if (!key) return [];
    return key.split('.');
}

/**
 * Convert a dot-notation key and a value into a nested object.
 * Useful for partial updates (e.g., "map.zoom" -> { map: { zoom: 10 } })
 * Rejects keys that could cause prototype pollution (e.g. __proto__, constructor, prototype).
 */
export function keyValueToNested(key: string, value: unknown): unknown {
    const path = keyToPath(key);
    if (path.length === 0) return value;

    for (const segment of path) {
        if (isUnsafePathSegment(segment)) {
            throw new Error(`Invalid key segment for nested settings: "${segment}"`);
        }
    }

    const result: Record<string, unknown> = {};
    let current = result;

    for (let i = 0; i < path.length - 1; i++) {
        const next: Record<string, unknown> = {};
        current[path[i]] = next;
        current = next;
    }

    current[path[path.length - 1]] = value;
    return result;
}

/**
 * Get a value from a nested object using a dot-notation key.
 * Only follows own properties to avoid reading from a polluted prototype.
 */
export function getNestedValue(obj: unknown, key: string): unknown {
    const path = keyToPath(key);
    let current: unknown = obj;

    for (const segment of path) {
        if (current === null || current === undefined) {
            return undefined;
        }
        if (!Object.prototype.hasOwnProperty.call(current, segment)) {
            return undefined;
        }
        current = (current as Record<string, unknown>)[segment];
    }

    return current;
}
