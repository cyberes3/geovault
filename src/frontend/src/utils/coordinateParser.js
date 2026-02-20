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

/**
 * Returns true if the string looks like a coordinate attempt (only N/S/E/W/D letters, 2-6 numbers,
 * valid cardinal orientation). Used to show "Invalid coordinate format" instead of geocoding.
 * Mirrors Android CoordinateParser.looksLikeCoordinates / Validator.
 * @param {string} input
 * @returns {boolean}
 */
export function looksLikeCoordinates(input) {
  if (!input || typeof input !== 'string') return false
  const s = input.trim()
  if (!s) return false
  // Only allow letters n, s, e, w, d (e.g. "39 N 104 W")
  if (/(?![neswd])[a-z]/i.test(s)) return false
  // Valid cardinal orientation: optional N/S and E/W in order
  if (!/^[^nsew]*[ns]?[^nsew]*[ew]?[^nsew]*$/i.test(s)) return false
  // 2, 4, or 6 numbers (lat/lon pairs)
  const numbers = s.match(/-?\d+(\.\d+)?/g)
  const count = numbers ? numbers.length : 0
  if (count === 0 || count % 2 !== 0 || count > 6) return false
  return true
}

