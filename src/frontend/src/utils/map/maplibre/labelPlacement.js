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
 * Calculate the bottom center of a polygon for label placement
 * Used when label would intersect with polygon border if placed at centroid
 * @param {Object} geometry - GeoJSON Polygon or MultiPolygon geometry
 * @returns {Array<number>} [lon, lat] coordinates at bottom center
 */
export function calculatePolygonBottomCenter(geometry) {
  if (!geometry || !geometry.coordinates) return null

  try {
    // Calculate extent
    let allCoords = []
    if (geometry.type === 'Polygon') {
      allCoords = geometry.coordinates[0] || []
    } else if (geometry.type === 'MultiPolygon') {
      geometry.coordinates.forEach(polygon => {
        if (polygon[0]) {
          allCoords = allCoords.concat(polygon[0])
        }
      })
    }

    if (allCoords.length === 0) return null

    let minLon = Infinity, minLat = Infinity, maxLon = -Infinity, maxLat = -Infinity
    allCoords.forEach(coord => {
      const [lon, lat] = coord
      if (isFinite(lon) && isFinite(lat)) {
        minLon = Math.min(minLon, lon)
        minLat = Math.min(minLat, lat)
        maxLon = Math.max(maxLon, lon)
        maxLat = Math.max(maxLat, lat)
      }
    })

    if (!isFinite(minLon) || !isFinite(minLat) || !isFinite(maxLon) || !isFinite(maxLat)) {
      return null
    }

    // Return bottom center: [centerX, minY]
    const centerX = (minLon + maxLon) / 2
    return [centerX, minLat]
  } catch (error) {
    console.warn('Error calculating polygon bottom center:', error)
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

