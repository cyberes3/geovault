/**
 * Convert MapLibre features to format expected by Vue components
 * Components expect OpenLayers-style features with getGeometry() method
 */

import { getFeatureCoordinates } from './mapUtils.js'

/**
 * Convert a MapLibre feature to a format compatible with existing components
 * @param {Object} mlFeature - MapLibre feature object
 * @returns {Object} Converted feature with OpenLayers-like interface
 */
export function convertMapLibreFeature(mlFeature) {
  const geometry = mlFeature.geometry || {}
  const geometryType = geometry.type || 'Unknown'
  
  // MapLibre serializes array properties to JSON strings when storing features
  // We need to parse them back to arrays for tags and system_tags
  const properties = mlFeature.properties || {}
  const normalizedProperties = { ...properties }
  
  // Parse tags if it's a string
  if (typeof normalizedProperties.tags === 'string') {
    try {
      normalizedProperties.tags = JSON.parse(normalizedProperties.tags)
    } catch (e) {
      console.warn('Failed to parse tags as JSON:', normalizedProperties.tags)
      normalizedProperties.tags = []
    }
  }
  
  // Parse system_tags if it's a string
  if (typeof normalizedProperties.system_tags === 'string') {
    try {
      normalizedProperties.system_tags = JSON.parse(normalizedProperties.system_tags)
    } catch (e) {
      console.warn('Failed to parse system_tags as JSON:', normalizedProperties.system_tags)
      normalizedProperties.system_tags = []
    }
  }
  
  // Create a mock geometry object with OpenLayers-compatible methods
  const mockGeometry = {
    type: geometryType,
    coordinates: geometry.coordinates,
    getType: () => geometryType,
    getCoordinates: () => geometry.coordinates,
    clone: function() {
      // Return a clone of this geometry object with all necessary methods
      const cloned = {
        type: this.type,
        coordinates: JSON.parse(JSON.stringify(geometry.coordinates)),
        getType: this.getType,
        getCoordinates: this.getCoordinates,
        getExtent: this.getExtent,
        clone: this.clone,
        transform: this.transform,
        applyTransform: this.applyTransform
      }
      return cloned
    },
    transform: function() {
      // Transform is a no-op for GeoJSON (already in EPSG:4326)
      return this
    },
    applyTransform: function() {
      // applyTransform is a no-op for GeoJSON (already in EPSG:4326)
      // This method is used by OpenLayers for coordinate transformations
    },
    getExtent: () => {
      // Calculate extent from coordinates
      const coords = getFeatureCoordinates(geometry)
      if (coords.length === 0) return [0, 0, 0, 0]
      
      let minLon = Infinity, minLat = Infinity, maxLon = -Infinity, maxLat = -Infinity
      coords.forEach((coord) => {
        // Handle both [lon, lat] and nested arrays
        const [lon, lat] = Array.isArray(coord) && coord.length >= 2 ? coord : [null, null]
        if (lon != null && lat != null && isFinite(lon) && isFinite(lat)) {
          minLon = Math.min(minLon, lon)
          minLat = Math.min(minLat, lat)
          maxLon = Math.max(maxLon, lon)
          maxLat = Math.max(maxLat, lat)
        }
      })
      
      // Return valid extent or default
      if (isFinite(minLon) && isFinite(minLat) && isFinite(maxLon) && isFinite(maxLat)) {
        return [minLon, minLat, maxLon, maxLat]
      }
      return [0, 0, 0, 0]
    }
  }
  
  return {
    // Add database_id as a top-level property for use as RecycleScroller key
    database_id: normalizedProperties.database_id,
    properties: normalizedProperties,
    geometry: geometry,
    getGeometry: () => mockGeometry,
    get: (key) => {
      if (key === 'properties') return normalizedProperties
      if (key === 'geometry') return mockGeometry
      return mlFeature[key]
    },
    set: (key, value) => {
      if (key === 'properties') {
        mlFeature.properties = value
      } else {
        mlFeature[key] = value
      }
    }
  }
}

