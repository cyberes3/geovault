/**
 * Coordinate validation utilities for GeoJSON coordinates.
 * 
 * Validates coordinate arrays to ensure they:
 * 1. Match the expected structure for the geometry type
 * 2. Are within valid bounds (lon: -180 to 180, lat: -90 to 90)
 * 3. Are not swapped (lat/lon order)
 */

/**
 * Validate a single point coordinate [lon, lat] or [lon, lat, elevation].
 * 
 * @param {Array} point - Coordinate array [lon, lat] or [lon, lat, elevation]
 * @returns {{lon: number, lat: number}} - Validated lon and lat
 * @throws {Error} - If coordinate is invalid
 */
function validatePointCoordinate(point) {
  if (!Array.isArray(point)) {
    throw new Error(`Point coordinate must be an array, got ${typeof point}`)
  }
  
  if (point.length < 2) {
    throw new Error(`Point coordinate must have at least 2 elements [lon, lat], got ${point.length}`)
  }
  
  const lon = point[0]
  const lat = point[1]
  
  // Check types
  if (typeof lon !== 'number' && typeof lon !== 'string') {
    throw new Error(`Longitude must be a number, got ${typeof lon}`)
  }
  if (typeof lat !== 'number' && typeof lat !== 'string') {
    throw new Error(`Latitude must be a number, got ${typeof lat}`)
  }
  
  // Check for null/undefined
  if (lon === null || lon === undefined || lat === null || lat === undefined) {
    throw new Error('Coordinate contains null or undefined values')
  }
  
  // Convert to number
  const lonNum = parseFloat(lon)
  const latNum = parseFloat(lat)
  
  if (isNaN(lonNum) || isNaN(latNum)) {
    throw new Error('Coordinate values must be valid numbers')
  }
  
  // Check for Infinity
  if (!isFinite(lonNum) || !isFinite(latNum)) {
    throw new Error('Coordinate values cannot be Infinity')
  }
  
  // Check bounds — flag obvious lat/lon swap before generic latitude out-of-bounds text
  if (!(-180 <= lonNum && lonNum <= 180)) {
    throw new Error(`Longitude ${lonNum} is out of bounds [-180, 180]`)
  }
  if (Math.abs(latNum) > 90) {
    throw new Error(
      `Coordinates appear to be swapped. Latitude ${latNum} is outside valid range [-90, 90].`
    )
  }
  if (!(-90 <= latNum && latNum <= 90)) {
    throw new Error(`Latitude ${latNum} is out of bounds [-90, 90]`)
  }

  return { lon: lonNum, lat: latNum }
}

/**
 * Check multiple points to detect consistent lat/lon swapping pattern.
 * 
 * @param {Array<{lon: number, lat: number}>} points - List of (lon, lat) objects
 * @returns {string|null} - Error message if swap detected, null otherwise
 */
function checkMultiplePointsForSwap(points) {
  if (points.length < 2) {
    return null
  }
  
  // Count how many points look swapped
  let swapCount = 0
  let totalChecked = 0
  
  for (const { lon, lat } of points) {
    // Skip if either is clearly out of bounds (already caught by validatePointCoordinate)
    if (Math.abs(lon) > 180 || Math.abs(lat) > 90) {
      continue
    }
    
    totalChecked += 1
    
    // If both are in valid ranges but lon is in lat range and lat is in lon range
    if ((-90 <= lon && lon <= 90) && (-180 <= lat && lat <= 180)) {
      // Check if this looks like a swap
      if (Math.abs(lon) > Math.abs(lat)) {
        swapCount += 1
      }
    }
  }
  
  // If majority of points look swapped, report it
  if (totalChecked >= 2 && swapCount > totalChecked * 0.5) {
    return (
      `Multiple coordinates appear to be swapped. ` +
      `Expected [longitude, latitude] format. ` +
      `Longitude should be first (range -180 to 180), latitude second (range -90 to 90).`
    )
  }
  
  return null
}

/**
 * Validate coordinate structure and depth, returning all points for swap detection.
 * 
 * @param {*} coordinates - Coordinate array
 * @param {number} expectedDepth - Expected nesting depth (0 for Point, 1 for LineString/MultiPoint, etc.)
 * @param {string} geometryType - Geometry type name for error messages
 * @returns {Array<{lon: number, lat: number}>} - List of (lon, lat) objects from all points
 * @throws {Error} - If structure is invalid
 */
