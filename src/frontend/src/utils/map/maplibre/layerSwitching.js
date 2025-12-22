import { ensureLayersExist, addFeaturesToMap } from './index.js'

/**
 * Restore GeoJSON features to the map after layer switch
 * @param {Object} map - MapLibre map instance
 * @param {Object} geojsonData - GeoJSON data to restore
 * @param {boolean} showAllLabels - Whether to show labels
 * @param {Object} labelMarkerManager - Label marker manager instance
 */
export async function restoreGeoJsonFeatures(map, geojsonData, showAllLabels, labelMarkerManager) {
  if (!geojsonData || !map) return

  if (!map.getSource('geojson-data')) {
    map.addSource('geojson-data', {
      type: 'geojson',
      data: {
        type: 'FeatureCollection',
        features: []
      }
    })
  }

  ensureLayersExist(map, showAllLabels)
  const zoom = map.getZoom()
  await addFeaturesToMap(map, geojsonData, showAllLabels, zoom)

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

