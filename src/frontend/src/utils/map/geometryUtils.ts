/**
 * Geometry Utilities
 *
 * Functions for feature geometry operations, counting, and timestamp management.
 */

/**
 * Count only polygon and line features (exclude points)
 * This is used for the tier system which only considers complex geometries
 * @param features - Array of OpenLayers features
 * @returns Number of polygon and line features
 */
export function countPolyLineFeatures(features: any[]): number {
    return features.filter(feature => {
        const geometryType = feature.getGeometry().getType();
        return ['LineString', 'Polygon', 'MultiLineString', 'MultiPolygon'].indexOf(geometryType) !== -1;
    }).length;
}

/**
 * Generate a unique ID for a feature
 * @param feature - OpenLayers feature
 * @param counter - Counter for generating unique IDs
 * @returns Unique feature ID
 */
export function getFeatureId(feature: any, counter: { value: number }): string {
    // Try to get existing ID or create a new one
    if (!feature._geoJsonMapId) {
        feature._geoJsonMapId = `feature_${++counter.value}_${Date.now()}`;
    }
    return feature._geoJsonMapId;
}

/**
 * Add timestamp to feature for tracking
 * @param feature - OpenLayers feature
 * @param featureTimestamps - Timestamp storage object
 * @param featureId - Feature ID
 */
export function addFeatureTimestamp(_feature: any, featureTimestamps: Record<string, number>, featureId: string): void {
    featureTimestamps[featureId] = Date.now();
}

/**
 * Remove feature timestamp
 * @param featureId - Feature ID
 * @param featureTimestamps - Timestamp storage object
 */
export function removeFeatureTimestamp(featureId: string, featureTimestamps: Record<string, number>): void {
    delete featureTimestamps[featureId];
}

/**
 * Sort features by timestamp (oldest first)
 * @param features - Array of OpenLayers features
 * @param featureTimestamps - Timestamp storage object
 * @param getFeatureId - Function to get feature ID
 * @returns Sorted features with timestamps
 */
export function sortFeaturesByTimestamp(
    features: any[],
    featureTimestamps: Record<string, number>,
    getFeatureId: (feature: any) => string
): Array<{ feature: any; featureId: string; timestamp: number }> {
    return features.map(feature => {
        const featureId = getFeatureId(feature);
        return {
            feature,
            featureId,
            timestamp: featureTimestamps[featureId] || 0
        };
    }).sort((a, b) => a.timestamp - b.timestamp);
}

