/**
 * Feature extent and filtering utilities
 */

/**
 * Get coordinates from geometry
 * @param {Object} geometry - GeoJSON geometry
 * @returns {Array} Array of [lon, lat] coordinates
 */
export function getCoordinatesFromGeometry(geometry) {
  if (!geometry) return [];
  
  switch (geometry.type) {
    case 'Point':
      return [geometry.coordinates];
    case 'MultiPoint':
    case 'LineString':
      return geometry.coordinates;
    case 'MultiLineString':
    case 'Polygon':
      return geometry.coordinates.flat();
    case 'MultiPolygon':
      return geometry.coordinates.flat(2);
    case 'GeometryCollection':
      return geometry.geometries.flatMap(g => getCoordinatesFromGeometry(g));
    default:
      return [];
  }
}

/**
 * Check if feature is in bounds
 * @param {Object} feature - GeoJSON feature
 * @param {Object} bounds - MapLibre bounds object
 * @returns {boolean} True if feature is in bounds
 */
export function isFeatureInBounds(feature, bounds) {
  if (!feature || !feature.geometry || !bounds) return false;
  
  const coords = getCoordinatesFromGeometry(feature.geometry);
  return coords.some(coord => {
    const [lon, lat] = coord;
    return lon >= bounds.getWest() && lon <= bounds.getEast() &&
           lat >= bounds.getSouth() && lat <= bounds.getNorth();
  });
}

/**
 * Filter features by bounds
 * @param {Array} features - Array of features
 * @param {Object} bounds - MapLibre bounds object
 * @param {boolean} excludeLabels - Whether to exclude label points
 * @param {boolean} excludeReplacements - Whether to exclude replacement points
 * @returns {Array} Filtered features
 */
export function filterFeaturesByBounds(features, bounds, excludeLabels = true, excludeReplacements = true) {
  if (!features || !bounds) return [];
  
  return features.filter(f => {
    // Skip label points
    if (excludeLabels && f.properties?._isLabelPoint) return false;
    
    // Skip small feature replacement points
    if (excludeReplacements && f.properties?._isSmallFeatureReplacement) return false;
    
    return isFeatureInBounds(f, bounds);
  });
}

/**
 * Calculate buffered bounds
 * @param {Object} bounds - MapLibre bounds object
 * @param {number} bufferMiles - Buffer distance in miles
 * @returns {Object} Buffered bounds
 */
export function calculateBufferedBounds(bounds, bufferMiles = 500) {
  // Convert miles to degrees (approximate at equator: 1 degree ≈ 69.172 miles)
  const bufferDegrees = bufferMiles / 69.172;
  
  return {
    west: bounds.getWest() - bufferDegrees,
    east: bounds.getEast() + bufferDegrees,
    south: bounds.getSouth() - bufferDegrees,
    north: bounds.getNorth() + bufferDegrees
  };
}

/**
 * Check if feature is far outside bounds
 * @param {Object} feature - GeoJSON feature
 * @param {Object} bufferedBounds - Buffered bounds object
 * @returns {boolean} True if feature is outside buffered bounds
 */
export function isFeatureFarOutside(feature, bufferedBounds) {
  if (!feature || !feature.geometry) return true;
  
  const coords = getCoordinatesFromGeometry(feature.geometry);
  
  // Feature is far outside if ALL coordinates are outside buffered bounds
  return coords.every(coord => {
    const [lon, lat] = coord;
    return lon < bufferedBounds.west || lon > bufferedBounds.east ||
           lat < bufferedBounds.south || lat > bufferedBounds.north;
  });
}

/**
 * Clean up features far outside viewport
 * @param {Array} features - Current features
 * @param {Object} bounds - MapLibre bounds object
 * @param {Function} getCoords - Function to get coordinates from geometry
 * @param {number} bufferMiles - Buffer distance in miles
 * @returns {Object} Result with filteredFeatures and removedCount
 */
export function cleanupDistantFeatures(features, bounds, getCoords, bufferMiles = 500) {
  if (!features || !bounds) return { filteredFeatures: features, removedCount: 0 };
  
  const bufferedBounds = calculateBufferedBounds(bounds, bufferMiles);
  
  const filteredFeatures = features.filter(f => {
    if (!f.geometry) return false;
    
    const coords = getCoords(f.geometry);
    
    // Check if any coordinate is within the buffered bounds
    return coords.some(coord => {
      const [lon, lat] = coord;
      return lon >= bufferedBounds.west && lon <= bufferedBounds.east &&
             lat >= bufferedBounds.south && lat <= bufferedBounds.north;
    });
  });
  
  return {
    filteredFeatures,
    removedCount: features.length - filteredFeatures.length
  };
}

