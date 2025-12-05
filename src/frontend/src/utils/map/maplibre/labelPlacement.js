/**
 * Label placement utilities for MapLibre
 * Calculates centroids for polygons and centers for lines
 */

import * as turf from '@turf/turf'

/**
 * Calculate the centroid of a polygon
 * Uses centerOfMass which is better for irregular/concave polygons
 * @param {Object} geometry - GeoJSON Polygon or MultiPolygon geometry
 * @returns {Array<number>} [lon, lat] coordinates of centroid
 */
export function calculatePolygonCentroid(geometry) {
  if (!geometry || !geometry.coordinates) return null

  try {
    let polygonFeature
    if (geometry.type === 'Polygon') {
      polygonFeature = turf.polygon(geometry.coordinates)
    } else if (geometry.type === 'MultiPolygon') {
      polygonFeature = turf.multiPolygon(geometry.coordinates)
    } else {
      return null
    }

    const centerOfMass = turf.centerOfMass(polygonFeature)
    return centerOfMass.geometry.coordinates
  } catch (error) {
    console.warn('Error calculating polygon centroid:', error)
    return null
  }
}

/**
 * Calculate the center point of a line
 * @param {Object} geometry - GeoJSON LineString or MultiLineString geometry
 * @returns {Array<number>} [lon, lat] coordinates of center point
 */
export function calculateLineCenter(geometry) {
  if (!geometry || !geometry.coordinates) return null

  try {
    let lineFeature
    if (geometry.type === 'LineString') {
      lineFeature = turf.lineString(geometry.coordinates)
    } else if (geometry.type === 'MultiLineString') {
      // For MultiLineString, find the center of the longest segment
      let longestLine = null
      let maxLength = 0
      
      geometry.coordinates.forEach(coords => {
        const line = turf.lineString(coords)
        const length = turf.length(line, { units: 'kilometers' })
        if (length > maxLength) {
          maxLength = length
          longestLine = line
        }
      })
      
      if (!longestLine) return null
      lineFeature = longestLine
    } else {
      return null
    }

    // Get the center point along the line
    const length = turf.length(lineFeature, { units: 'kilometers' })
    const centerDistance = length / 2
    
    const centerPoint = turf.along(lineFeature, centerDistance, { units: 'kilometers' })
    return centerPoint.geometry.coordinates
  } catch (error) {
    console.warn('Error calculating line center:', error)
    return null
  }
}

