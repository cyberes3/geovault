/**
 * Feature management utilities for MapLibre
 */

import { filterPointsOnBorders } from './featureFiltering.js'
import { ensureLayersExist } from './layerManagement.js'
import {
  getFeatureIconUrl,
  getIconSourceUrl,
  shouldUseIcon,
  loadIconImage
} from './featureStyling.js'

/**
 * Process features to add icon metadata and prepare for rendering
 * @param {Array} features - Array of GeoJSON features
 * @param {Object} map - MapLibre map instance
 * @param {number} zoom - Current zoom level
 * @param {boolean} replaceIconsLowZoom - Whether to replace icons at low zoom
 * @returns {Promise<Array>} Processed features with icon metadata
 */
async function processFeaturesForIcons(features, map, zoom, replaceIconsLowZoom = true) {
  if (!map || !features) return features

  const processedFeatures = []
  const iconLoadPromises = []

  for (const feature of features) {
    if (!feature.properties) {
      processedFeatures.push(feature)
      continue
    }

    const iconUrl = getFeatureIconUrl(feature.properties)
    const shouldUseIconImage = iconUrl && shouldUseIcon(zoom, iconUrl, replaceIconsLowZoom)

    if (shouldUseIconImage) {
      // Add icon metadata to feature
      const resolvedUrl = getIconSourceUrl(iconUrl, feature.properties)
      const iconId = `icon-${resolvedUrl.replace(/[^a-zA-Z0-9]/g, '_')}`
      
      // Store icon ID and scale in feature properties for MapLibre expressions
      feature.properties['_icon-id'] = iconId
      feature.properties['_icon-scale'] = 0.4 // Default scale, can be adjusted
      
      // Load icon image
      iconLoadPromises.push(
        loadIconImage(map, iconId, resolvedUrl).catch(err => {
          console.warn(`Failed to load icon ${iconId}:`, err)
          // Remove icon metadata on failure
          delete feature.properties['_icon-id']
        })
      )
    } else {
      // Remove icon metadata if not using icon
      delete feature.properties['_icon-id']
      delete feature.properties['_icon-scale']
    }

    processedFeatures.push(feature)
  }

  // Wait for all icons to load
  await Promise.all(iconLoadPromises)

  return processedFeatures
}


/**
 * Add features to the map, merging with existing features and filtering points on borders
 * @param {Object} map - MapLibre map instance
 * @param {Object} geojsonData - GeoJSON data to add
 * @param {boolean} showAllLabels - Whether to show labels
 * @param {number} zoom - Current zoom level (for icon handling)
 * @param {boolean} replaceIconsLowZoom - Whether to replace icons at low zoom
 */
export async function addFeaturesToMap(map, geojsonData, showAllLabels = true, zoom = null, replaceIconsLowZoom = true) {
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

  // Process features for icons if zoom is provided
  let processedFeatures = filteredFeatures
  if (zoom !== null) {
    processedFeatures = await processFeaturesForIcons(filteredFeatures, map, zoom, replaceIconsLowZoom)
  }

  // Update source with processed features
  source.setData({
    type: 'FeatureCollection',
    features: processedFeatures
  })

  // Add layers if they don't exist
  ensureLayersExist(map, showAllLabels)
}

