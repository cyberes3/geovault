/**
 * Elevation Profile Utilities
 *
 * Pure functions for processing elevation data, calculating distances, speeds, and statistics
 * for elevation profile charts.
 */

import {
  getElevationMultiplier,
  getDistanceMultiplier,
  formatSpeed,
  formatDuration
} from '@/utils/units'

/**
 * Calculate distance between two coordinates using Haversine formula
 * Returns distance in meters
 * @param {number} lat1 - Latitude of first point
 * @param {number} lon1 - Longitude of first point
 * @param {number} lat2 - Latitude of second point
 * @param {number} lon2 - Longitude of second point
 * @returns {number} Distance in meters
 */
export function haversineDistance(lat1, lon1, lat2, lon2) {
  const R = 6371000 // Earth radius in meters
  const phi1 = (lat1 * Math.PI) / 180
  const phi2 = (lat2 * Math.PI) / 180
  const deltaPhi = ((lat2 - lat1) * Math.PI) / 180
  const deltaLambda = ((lon2 - lon1) * Math.PI) / 180

  const a =
    Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2) +
    Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2)
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

  return R * c
}

/**
 * Extract coordinates from GeoJSON geometry
 * Returns array of [lon, lat, elevation] coordinates
 * @param {Object} geometry - GeoJSON geometry object
 * @returns {Array} Array of [lon, lat, elevation] coordinates
 */
export function extractCoordinates(geometry) {
  if (!geometry) return []

  const geomType = geometry.type
  const coords = geometry.coordinates

  if (geomType === 'LineString') {
    // LineString: [[lon, lat, ele], ...]
    return coords || []
  } else if (geomType === 'MultiLineString') {
    // MultiLineString: [[[lon, lat, ele], ...], [[lon, lat, ele], ...], ...]
    // Merge all lines into one continuous array
    const merged = []
    if (Array.isArray(coords)) {
      for (const line of coords) {
        if (Array.isArray(line)) {
          merged.push(...line)
        }
      }
    }
    return merged
  }

  return []
}

/**
 * Extract timestamps from feature coordinateProperties
 * Returns array of timestamps (ISO strings) matching the coordinate array
 * Handles both LineString (flat array) and MultiLineString (nested arrays) formats
 * 
 * Timestamps are typically only available for:
 * - GPX tracks (trk): stored in properties.coordinateProperties.times
 * - GPX routes (rte): stored in properties.time (single timestamp)
 * - KML tracks with time data
 * 
 * Timestamps are NOT available for:
 * - Manually drawn lines/features
 * - Features imported from formats without time data
 * - Some converted/processed features where timestamps were lost
 * 
 * @param {Object} feature - GeoJSON feature object
 * @returns {Array|null} Array of timestamps or null if not available
 */
export function extractTimestamps(feature) {
  if (!feature) return null

  const properties = feature.properties || {}
  const coordinateProperties = properties.coordinateProperties

  // Timestamps are stored in coordinateProperties.times for GPX tracks
  // If coordinateProperties doesn't exist, the feature likely doesn't have timestamps
  if (!coordinateProperties || typeof coordinateProperties !== 'object') {
    return null
  }

  const times = coordinateProperties.times
  // Check if times array exists and has data
  if (!times || !Array.isArray(times) || times.length === 0) {
    return null
  }

  const geometry = feature.geometry
  if (!geometry) return null

  const geomType = geometry.type

  if (geomType === 'LineString') {
    // LineString: times is a flat array
    return times
  } else if (geomType === 'MultiLineString') {
    // MultiLineString: times is an array of arrays
    // Merge all line times into one continuous array
    const merged = []
    if (Array.isArray(times)) {
      for (const lineTimes of times) {
        if (Array.isArray(lineTimes)) {
          merged.push(...lineTimes)
        }
      }
    }
    return merged
  }

  return null
}

/**
 * Process elevation data and calculate cumulative distances
 * Returns { distances: [], distancesMeters: [], elevations: [], coordinateMapping: [], timestamps: [] }
 * where distances and elevations are in user preferred units
 * @param {Array} coordinates - Array of [lon, lat, elevation] coordinates
 * @param {Array|null} timestamps - Optional array of ISO timestamp strings
 * @returns {Object} Object with processed elevation data
 */
