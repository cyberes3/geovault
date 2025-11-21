/**
 * Coordinate comparison utilities for geospatial data
 * Matches the backend's tolerance-based coordinate matching logic
 */

/**
 * Normalize coordinates by rounding to specified tolerance
 * @param {Array} coords - Coordinate array
 * @param {number} tolerance - Tolerance for rounding (default: 1e-6)
 * @returns {Array} Normalized coordinates
 */
export function normalizeCoordinates(coords, tolerance = 1e-6) {
  if (!Array.isArray(coords)) {
    return coords;
  }
  
  // Handle nested arrays (for LineString, Polygon, etc.)
  if (Array.isArray(coords[0])) {
    return coords.map(coord => normalizeCoordinates(coord, tolerance));
  }
  
  // Handle individual coordinate pairs/triples
  return coords.map(coord => {
    if (typeof coord === 'number') {
      return Math.round(coord / tolerance) * tolerance;
    }
    return coord;
  });
}

/**
 * Check if two coordinate sets match within tolerance
 * @param {Array} coord1 - First coordinate set
 * @param {Array} coord2 - Second coordinate set
 * @param {number} tolerance - Tolerance for comparison (default: 1e-6)
 * @returns {boolean} True if coordinates match within tolerance
 */
export function coordinatesMatch(coord1, coord2, tolerance = 1e-6) {
  if (!coord1 || !coord2) {
    return false;
  }
  
  const norm1 = normalizeCoordinates(coord1, tolerance);
  const norm2 = normalizeCoordinates(coord2, tolerance);
  
  return JSON.stringify(norm1) === JSON.stringify(norm2);
}

/**
 * Check if two geometry objects have matching coordinates
 * @param {Object} geom1 - First geometry object
 * @param {Object} geom2 - Second geometry object
 * @param {number} tolerance - Tolerance for comparison (default: 1e-6)
 * @returns {boolean} True if geometries have matching coordinates
 */
export function geometriesMatch(geom1, geom2, tolerance = 1e-6) {
  if (!geom1 || !geom2) {
    return false;
  }
  
  // Check if geometry types match
  if (geom1.type !== geom2.type) {
    return false;
  }
  
  // Compare coordinates
  return coordinatesMatch(geom1.coordinates, geom2.coordinates, tolerance);
}

/**
 * Check if two GeoJSON features have matching coordinates
 * @param {Object} feature1 - First feature object
 * @param {Object} feature2 - Second feature object
 * @param {number} tolerance - Tolerance for comparison (default: 1e-6)
 * @returns {boolean} True if features have matching coordinates
 */
export function featuresMatch(feature1, feature2, tolerance = 1e-6) {
  if (!feature1 || !feature2) {
    return false;
  }
  
  return geometriesMatch(feature1.geometry, feature2.geometry, tolerance);
}

/**
 * Find features in an array that match the given feature's coordinates
 * @param {Object} targetFeature - Feature to match against
 * @param {Array} features - Array of features to search
 * @param {number} tolerance - Tolerance for comparison (default: 1e-6)
 * @returns {Array} Array of matching features
 */
export function findMatchingFeatures(targetFeature, features, tolerance = 1e-6) {
  if (!targetFeature || !Array.isArray(features)) {
    return [];
  }
  
  return features.filter(feature => 
    featuresMatch(targetFeature, feature, tolerance)
  );
}

/**
 * Check if a feature has duplicate coordinates in the given array
 * @param {Object} feature - Feature to check
 * @param {Array} features - Array of features to check against
 * @param {number} tolerance - Tolerance for comparison (default: 1e-6)
 * @returns {boolean} True if feature has duplicates
 */
export function hasDuplicateCoordinates(feature, features, tolerance = 1e-6) {
  const matches = findMatchingFeatures(feature, features, tolerance);
  return matches.length > 1; // More than just itself
}
