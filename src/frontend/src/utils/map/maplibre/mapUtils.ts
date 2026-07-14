/**
 * MapLibre utility functions for bounding boxes, coordinates, and map configuration
 */
import type { Geometry, Position } from 'geojson'

/** Generate a bounding box key for caching. `bounds` is [minLon, minLat, maxLon, maxLat]. */
export function getBoundingBoxKey(bounds: [number, number, number, number], zoom: number): string {
  const roundedZoom = Math.floor(zoom)
  return `${bounds[0].toFixed(4)},${bounds[1].toFixed(4)},${bounds[2].toFixed(4)},${bounds[3].toFixed(4)}_${roundedZoom}`
}

/** Convert bounds to string format for API requests. `bounds` is [minLon, minLat, maxLon, maxLat]. */
export function getBoundingBoxString(bounds: [number, number, number, number]): string {
  return `${bounds[0]},${bounds[1]},${bounds[2]},${bounds[3]}`
}

/** Extract coordinates from a GeoJSON geometry. */
export function getFeatureCoordinates(geometry: Geometry): Position[] {
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

/** Extract all coordinates from a LineString or MultiLineString geometry. */
export function extractLineCoordinates(geometry: Geometry): Position[] {
  if (geometry.type === 'LineString') {
    return geometry.coordinates
  } else if (geometry.type === 'MultiLineString') {
    return geometry.coordinates.flat()
  }
  return []
}

/** Extract all coordinates from a Polygon or MultiPolygon geometry. */
export function extractPolygonCoordinates(geometry: Geometry): Position[] {
  if (geometry.type === 'Polygon') {
    // Return coordinates from all rings (outer + holes)
    return geometry.coordinates.flat()
  } else if (geometry.type === 'MultiPolygon') {
    // Return coordinates from all polygons and their rings
    return geometry.coordinates.flat(2)
  }
  return []
}