export function processElevationData(coordinates, timestamps = null) {
  const distances = [0] // Start at 0 (in user units)
  const distancesMeters = [0] // Start at 0 (in meters)
  const elevations = []
  const coordinateMapping = [] // Maps chart index to [lon, lat]
  const validTimestamps = [] // Timestamps matching validPoints
  let cumulativeDistance = 0 // in meters

  // Get conversion factors
  const elevationMultiplier = getElevationMultiplier()
  const distanceMultiplier = getDistanceMultiplier()

  // Filter out points without elevation data and process
  const validPoints = []
  const originalIndices = [] // Track original coordinate indices for timestamp matching
  for (let idx = 0; idx < coordinates.length; idx++) {
    const coord = coordinates[idx]
    if (Array.isArray(coord) && coord.length >= 3) {
      const elevation = coord[2]
      // Check if elevation exists (note: 0 is a valid elevation for sea level)
      // Only reject null, undefined, or coordinates without a third element
      if (elevation !== null && elevation !== undefined) {
        validPoints.push(coord)
        originalIndices.push(idx)
        // Store corresponding timestamp if available
        if (timestamps && idx < timestamps.length) {
          validTimestamps.push(timestamps[idx])
        }
      }
    }
  }

  if (validPoints.length === 0) {
    return { distances: [], distancesMeters: [], elevations: [], coordinateMapping: [], timestamps: [] }
  }

  // Check if all elevations are effectively 0 (placeholder values from backend)
  // If all elevations are 0, treat this as "no elevation data" since 0 is used as a placeholder
  // for missing data in the backend when elevation API fails or coordinates lack elevation
  const allElevationsZero = validPoints.every(coord => Math.abs(coord[2]) < 0.01)
  if (allElevationsZero) {
    return { distances: [], distancesMeters: [], elevations: [], coordinateMapping: [], timestamps: [] }
  }

  // Add first point - convert elevation from meters to user unit
  elevations.push(validPoints[0][2] * elevationMultiplier)
  coordinateMapping.push([validPoints[0][0], validPoints[0][1]]) // [lon, lat]

  // Process remaining points
  for (let i = 1; i < validPoints.length; i++) {
    const prevCoord = validPoints[i - 1]
    const currCoord = validPoints[i]

    // Calculate distance between consecutive points
    const distanceMeters = haversineDistance(
      prevCoord[1], // lat1
      prevCoord[0], // lon1
      currCoord[1], // lat2
      currCoord[0]  // lon2
    )

    // Add to cumulative distance
    cumulativeDistance += distanceMeters

    // Store distance in meters
    distancesMeters.push(cumulativeDistance)

    // Convert to user distance unit (miles or km)
    const distanceUserUnit = cumulativeDistance * distanceMultiplier

    distances.push(distanceUserUnit)
    // Convert elevation from meters to user unit (feet or meters)
    elevations.push(currCoord[2] * elevationMultiplier)
    // Store coordinate mapping
    coordinateMapping.push([currCoord[0], currCoord[1]]) // [lon, lat]
  }

  return { distances, distancesMeters, elevations, coordinateMapping, timestamps: validTimestamps }
}

/**
 * Map chart distance (in user units) to corresponding coordinate on the line
 * Returns [lon, lat] or null if not found
 * @param {number} targetDistance - Distance in user units to map
 * @param {Array} distances - Array of cumulative distances in user units
 * @param {Array} coordinateMapping - Array of [lon, lat] coordinates matching distance indices
 * @returns {Array|null} [lon, lat] coordinate or null
 */
export function mapDistanceToCoordinate(targetDistance, distances, coordinateMapping) {
  if (!distances || !coordinateMapping || distances.length === 0) {
    return null
  }

  // Find the segment that contains this distance
  for (let i = 0; i < distances.length - 1; i++) {
    const dist1 = distances[i]
    const dist2 = distances[i + 1]

    if (targetDistance >= dist1 && targetDistance <= dist2) {
      // Interpolate between the two points
      const ratio = (targetDistance - dist1) / (dist2 - dist1)
      const coord1 = coordinateMapping[i]
      const coord2 = coordinateMapping[i + 1]

      // Linear interpolation
      const lon = coord1[0] + (coord2[0] - coord1[0]) * ratio
      const lat = coord1[1] + (coord2[1] - coord1[1]) * ratio

      return [lon, lat]
    }
  }

  // If beyond the last point, return the last coordinate
  if (targetDistance >= distances[distances.length - 1]) {
    return coordinateMapping[coordinateMapping.length - 1]
  }

  // If before the first point, return the first coordinate
  if (targetDistance <= distances[0]) {
    return coordinateMapping[0]
  }

  return null
}

/**
 * Smooth elevation data using a moving average to reduce GPS noise
 * This is commonly used in GPS software to get more accurate elevation gain/loss
 * @param {Array} elevations - Array of elevation values
 * @param {number} windowSize - Size of the moving average window (default: 10)
 * @returns {Array} Smoothed elevation array
 */
export function smoothElevationData(elevations, windowSize = 10) {
  if (elevations.length === 0) return []
  if (elevations.length <= windowSize) return elevations

  const smoothed = []
  for (let i = 0; i < elevations.length; i++) {
    const start = Math.max(0, i - Math.floor(windowSize / 2))
    const end = Math.min(elevations.length, i + Math.ceil(windowSize / 2))
    const window = elevations.slice(start, end)
    const avg = window.reduce((a, b) => a + b, 0) / window.length
    smoothed.push(avg)
  }
  return smoothed
}

