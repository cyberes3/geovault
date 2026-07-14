/**
 * Feature filtering utilities for MapLibre
 */

import type { Feature, LineString, Point, Polygon, Position } from 'geojson'
import { point, lineString, polygon } from '@turf/helpers'
import { booleanPointOnLine } from '@turf/boolean-point-on-line'
import { nearestPointOnLine } from '@turf/nearest-point-on-line'
import { distance } from '@turf/distance'
import type { MapFeature } from './mapFeatureTypes.js'

/** Check if a point is on a line using Turf.js. `tolerance` is in meters (default: 10). */
function isPointOnLineFeature(pointFeature: Feature<Point>, lineFeature: Feature<LineString>, tolerance = 10): boolean {
  try {
    // booleanPointOnLine has no meters-based tolerance option (its `epsilon` is a fractional
    // cross-product threshold, not a distance) - the meter-based `tolerance` is applied via the
    // distance-based fallback below instead.
    return booleanPointOnLine(pointFeature, lineFeature)
  } catch {
    // Fallback: check distance to line
    try {
      const nearestPoint = nearestPointOnLine(lineFeature, pointFeature)
      const pointDistance = distance(pointFeature, nearestPoint, { units: 'meters' })
      return pointDistance <= tolerance
    } catch {
      return false
    }
  }
}

/** Check if a point is on a polygon boundary using Turf.js. `tolerance` is in meters (default: 10). */
function isPointOnPolygonBoundary(pointFeature: Feature<Point>, polygonFeature: Feature<Polygon>, tolerance = 10): boolean {
  try {
    // Check all rings (outer boundary + holes)
    const rings = polygonFeature.geometry.coordinates

    for (const ring of rings) {
      // Create a LineString from the ring (closed)
      const boundaryLine = lineString([...ring, ring[0]])

      // Check if point is on this boundary line
      if (isPointOnLineFeature(pointFeature, boundaryLine, tolerance)) {
        return true
      }
    }

    return false
  } catch {
    return false
  }
}

/**
 * Filter out Point features that are on polygon/line borders using Turf.js.
 * `tolerance` is in meters (default: 10 meters - increased for better detection).
 */
export function filterPointsOnBorders(features: MapFeature[], tolerance = 10): MapFeature[] {
  // Separate features by type
  const points: MapFeature[] = []
  const lines: MapFeature[] = []
  const polygons: MapFeature[] = []
  const labelPoints: MapFeature[] = [] // Keep label points separate - they should never be filtered
  const replacementPoints: MapFeature[] = [] // Keep replacement points separate - they should never be filtered

  features.forEach(f => {
    // Skip label points - they should never be filtered
    if (f.properties._isLabelPoint) {
      labelPoints.push(f)
      return
    }

    // Skip small feature replacement points - they should never be filtered
    if (f.properties._isSmallFeatureReplacement) {
      replacementPoints.push(f)
      return
    }

    const geomType = f.geometry.type
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
  const lineFeatures: Feature<LineString>[] = []
  lines.forEach(f => {
    try {
      if (f.geometry.type === 'LineString') {
        lineFeatures.push(lineString(f.geometry.coordinates, f.properties))
      } else if (f.geometry.type === 'MultiLineString') {
        const sequences = f.geometry.coordinates as Position[][]
        sequences.forEach(seq => {
          lineFeatures.push(lineString(seq, f.properties))
        })
      }
    } catch {
      // Skip invalid line features
    }
  })

  // Convert polygons to Polygon features for Turf.js
  const polygonFeatures: Feature<Polygon>[] = []
  polygons.forEach(f => {
    try {
      if (f.geometry.type === 'Polygon') {
        polygonFeatures.push(polygon(f.geometry.coordinates, f.properties))
      } else if (f.geometry.type === 'MultiPolygon') {
        const polygonSequences = f.geometry.coordinates as Position[][][]
        polygonSequences.forEach(polygonCoords => {
          polygonFeatures.push(polygon(polygonCoords, f.properties))
        })
      }
    } catch {
      // Skip invalid polygon features
    }
  })

  // Filter out points that are on any line or polygon border
  const filteredPoints = points.filter(candidatePoint => {
    try {
      // Convert point to Turf.js Point feature
      const pointFeature = point(candidatePoint.geometry.coordinates, candidatePoint.properties)

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
    } catch {
      // Keep point on error
      return true
    }
  })

  // Return all non-point features plus filtered points, label points, and replacement points
  return [...lines, ...polygons, ...filteredPoints, ...labelPoints, ...replacementPoints]
}

