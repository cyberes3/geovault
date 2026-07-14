/**
 * Label placement utilities for MapLibre
 * Calculates centroids for polygons and centers for lines
 */

import type { Feature, LineString, MultiPolygon, Point, Polygon, Position } from 'geojson'
import { polygon, multiPolygon, lineString } from '@turf/helpers'
import { centerOfMass as turfCenterOfMass } from '@turf/center-of-mass'
import { length as turfLength } from '@turf/length'
import { along } from '@turf/along'
import type { GeoJsonFeature } from '@/types/geospatial'

type FeatureGeometry = GeoJsonFeature['geometry']

/**
 * Calculate the centroid of a polygon.
 * Uses centerOfMass which is better for irregular/concave polygons.
 * Returns [lon, lat] coordinates of centroid, or null if it cannot be calculated.
 */
export function calculatePolygonCentroid(geometry: FeatureGeometry | null | undefined): Position | null {
  if (!geometry?.coordinates) return null

  try {
    let polygonFeature: Feature<Polygon> | Feature<MultiPolygon>
    if (geometry.type === 'Polygon') {
      polygonFeature = polygon(geometry.coordinates)
    } else if (geometry.type === 'MultiPolygon') {
      polygonFeature = multiPolygon(geometry.coordinates)
    } else {
      return null
    }

    const centroid = turfCenterOfMass(polygonFeature)
    return centroid.geometry.coordinates
  } catch (error) {
    console.warn('Error calculating polygon centroid:', error)
    return null
  }
}

/**
 * Calculate the bottom center of a polygon for label placement.
 * Used when label would intersect with polygon border if placed at centroid.
 * Returns [lon, lat] coordinates at bottom center.
 */
export function calculatePolygonBottomCenter(geometry: FeatureGeometry | null | undefined): Position | null {
  if (!geometry?.coordinates) return null

  try {
    // Calculate extent
    let allCoords: Position[] = []
    if (geometry.type === 'Polygon') {
      const rings = geometry.coordinates as Position[][]
      allCoords = rings[0] || []
    } else if (geometry.type === 'MultiPolygon') {
      const polygons = geometry.coordinates as Position[][][]
      polygons.forEach(poly => {
        if (poly[0]) {
          allCoords = allCoords.concat(poly[0])
        }
      })
    } else {
      return null
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
 * Calculate the center point of a line.
 * Returns [lon, lat] coordinates of center point.
 */
export function calculateLineCenter(geometry: FeatureGeometry | null | undefined): Position | null {
  if (!geometry?.coordinates) return null

  try {
    let lineFeature: Feature<LineString> | Feature<Point>
    if (geometry.type === 'LineString') {
      lineFeature = lineString(geometry.coordinates)
    } else if (geometry.type === 'MultiLineString') {
      // For MultiLineString, find the center of the longest segment
      let longestLine: Feature<LineString> | null = null
      let maxLength = 0
      const sequences = geometry.coordinates as Position[][]

      for (const coords of sequences) {
        const line = lineString(coords)
        const segmentLength = turfLength(line, { units: 'kilometers' })
        if (segmentLength > maxLength) {
          maxLength = segmentLength
          longestLine = line
        }
      }

      if (!longestLine) return null
      lineFeature = longestLine
    } else {
      return null
    }

    // Get the center point along the line
    const lineLength = turfLength(lineFeature, { units: 'kilometers' })
    const centerDistance = lineLength / 2

    const centerPoint = along(lineFeature, centerDistance, { units: 'kilometers' })
    return centerPoint.geometry.coordinates
  } catch (error) {
    console.warn('Error calculating line center:', error)
    return null
  }
}

