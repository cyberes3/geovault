/**
 * Utility functions for filtering protected tags
 */

/**
 * Check if a tag is protected (matches exactly or starts with a protected prefix)
 * @param {string} tag - The tag to check
 * @param {string[]} protectedTags - List of protected tag prefixes (e.g., ['type', 'import-year'])
 * @returns {boolean} True if the tag is protected, False otherwise
 */
export function isProtectedTag(tag, protectedTags) {
    if (!tag || typeof tag !== 'string') {
        return false;
    }
    
    for (const prefix of protectedTags) {
        // Exact match
        if (tag === prefix) {
            return true;
        }
        // Prefix match (e.g., "type:point" matches "type")
        if (tag.startsWith(prefix + ':')) {
            return true;
        }
    }
    
    return false;
}

/**
 * Filter out protected tags from a list of tags
 * @param {string[]} tags - List of tags to filter
 * @param {string[]} protectedTags - List of protected tag prefixes
 * @returns {string[]} List of tags with protected tags removed
 */
export function filterProtectedTags(tags, protectedTags) {
    if (!Array.isArray(tags)) {
        return [];
    }
    
    if (!Array.isArray(protectedTags) || protectedTags.length === 0) {
        return tags;
    }
    
    return tags.filter(tag => !isProtectedTag(tag, protectedTags));
}

