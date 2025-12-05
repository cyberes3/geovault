/**
 * Feature management utilities for MapLibre
 */

import { filterPointsOnBorders } from './featureFiltering.js'
import { ensureLayersExist } from './layerManagement.js'

/**
 * Add features to the map, merging with existing features and filtering points on borders
 * @param {Object} map - MapLibre map instance
 * @param {Object} geojsonData - GeoJSON data to add
 * @param {boolean} showAllLabels - Whether to show labels
 */
export function addFeaturesToMap(map, geojsonData, showAllLabels = true) {
  if (!map || !map.getSource('geojson-data')) return

  const source = map.getSource('geojson-data')
  const currentData = source._data || { type: 'FeatureCollection', features: [] }
  const existingFeatures = new Map()
  
  // Index existing features by database_id
  if (currentData.features) {
    currentData.features.forEach(f => {
      const id = f.properties?.database_id
      if (id) existingFeatures.set(String(id), f)
    })
  }

  // Merge new features, avoiding duplicates
  const newFeatures = geojsonData.features || []
  newFeatures.forEach(f => {
    const id = f.properties?.database_id
    if (id && !existingFeatures.has(String(id))) {
      existingFeatures.set(String(id), f)
    }
  })

  // Filter out points that are on polygon/line borders
  const allFeatures = Array.from(existingFeatures.values())
  const filteredFeatures = filterPointsOnBorders(allFeatures)

  // Update source with filtered features
  source.setData({
    type: 'FeatureCollection',
    features: filteredFeatures
  })

  // Add layers if they don't exist
  ensureLayersExist(map, showAllLabels)
}

