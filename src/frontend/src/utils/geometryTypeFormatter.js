/**
 * Format geometry types for user-facing display
 * Converts technical geometry type names to user-friendly names
 * 
 * @param {string} geometryType - The geometry type (e.g., 'Point', 'LineString', 'MultiLineString', 'Polygon')
 * @returns {string} User-friendly geometry type name
 */
export function formatGeometryTypeForDisplay(geometryType) {
  if (!geometryType) return 'Unknown'
  
  // Convert LineString and MultiLineString to "Line" for user display
  if (geometryType === 'LineString' || geometryType === 'MultiLineString') {
    return 'Line'
  }
  
  // Return other types unchanged
  return geometryType
}

