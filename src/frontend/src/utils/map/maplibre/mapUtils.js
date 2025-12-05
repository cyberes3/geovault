/**
 * MapLibre utility functions for bounding boxes, coordinates, and map configuration
 */

/**
 * Generate a bounding box key for caching
 * @param {Array<number>} bounds - [minLon, minLat, maxLon, maxLat]
 * @param {number} zoom - Map zoom level
 * @returns {string} Bounding box key
 */
export function getBoundingBoxKey(bounds, zoom) {
  const roundedZoom = Math.floor(zoom)
  return `${bounds[0].toFixed(4)},${bounds[1].toFixed(4)},${bounds[2].toFixed(4)},${bounds[3].toFixed(4)}_${roundedZoom}`
}

/**
 * Convert bounds to string format for API requests
 * @param {Array<number>} bounds - [minLon, minLat, maxLon, maxLat]
 * @returns {string} Bounding box string
 */
export function getBoundingBoxString(bounds) {
  return `${bounds[0]},${bounds[1]},${bounds[2]},${bounds[3]}`
}

/**
 * Extract coordinates from a geometry
 * @param {Object} geometry - GeoJSON geometry
 * @returns {Array<Array<number>>} Array of [lon, lat] coordinates
 */
export function getFeatureCoordinates(geometry) {
  if (geometry.type === 'Point') {
    return [geometry.coordinates]
  } else if (geometry.type === 'LineString') {
    return geometry.coordinates
  } else if (geometry.type === 'Polygon') {
    return geometry.coordinates[0] // Outer ring
  } else if (geometry.type === 'MultiPoint') {
    return geometry.coordinates
  } else if (geometry.type === 'MultiLineString') {
    return geometry.coordinates.flat()
  } else if (geometry.type === 'MultiPolygon') {
    return geometry.coordinates.flat(2)
  }
  return []
}

/**
 * Extract all coordinates from a LineString or MultiLineString
 * @param {Object} geometry - LineString or MultiLineString geometry
 * @returns {Array<Array<number>>} Array of [lon, lat] coordinates
 */
export function extractLineCoordinates(geometry) {
  if (geometry.type === 'LineString') {
    return geometry.coordinates
  } else if (geometry.type === 'MultiLineString') {
    return geometry.coordinates.flat()
  }
  return []
}

/**
 * Extract all coordinates from a Polygon or MultiPolygon
 * @param {Object} geometry - Polygon or MultiPolygon geometry
 * @returns {Array<Array<number>>} Array of [lon, lat] coordinates
 */
export function extractPolygonCoordinates(geometry) {
  if (geometry.type === 'Polygon') {
    // Return coordinates from all rings (outer + holes)
    return geometry.coordinates.flat()
  } else if (geometry.type === 'MultiPolygon') {
    // Return coordinates from all polygons and their rings
    return geometry.coordinates.flat(2)
  }
  return []
}

