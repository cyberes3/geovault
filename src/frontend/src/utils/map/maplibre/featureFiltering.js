/**
 * Feature filtering utilities for MapLibre
 */

import * as turf from '@turf/turf'

/**
 * Check if a point is on a line using Turf.js
 * @param {Object} pointFeature - GeoJSON Point feature
 * @param {Object} lineFeature - GeoJSON LineString feature
 * @param {number} tolerance - Tolerance in meters (default: 10 meters)
 * @returns {boolean} True if point is on the line
 */
function isPointOnLineFeature(pointFeature, lineFeature, tolerance = 10) {
  try {
    // Use Turf's booleanPointOnLine with tolerance
    return turf.booleanPointOnLine(pointFeature, lineFeature, { tolerance })
  } catch (e) {
    // Fallback: check distance to line
    try {
      const nearestPoint = turf.nearestPointOnLine(lineFeature, pointFeature)
      const distance = turf.distance(pointFeature, nearestPoint, { units: 'meters' })
      return distance <= tolerance
    } catch (e2) {
      return false
    }
  }
}

/**
 * Check if a point is on a polygon boundary using Turf.js
 * @param {Object} pointFeature - GeoJSON Point feature
 * @param {Object} polygonFeature - GeoJSON Polygon feature
 * @param {number} tolerance - Tolerance in meters (default: 10 meters)
 * @returns {boolean} True if point is on the polygon boundary
 */
function isPointOnPolygonBoundary(pointFeature, polygonFeature, tolerance = 10) {
  try {
    // Check all rings (outer boundary + holes)
    const rings = polygonFeature.geometry.coordinates
    
    for (const ring of rings) {
      // Create a LineString from the ring (closed)
      const boundaryLine = turf.lineString([...ring, ring[0]])
      
      // Check if point is on this boundary line
      if (isPointOnLineFeature(pointFeature, boundaryLine, tolerance)) {
        return true
      }
    }
    
    return false
  } catch (e) {
    return false
  }
}

/**
 * Filter out Point features that are on polygon/line borders using Turf.js
 * @param {Array<Object>} features - Array of GeoJSON features
 * @param {number} tolerance - Tolerance in meters (default: 10 meters - increased for better detection)
 * @returns {Array<Object>} Filtered features
 */
export function filterPointsOnBorders(features, tolerance = 10) {
  // Separate features by type
  const points = []
  const lines = []
  const polygons = []
  const labelPoints = [] // Keep label points separate - they should never be filtered
  const replacementPoints = [] // Keep replacement points separate - they should never be filtered
  
  features.forEach(f => {
    // Skip label points - they should never be filtered
    if (f.properties?._isLabelPoint) {
      labelPoints.push(f)
      return
    }
    
    // Skip small feature replacement points - they should never be filtered
    if (f.properties?._isSmallFeatureReplacement) {
      replacementPoints.push(f)
      return
    }
    
    const geomType = f.geometry?.type
    if (geomType === 'Point') {
      points.push(f)
    } else if (geomType === 'LineString' || geomType === 'MultiLineString') {
      lines.push(f)
    } else if (geomType === 'Polygon' || geomType === 'MultiPolygon') {
      polygons.push(f)
    }
  })

  // If no points, return all features as-is
  if (points.length === 0) {
    return features
  }

  // Convert lines to LineString features for Turf.js
  const lineFeatures = []
  lines.forEach(f => {
    try {
      if (f.geometry.type === 'LineString') {
        lineFeatures.push(turf.lineString(f.geometry.coordinates, f.properties || {}))
      } else if (f.geometry.type === 'MultiLineString') {
        f.geometry.coordinates.forEach(seq => {
          lineFeatures.push(turf.lineString(seq, f.properties || {}))
        })
      }
    } catch (e) {
      // Skip invalid line features
    }
  })

  // Convert polygons to Polygon features for Turf.js
  const polygonFeatures = []
  polygons.forEach(f => {
    try {
      if (f.geometry.type === 'Polygon') {
        polygonFeatures.push(turf.polygon(f.geometry.coordinates, f.properties || {}))
      } else if (f.geometry.type === 'MultiPolygon') {
        f.geometry.coordinates.forEach(polygonCoords => {
          polygonFeatures.push(turf.polygon(polygonCoords, f.properties || {}))
        })
      }
    } catch (e) {
      // Skip invalid polygon features
    }
  })

  // Filter out points that are on any line or polygon border
  const filteredPoints = points.filter(point => {
    try {
      // Convert point to Turf.js Point feature
      const pointFeature = turf.point(point.geometry.coordinates, point.properties || {})
      
      // Check if point is on any line
      for (const lineFeature of lineFeatures) {
        if (isPointOnLineFeature(pointFeature, lineFeature, tolerance)) {
          return false // Filter out this point
        }
      }

      // Check if point is on any polygon boundary
      for (const polygonFeature of polygonFeatures) {
        if (isPointOnPolygonBoundary(pointFeature, polygonFeature, tolerance)) {
          return false // Filter out this point
        }
      }

      return true // Keep this point
    } catch (e) {
      // Keep point on error
      return true
    }
  })

  // Return all non-point features plus filtered points, label points, and replacement points
  return [...lines, ...polygons, ...filteredPoints, ...labelPoints, ...replacementPoints]
}