function validateCoordinatesStructure(coordinates, expectedDepth, geometryType) {
  if (!Array.isArray(coordinates)) {
    throw new Error(`${geometryType} coordinates must be an array`)
  }
  
  if (expectedDepth === 0) {
    // Point: [lon, lat] or [lon, lat, elevation]
    const { lon, lat } = validatePointCoordinate(coordinates)
    return [{ lon, lat }]
  } else if (expectedDepth === 1) {
    // LineString or MultiPoint: [[lon, lat], ...]
    if (coordinates.length === 0) {
      throw new Error(`${geometryType} must have at least one coordinate`)
    }
    
    const points = []
    for (let i = 0; i < coordinates.length; i++) {
      try {
        const { lon, lat } = validatePointCoordinate(coordinates[i])
        points.push({ lon, lat })
      } catch (e) {
        throw new Error(`Invalid coordinate at index ${i}: ${e.message}`)
      }
    }
    
    return points
  } else if (expectedDepth === 2) {
    // Polygon or MultiLineString: [[[lon, lat], ...], ...]
    if (coordinates.length === 0) {
      throw new Error(`${geometryType} must have at least one ring/line`)
    }
    
    const allPoints = []
    for (let ringIdx = 0; ringIdx < coordinates.length; ringIdx++) {
      const ring = coordinates[ringIdx]
      if (!Array.isArray(ring)) {
        throw new Error(`${geometryType} ring/line at index ${ringIdx} must be an array`)
      }
      if (ring.length === 0) {
        throw new Error(`${geometryType} ring/line at index ${ringIdx} must have at least one coordinate`)
      }
      
      for (let pointIdx = 0; pointIdx < ring.length; pointIdx++) {
        try {
          const { lon, lat } = validatePointCoordinate(ring[pointIdx])
          allPoints.push({ lon, lat })
        } catch (e) {
          throw new Error(
            `Invalid coordinate at ring/line ${ringIdx}, point ${pointIdx}: ${e.message}`
          )
        }
      }
    }
    
    return allPoints
  } else if (expectedDepth === 3) {
    // MultiPolygon: [[[[lon, lat], ...], ...], ...]
    if (coordinates.length === 0) {
      throw new Error(`${geometryType} must have at least one polygon`)
    }
    
    const allPoints = []
    for (let polyIdx = 0; polyIdx < coordinates.length; polyIdx++) {
      const polygon = coordinates[polyIdx]
      if (!Array.isArray(polygon)) {
        throw new Error(`${geometryType} polygon at index ${polyIdx} must be an array`)
      }
      if (polygon.length === 0) {
        throw new Error(`${geometryType} polygon at index ${polyIdx} must have at least one ring`)
      }
      
      for (let ringIdx = 0; ringIdx < polygon.length; ringIdx++) {
        const ring = polygon[ringIdx]
        if (!Array.isArray(ring)) {
          throw new Error(
            `${geometryType} polygon ${polyIdx}, ring ${ringIdx} must be an array`
          )
        }
        if (ring.length === 0) {
          throw new Error(
            `${geometryType} polygon ${polyIdx}, ring ${ringIdx} must have at least one coordinate`
          )
        }
        
        for (let pointIdx = 0; pointIdx < ring.length; pointIdx++) {
          try {
            const { lon, lat } = validatePointCoordinate(ring[pointIdx])
            allPoints.push({ lon, lat })
          } catch (e) {
            throw new Error(
              `Invalid coordinate at polygon ${polyIdx}, ring ${ringIdx}, point ${pointIdx}: ${e.message}`
            )
          }
        }
      }
    }
    
    return allPoints
  } else {
    throw new Error(`Unsupported coordinate depth: ${expectedDepth}`)
  }
}

/**
 * Validate coordinates array matches the expected structure for the geometry type.
 * 
 * Validates:
 * 1. Structure depth matches geometry type
 * 2. All coordinates are within bounds (lon: -180 to 180, lat: -90 to 90)
 * 3. Coordinates are not swapped (lat/lon order)
 * 
 * @param {*} coordinates - Coordinate array to validate
 * @param {string} geometryType - Geometry type (Point, LineString, Polygon, MultiPoint, MultiLineString, MultiPolygon)
 * @returns {{valid: boolean, error: string|null}} - Validation result
 */
export function validateCoordinates(coordinates, geometryType) {
  try {
    // Reject null or undefined
    if (coordinates === null || coordinates === undefined) {
      return {
        valid: false,
        error: 'Coordinates cannot be null or empty'
      }
    }
    
    // Reject non-arrays
    if (!Array.isArray(coordinates)) {
      return {
        valid: false,
        error: `Coordinates must be an array, got ${typeof coordinates}`
      }
    }
    
    const geomType = (geometryType || '').toLowerCase()
    
    // Determine expected depth
    const depthMap = {
      'point': 0,
      'linestring': 1,
      'polygon': 2,
      'multipoint': 1,
      'multilinestring': 2,
      'multipolygon': 3,
    }
    
    if (!(geomType in depthMap)) {
      return {
        valid: false,
        error: `Unsupported geometry type: ${geometryType}`
      }
    }
    
    const expectedDepth = depthMap[geomType]
    
    // Reject empty arrays
    if (expectedDepth === 0 && coordinates.length === 0) {
      return {
        valid: false,
        error: 'Point coordinates cannot be empty. Must be [longitude, latitude]'
      }
    }
    
    if (expectedDepth > 0 && coordinates.length === 0) {
      return {
        valid: false,
        error: `${geometryType} coordinates cannot be empty`
      }
    }
    
    // Validate structure and get all points
    const points = validateCoordinatesStructure(coordinates, expectedDepth, geometryType)
    
    // Additional swap detection for multi-point geometries
    if (points.length > 1) {
      const swapError = checkMultiplePointsForSwap(points)
      if (swapError) {
        return {
          valid: false,
          error: swapError
        }
      }
    }
    
    return {
      valid: true,
      error: null
    }
  } catch (e) {
    return {
      valid: false,
      error: e.message
    }
  }
}

