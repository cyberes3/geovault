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
  
  // Create a mock geometry object with getType() method
  const mockGeometry = {
    type: geometryType,
    getType: () => geometryType,
    getExtent: () => {
      // Calculate extent from coordinates
      const coords = getFeatureCoordinates(geometry)
      if (coords.length === 0) return [0, 0, 0, 0]
      
      let minLon = Infinity, minLat = Infinity, maxLon = -Infinity, maxLat = -Infinity
      coords.forEach(([lon, lat]) => {
        minLon = Math.min(minLon, lon)
        minLat = Math.min(minLat, lat)
        maxLon = Math.max(maxLon, lon)
        maxLat = Math.max(maxLat, lat)
      })
      return [minLon, minLat, maxLon, maxLat]
    }
  }
  
  return {
    properties: mlFeature.properties || {},
    geometry: geometry,
    getGeometry: () => mockGeometry,
    get: (key) => {
      if (key === 'properties') return mlFeature.properties || {}
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

