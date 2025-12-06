/**
 * Convert MapLibre features to GeoJSON format for Vue components
 * Returns pure GeoJSON features with no OpenLayers compatibility layer
 */

/**
 * Convert a MapLibre feature to GeoJSON format
 * @param {Object} mlFeature - MapLibre feature object
 * @returns {Object} GeoJSON feature
 */
export function convertMapLibreFeature(mlFeature) {
    const geometry = mlFeature.geometry || {}

    // MapLibre serializes array properties to JSON strings when storing features
    // We need to parse them back to arrays for tags and system_tags
    const properties = mlFeature.properties || {}
    const normalizedProperties = {...properties}

    // Parse tags if it's a string
    if (typeof normalizedProperties.tags === 'string') {
        try {
            normalizedProperties.tags = JSON.parse(normalizedProperties.tags)
        } catch (e) {
            console.warn('Failed to parse tags as JSON:', normalizedProperties.tags)
            normalizedProperties.tags = []
        }
    }

    // Parse system_tags if it's a string
    if (typeof normalizedProperties.system_tags === 'string') {
        try {
            normalizedProperties.system_tags = JSON.parse(normalizedProperties.system_tags)
        } catch (e) {
            console.warn('Failed to parse system_tags as JSON:', normalizedProperties.system_tags)
            normalizedProperties.system_tags = []
        }
    }

    // Return pure GeoJSON feature
    return {
        type: 'Feature',
        // Add database_id as a top-level property for use as RecycleScroller key
        database_id: normalizedProperties.database_id,
        properties: normalizedProperties,
        geometry: geometry
    }
}

