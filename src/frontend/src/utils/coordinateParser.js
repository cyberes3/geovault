/**
 * Coordinate parsing utility
 * Supports various coordinate formats:
 * - Decimal degrees: 39.126623184652765, -104.88898693773095
 * - DMS (Degrees Minutes Seconds): 39°07'35.8"N 104°53'20.4"W
 * - And many other formats via the coordinate-parser library
 */

import Coordinates from 'coordinate-parser'

/**
 * Attempts to parse a string as coordinates
 * @param {string} input - The input string to parse
 * @returns {Object|null} - Returns {lat, lng} if successful, null otherwise
 */
export function parseCoordinates(input) {
  if (!input || typeof input !== 'string') {
    return null
  }

  try {
    // The coordinate-parser library handles many formats
    const position = new Coordinates(input.trim())
    
    return {
      lat: position.getLatitude(),
      lng: position.getLongitude()
    }
  } catch (error) {
    // Not valid coordinates
    return null
  }
}

