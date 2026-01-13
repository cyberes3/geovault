/**
 * Utility functions for handling nested settings objects.
 */

/**
 * Convert a dot-notation key (e.g., "map.default_basemap") to an array of path segments.
 * @param {string} key - Dot notation key
 * @returns {Array} - Array of path segments
 */
export function keyToPath(key) {
    if (!key) return [];
    return key.split('.');
}

/**
 * Convert a dot-notation key and a value into a nested object.
 * Useful for partial updates (e.g., "map.zoom" -> { map: { zoom: 10 } })
 * 
 * @param {string} key - Dot notation key
 * @param {any} value - Value to set at the end of the path
 * @returns {Object} - Nested object structure
 */
export function keyValueToNested(key, value) {
    const path = keyToPath(key);
    if (path.length === 0) return value;

    const result = {};
    let current = result;

    for (let i = 0; i < path.length - 1; i++) {
        current[path[i]] = {};
        current = current[path[i]];
    }

    current[path[path.length - 1]] = value;
    return result;
}

/**
 * Get a value from a nested object using a dot-notation key.
 * 
 * @param {Object} obj - The object to search
 * @param {string} key - Dot notation key
 * @returns {any} - The value or undefined
 */
export function getNestedValue(obj, key) {
    const path = keyToPath(key);
    let current = obj;

    for (const segment of path) {
        if (current === null || current === undefined) {
            return undefined;
        }
        current = current[segment];
    }

    return current;
}
