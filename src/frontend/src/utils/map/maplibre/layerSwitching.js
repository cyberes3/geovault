import { ensureLayersExist, addFeaturesToMap } from './index.js'

/**
 * Restore GeoJSON features to the map after layer switch
 * @param {Object} map - MapLibre map instance
 * @param {Object} geojsonData - GeoJSON data to restore
 * @param {boolean} showAllLabels - Whether to show labels
 * @param {Object|null} labelMarkerManager - Label marker manager instance
 */
export async function restoreGeoJsonFeatures(map, geojsonData, showAllLabels, labelMarkerManager) {
  if (!map) return

  // Always ensure the geojson-data source exists (setStyle() destroys all sources)
  // Wait a brief moment to ensure style is fully loaded before adding source
  await new Promise(resolve => setTimeout(resolve, 50))
  
  if (!map.getSource('geojson-data')) {
    map.addSource('geojson-data', {
      type: 'geojson',
      data: {
        type: 'FeatureCollection',
        features: []
      }
    })
  }

  // Ensure layers exist before adding features
  ensureLayersExist(map, showAllLabels)

  // Restore features - setStyle() destroys all sources, so source should be empty
  // Use addFeaturesToMap which handles all the processing (icons, labels, etc.)
  // Since the source is empty after setStyle(), this will effectively set the features directly
  if (geojsonData && geojsonData.features && geojsonData.features.length > 0) {
    try {
      const zoom = map.getZoom()
      // addFeaturesToMap will merge with existing features, but since setStyle() destroyed
      // all sources, the source should be empty, so this effectively sets the features
      await addFeaturesToMap(map, geojsonData, showAllLabels, zoom)
      
      // Verify features were actually added
      const source = map.getSource('geojson-data')
      if (source) {
        const serialized = source.serialize()
        const data = serialized.data
        const featuresAdded = data && data.features && data.features.length > 0
        if (!featuresAdded) {
          console.warn('Features were not added to map during restoration', {
            expectedCount: geojsonData.features.length,
            sourceExists: !!source
          })
        }
      }
    } catch (error) {
      console.error('Error restoring features to map:', error)
      // Don't throw - allow the function to continue
    }
  }

  // Ensure feature layers are positioned after all base layers
  const style = map.getStyle()
  if (style && style.layers) {
    const ourFeatureLayers = ['polygons', 'polygon-outlines', 'lines', 'points', 'replacement-points', 'point-icons']
    ourFeatureLayers.forEach(layerId => {
      if (map.getLayer(layerId)) {
        map.moveLayer(layerId)
      }
    })
  }

  // Update label markers
  if (showAllLabels && labelMarkerManager) {
    const source = map.getSource('geojson-data')
    if (source) {
      const serialized = source.serialize()
      const data = serialized.data
      if (data && data.features) {
        labelMarkerManager.updateMarkers(data.features)
      }
    }
  }
}

/**
 * Restore map view (center, zoom, pitch, bearing) after layer switch
 * @param {Object} map - MapLibre map instance
 * @param {Object} center - Map center {lng, lat}
 * @param {number} zoom - Zoom level
 * @param {number} pitch - Pitch angle
 * @param {number} bearing - Bearing angle
 */
export function restoreMapView(map, center, zoom, pitch, bearing) {
  if (!map) return
  map.setCenter([center.lng, center.lat])
  map.setZoom(zoom)
  map.setPitch(pitch)
  map.setBearing(bearing)
}

/**
 * Extract current map state for restoration
 * @param {Object} map - MapLibre map instance
 * @returns {Object} Map state object with center, zoom, pitch, bearing
 */
export function getMapState(map) {
  if (!map) return null
  
  return {
    center: map.getCenter(),
    zoom: map.getZoom(),
    pitch: map.getPitch(),
    bearing: map.getBearing()
  }
}

/**
 * Extract GeoJSON data from map source
 * @param {Object} map - MapLibre map instance
 * @returns {Object|null} GeoJSON data or null if no source exists
 */
export function getGeoJsonData(map) {
  if (!map) return null
  
  const geojsonSource = map.getSource('geojson-data')
  if (!geojsonSource) return null
  
  const serialized = geojsonSource.serialize()
  return serialized.data
}

