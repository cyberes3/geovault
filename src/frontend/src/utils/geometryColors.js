/**
 * Geometry type color constants
 * Used for visual identification of different geometry types across the application
 */

export const GEOMETRY_COLORS = {
  'Point': '#93c5fd',           // Light blue
  'MultiPoint': '#93c5fd',      // Light blue
  'LineString': '#86efac',      // Light green
  'MultiLineString': '#86efac', // Light green
  'Polygon': '#fbbf24',         // Amber/yellow
  'MultiPolygon': '#fbbf24'     // Amber/yellow
}

export const DEFAULT_GEOMETRY_COLOR = '#d1d5db' // Light gray

/**
 * Get the color for a given geometry type
 * @param {string|null|undefined} geometryType - The geometry type (e.g., 'Point', 'LineString', 'Polygon')
 * @returns {string} The hex color code for the geometry type
 */
export function getGeometryTypeColor(geometryType) {
  if (!geometryType) return DEFAULT_GEOMETRY_COLOR
  return GEOMETRY_COLORS[geometryType] || DEFAULT_GEOMETRY_COLOR
}