/**
 * Calculate speeds for each segment from distances and timestamps
 * Returns array of speeds in m/s, filtering out invalid segments
 * @param {Array} distances - Array of cumulative distances in meters
 * @param {Array} timestamps - Array of ISO timestamp strings
 * @returns {Array} Array of speeds in m/s
 */
export function calculateSpeeds(distances, timestamps) {
  if (!timestamps || timestamps.length < 2 || distances.length < 2) {
    return []
  }

  const speeds = []
  
  // Calculate speed for each segment
  for (let i = 1; i < distances.length && i < timestamps.length; i++) {
    const distanceMeters = distances[i] - distances[i - 1]
    const time1 = new Date(timestamps[i - 1])
    const time2 = new Date(timestamps[i])
    
    // Check for valid timestamps
    if (isNaN(time1.getTime()) || isNaN(time2.getTime())) {
      continue
    }
    
    const timeDiffSeconds = (time2.getTime() - time1.getTime()) / 1000
    
    // Filter out invalid segments (zero or negative time, zero distance)
    if (timeDiffSeconds > 0 && distanceMeters > 0) {
      const speedMps = distanceMeters / timeDiffSeconds
      speeds.push(speedMps)
    }
  }
  
  return speeds
}

/**
 * Calculate speed statistics from segment speeds
 * Returns formatted stats object with averageSpeed, movingAverageSpeed, totalTrackTime, and totalMovingTime
 * @param {Array} speeds - Array of speeds in m/s
 * @param {Array} distances - Array of cumulative distances in meters
 * @param {Array} timestamps - Array of ISO timestamp strings
 * @returns {Object|null} Stats object with formatted speed and time strings or null if insufficient data
 */
export function calculateSpeedStats(speeds, distances, timestamps) {
  if (!speeds || speeds.length === 0 || !timestamps || timestamps.length < 2) {
    return null
  }

  // Calculate total track time: time from first to last timestamp
  const time1 = new Date(timestamps[0])
  const time2 = new Date(timestamps[timestamps.length - 1])
  
  if (isNaN(time1.getTime()) || isNaN(time2.getTime())) {
    return null
  }
  
  const totalTimeSeconds = (time2.getTime() - time1.getTime()) / 1000
  const totalTrackTime = formatDuration(totalTimeSeconds)

  // Calculate average speed: total distance / total time
  const totalDistanceMeters = distances[distances.length - 1] - distances[0]
  let averageSpeed = null
  if (totalTimeSeconds > 0) {
    averageSpeed = formatSpeed(totalDistanceMeters / totalTimeSeconds)
  }

  // Calculate moving time: sum of time segments where speed > threshold (0.5 m/s = 1.8 km/h to filter GPS noise)
  // This represents time actually spent moving, excluding stops
  // speeds array has one element per segment (between consecutive points)
  // timestamps array has one element per point
  // So segment i is between timestamps[i] and timestamps[i+1], with speed speeds[i]
  const MOVING_SPEED_THRESHOLD = 0.5 // m/s (1.8 km/h or ~1.1 mph)
  let totalMovingTimeSeconds = 0
  
  for (let i = 0; i < speeds.length && i + 1 < timestamps.length; i++) {
    const segmentSpeed = speeds[i]
    if (segmentSpeed > MOVING_SPEED_THRESHOLD) {
      const segTime1 = new Date(timestamps[i])
      const segTime2 = new Date(timestamps[i + 1])
      if (!isNaN(segTime1.getTime()) && !isNaN(segTime2.getTime())) {
        const segmentTimeSeconds = (segTime2.getTime() - segTime1.getTime()) / 1000
        if (segmentTimeSeconds > 0) {
          totalMovingTimeSeconds += segmentTimeSeconds
        }
      }
    }
  }
  
  const totalMovingTime = formatDuration(totalMovingTimeSeconds)

  // Calculate moving average speed (rolling window of 10 points, then average of that)
  let movingAverageSpeed = null
  if (speeds.length > 0) {
    const windowSize = Math.min(10, speeds.length)
    const movingAverages = []
    
    for (let i = 0; i < speeds.length; i++) {
      const start = Math.max(0, i - Math.floor(windowSize / 2))
      const end = Math.min(speeds.length, i + Math.ceil(windowSize / 2))
      const window = speeds.slice(start, end)
      const avg = window.reduce((a, b) => a + b, 0) / window.length
      movingAverages.push(avg)
    }
    
    // Average of all moving averages
    const overallMovingAvg = movingAverages.reduce((a, b) => a + b, 0) / movingAverages.length
    movingAverageSpeed = formatSpeed(overallMovingAvg)
  }

  return {
    averageSpeed,
    movingAverageSpeed,
    totalTrackTime,
    totalMovingTime
  }
}

