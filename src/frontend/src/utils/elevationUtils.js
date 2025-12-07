/**
 * Utility functions for handling elevation data in GeoJSON features.
 * 
 * MapLibre strips elevation (3rd coordinate) from geometry coordinates and stores
 * it in `_elevation` (points) or `_elevations` (lines) properties. This module
 * provides functions to restore elevation data back into coordinates before
 * sending to the backend.
 * 
 * IMPORTANT: Before sending any full GeoJSON Feature object to update endpoints
 * (like `/api/feature/<id>/update/`), call `restoreElevationInGeometry()` to
 * ensure elevation data is preserved in the coordinates.
 * 
 * Example usage:
 * ```javascript
 * import { restoreElevationInGeometry } from '@/utils/elevationUtils.js'
 * 
 * // Before sending to update endpoint
 * const featureWithElevation = restoreElevationInGeometry(feature)
 * await fetch(`/api/feature/${id}/update/`, {
 *   method: 'PUT',
 *   body: JSON.stringify(featureWithElevation)
 * })
 * ```
 */

/**
 * Restores elevation data from properties back into geometry coordinates.
 * 
 * For Points: Restores `_elevation` to `coordinates[2]`
 * For MultiPoints: Restores `_elevation` to first point's `coordinates[2]`
 * For LineStrings: Restores `_elevations` array to each coordinate's `coordinates[2]`
 * For MultiLineStrings: Restores `_elevations` array (flattened) to each coordinate's `coordinates[2]`
 * 
 * After restoration, removes `_elevation` and `_elevations` from properties.
 * 
 * @param {Object} feature - GeoJSON Feature object
 * @returns {Object} Modified feature with elevation restored in coordinates
 */
export function restoreElevationInGeometry(feature) {
  if (!feature || !feature.geometry || !feature.properties) {
    return feature
  }

  // Create a deep copy to avoid mutating the original
  const restoredFeature = JSON.parse(JSON.stringify(feature))
  const geometry = restoredFeature.geometry
  const properties = restoredFeature.properties

  if (!geometry || !properties) {
    return restoredFeature
  }

  const geometryType = geometry.type

  // Handle Point features
  if (geometryType === 'Point') {
    if (properties._elevation != null && Array.isArray(geometry.coordinates)) {
      // Ensure coordinates array has at least 2 elements
      if (geometry.coordinates.length >= 2) {
        // Set elevation as 3rd coordinate
        geometry.coordinates[2] = properties._elevation
      }
      // Remove _elevation from properties
      delete properties._elevation
    }
  }
  // Handle MultiPoint features
  else if (geometryType === 'MultiPoint') {
    if (properties._elevation != null && Array.isArray(geometry.coordinates)) {
      // Restore elevation to first point (MapLibre stores elevation from first point)
      if (geometry.coordinates.length > 0 && Array.isArray(geometry.coordinates[0])) {
        const firstPoint = geometry.coordinates[0]
        if (firstPoint.length >= 2) {
          firstPoint[2] = properties._elevation
        }
      }
      // Remove _elevation from properties
      delete properties._elevation
    }
  }
  // Handle LineString features
  else if (geometryType === 'LineString') {
    if (properties._elevations != null && Array.isArray(geometry.coordinates)) {
      const elevations = properties._elevations
      
      // Parse elevations if it's a string (sometimes stored as JSON string)
      let elevationArray = elevations
      if (typeof elevations === 'string') {
        try {
          elevationArray = JSON.parse(elevations)
        } catch (e) {
          console.warn('Failed to parse _elevations as JSON:', elevations)
          elevationArray = null
        }
      }

      if (Array.isArray(elevationArray) && Array.isArray(geometry.coordinates)) {
        // Restore elevation to each coordinate
        geometry.coordinates.forEach((coord, index) => {
          if (Array.isArray(coord) && coord.length >= 2 && elevationArray[index] != null) {
            coord[2] = elevationArray[index]
          }
        })
      }
      
      // Remove _elevations from properties
      delete properties._elevations
    }
  }
  // Handle MultiLineString features
  else if (geometryType === 'MultiLineString') {
    if (properties._elevations != null && Array.isArray(geometry.coordinates)) {
      const elevations = properties._elevations
      
      // Parse elevations if it's a string
      let elevationArray = elevations
      if (typeof elevations === 'string') {
        try {
          elevationArray = JSON.parse(elevations)
        } catch (e) {
          console.warn('Failed to parse _elevations as JSON:', elevations)
          elevationArray = null
        }
      }

      if (Array.isArray(elevationArray) && Array.isArray(geometry.coordinates)) {
        // Flatten the elevation array index to match flattened coordinates
        let elevationIndex = 0
        
        geometry.coordinates.forEach((lineCoords) => {
          if (Array.isArray(lineCoords)) {
            lineCoords.forEach((coord) => {
              if (Array.isArray(coord) && coord.length >= 2 && elevationArray[elevationIndex] != null) {
                coord[2] = elevationArray[elevationIndex]
                elevationIndex++
              }
            })
          }
        })
      }
      
      // Remove _elevations from properties
      delete properties._elevations
    }
  }

  return restoredFeature
}

/**
 * Restores elevation for a FeatureCollection.
 * 
 * @param {Object} featureCollection - GeoJSON FeatureCollection object
 * @returns {Object} Modified FeatureCollection with elevation restored in all features
 */
export function restoreElevationInFeatureCollection(featureCollection) {
  if (!featureCollection || featureCollection.type !== 'FeatureCollection') {
    return featureCollection
  }

  const restored = JSON.parse(JSON.stringify(featureCollection))
  
  if (Array.isArray(restored.features)) {
    restored.features = restored.features.map(feature => restoreElevationInGeometry(feature))
  }

  return restored
}

