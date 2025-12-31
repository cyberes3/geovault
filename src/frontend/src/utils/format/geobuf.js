/**
 * Geobuf format utilities for decoding protobuf-encoded GeoJSON responses
 * and extracting metadata from HTTP headers.
 */

import geobuf from 'geobuf'
import Pbf from 'pbf'

/**
 * Decode geobuf binary data to GeoJSON FeatureCollection
 * 
 * @param {ArrayBuffer|Uint8Array} arrayBuffer - Binary geobuf data
 * @returns {Object} GeoJSON FeatureCollection
 */
export function decodeGeobuf(arrayBuffer) {
  const pbf = new Pbf(new Uint8Array(arrayBuffer))
  return geobuf.decode(pbf)
}

/**
 * Extract metadata from response headers and reconstruct response format
 * 
 * @param {Response} response - Fetch Response object
 * @param {Object} geojsonData - Decoded GeoJSON FeatureCollection
 * @returns {Object} Response object matching JSON format structure
 */
export function extractMetadataFromHeaders(response, geojsonData) {
  const metadata = {}
  
  // Extract standard metadata fields from headers
  const featureCount = response.headers.get('X-Feature-Count')
  if (featureCount !== null) {
    metadata.feature_count = parseInt(featureCount, 10)
  }
  
  const totalFeatures = response.headers.get('X-Total-Features-In-Bbox')
  if (totalFeatures !== null) {
    metadata.total_features_in_bbox = parseInt(totalFeatures, 10)
  }
  
  const maxFeaturesLimit = response.headers.get('X-Max-Features-Limit')
  if (maxFeaturesLimit !== null) {
    metadata.max_features_limit = parseInt(maxFeaturesLimit, 10)
  }
  
  const zoomLevel = response.headers.get('X-Zoom-Level')
  if (zoomLevel !== null) {
    metadata.zoom_level = parseInt(zoomLevel, 10)
  }
  
  const fallbackUsed = response.headers.get('X-Fallback-Used')
  if (fallbackUsed !== null) {
    metadata.fallback_used = fallbackUsed === 'true'
  }
  
  const timestamp = response.headers.get('X-Timestamp')
  if (timestamp !== null) {
    metadata.timestamp = parseFloat(timestamp)
  }
  
  // Extract extra fields (e.g., collection_name, warning)
  // Headers are in format X-Collection-Name, X-Warning, etc.
  // Convert back to snake_case: X-Collection-Name -> collection_name
  for (const [headerName, headerValue] of response.headers.entries()) {
    if (headerName.startsWith('X-') && 
        !['X-Feature-Count', 'X-Total-Features-In-Bbox', 'X-Max-Features-Limit',
          'X-Zoom-Level', 'X-Fallback-Used', 'X-Timestamp'].includes(headerName)) {
      // Convert X-Collection-Name to collection_name
      const key = headerName
        .substring(2) // Remove 'X-'
        .toLowerCase()
        .replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())
        .replace(/^[a-z]/, (letter) => letter.toLowerCase())
        // Convert camelCase to snake_case
        .replace(/([A-Z])/g, '_$1')
        .toLowerCase()
      
      // Try to parse as number if possible
      const numValue = parseFloat(headerValue)
      if (!isNaN(numValue) && isFinite(numValue) && headerValue.trim() !== '') {
        metadata[key] = numValue
      } else if (headerValue === 'true') {
        metadata[key] = true
      } else if (headerValue === 'false') {
        metadata[key] = false
      } else {
        metadata[key] = headerValue
      }
    }
  }
  
  // Reconstruct the expected response format
  return {
    data: geojsonData,
    ...metadata
  }
}

/**
 * Parse bbox response, handling both JSON and geobuf formats
 * 
 * @param {Response} response - Fetch Response object
 * @returns {Promise<Object>} Parsed response data matching JSON format structure
 */
export async function parseBboxResponse(response) {
  const contentType = response.headers.get('Content-Type') || ''
  
  // Check if response is protobuf/geobuf
  if (contentType.includes('application/x-protobuf') || 
      contentType.includes('application/vnd.mapbox-vector-tile')) {
    // Decode geobuf
    const arrayBuffer = await response.arrayBuffer()
    const geojsonData = decodeGeobuf(arrayBuffer)
    
    // Extract metadata from headers
    return extractMetadataFromHeaders(response, geojsonData)
  } else {
    // Standard JSON response
    return await response.json()
  }
}

