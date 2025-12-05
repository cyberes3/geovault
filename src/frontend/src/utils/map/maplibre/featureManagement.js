/**
 * Feature management utilities for MapLibre
 */

import { markRaw } from 'vue'
import { filterPointsOnBorders } from './featureFiltering.js'
import { ensureLayersExist } from './layerManagement.js'
import {
  getFeatureIconUrl,
  getIconSourceUrl,
  shouldUseIcon,
  loadIconImage
} from './featureStyling.js'
import { detectPrimaryColor } from '@/utils/map/iconUtils'
import { calculatePolygonCentroid, calculateLineCenter } from './labelPlacement.js'

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

    // Skip label points - they don't need icon processing
    if (feature.properties._isLabelPoint) {
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
    } else if (iconUrl && !shouldUseIconImage) {
      // Icon exists but should be replaced with circle at low zoom
      // Detect primary color from icon image for the replacement circle
      const resolvedUrl = getIconSourceUrl(iconUrl, feature.properties)
      
      // Check if we already have a detected color stored
      if (!feature.properties['_detectedIconColor'] && !feature.properties['_colorDetectionInProgress']) {
        // Mark as in progress to avoid duplicate detection
        feature.properties['_colorDetectionInProgress'] = true
        
        // Start color detection asynchronously
        detectPrimaryColor(resolvedUrl)
          .then(color => {
            // Store detected color in feature properties
            feature.properties['_detectedIconColor'] = color
            feature.properties['_colorDetectionInProgress'] = false
            
            // Update the map source to trigger a re-render with the new color
            if (map && map.getSource('geojson-data')) {
              const source = map.getSource('geojson-data')
              const currentData = source._data || { type: 'FeatureCollection', features: [] }
              // Find and update this feature in the source data
              if (currentData.features) {
                const featureId = feature.properties?.database_id
                const existingFeature = currentData.features.find(f => 
                  f.properties?.database_id === featureId
                )
                if (existingFeature) {
                  existingFeature.properties['_detectedIconColor'] = color
                  existingFeature.properties['_colorDetectionInProgress'] = false
                  // Trigger update
                  source.setData(currentData)
                }
              }
            }
          })
          .catch(() => {
            // On error, use marker-color as fallback
            feature.properties['_detectedIconColor'] = feature.properties['marker-color'] || '#ff0000'
            feature.properties['_colorDetectionInProgress'] = false
          })
      }
      
      // Remove icon metadata since we're using a circle
      delete feature.properties['_icon-id']
      delete feature.properties['_icon-scale']
    } else {
      // No icon, remove icon metadata
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
  const existingLabelPoints = new Map() // Track existing label points
  
  // Separate existing features from label points
  if (currentData.features) {
    currentData.features.forEach(f => {
      if (f.properties?._isLabelPoint) {
        // Store label points separately, keyed by original feature ID
        const originalId = f.properties?._originalFeatureId
        if (originalId) {
          existingLabelPoints.set(String(originalId), f)
        }
      } else {
        // Regular features, indexed by database_id
        const id = f.properties?.database_id
        if (id) {
          existingFeatures.set(String(id), f)
        }
      }
    })
  }

  // Merge new features, avoiding duplicates
  const newFeatures = geojsonData.features || []
  const newFeatureIds = new Set()
  newFeatures.forEach(f => {
    const id = f.properties?.database_id
    if (id) {
      newFeatureIds.add(String(id))
      if (!existingFeatures.has(String(id))) {
        existingFeatures.set(String(id), f)
      }
    }
  })

  // Filter out points that are on polygon/line borders
  const allFeatures = Array.from(existingFeatures.values())
  const filteredFeatures = filterPointsOnBorders(allFeatures)

  // Add label points only if labels are visible
  // This significantly improves performance when labels are disabled
  // as we don't need to create extra Point features or calculate centroids/centers
  const featuresWithLabels = []
  const labelPointsToAdd = new Map()
  const labelPointsAdded = new Set() // Track which label points we've already added
  
  filteredFeatures.forEach(feature => {
    // Add the original feature
    featuresWithLabels.push(feature)
    
    // Only process label points if labels are visible
    // Skip expensive centroid/center calculations when labels are disabled
    if (showAllLabels) {
      // Check if this feature needs a label point
      const name = feature.properties?.name
      const featureId = feature.properties?.database_id
      const geometry = feature.geometry
      
      if (name && name.trim() !== '' && featureId && geometry) {
        const featureIdStr = String(featureId)
        
        // Check if we already have a label point for this feature
        const hasExistingLabelPoint = existingLabelPoints.has(featureIdStr)
        
        // Only create label point if:
        // 1. It's a polygon or line (not a point)
        // 2. We don't already have a label point for it
        // 3. Or it's a new feature (just added)
        const isNewFeature = newFeatureIds.has(featureIdStr)
        const needsLabelPoint = (geometry.type === 'Polygon' || geometry.type === 'MultiPolygon' || 
                                 geometry.type === 'LineString' || geometry.type === 'MultiLineString')
        
        if (needsLabelPoint && !labelPointsAdded.has(featureIdStr)) {
          if (hasExistingLabelPoint) {
            // ALWAYS preserve existing label point - never recalculate
            // This ensures labels stay at the exact same position
            const existingLabelPoint = existingLabelPoints.get(featureIdStr)
            // Make a deep copy to avoid any reference issues
            featuresWithLabels.push(JSON.parse(JSON.stringify(existingLabelPoint)))
            labelPointsAdded.add(featureIdStr)
          } else if (isNewFeature) {
            // Create a new label point for new features only
            let labelPoint = null
            
            if (geometry.type === 'Polygon' || geometry.type === 'MultiPolygon') {
              labelPoint = calculatePolygonCentroid(geometry)
            } else if (geometry.type === 'LineString' || geometry.type === 'MultiLineString') {
              labelPoint = calculateLineCenter(geometry)
            }
            
            if (labelPoint) {
              labelPointsToAdd.set(featureIdStr, {
                type: 'Feature',
                id: `label-point-${featureId}`, // Stable ID for MapLibre to track
                properties: {
                  ...feature.properties,
                  _isLabelPoint: true,
                  _originalFeatureId: featureId
                },
                geometry: {
                  type: 'Point',
                  coordinates: labelPoint
                }
              })
              labelPointsAdded.add(featureIdStr)
            }
          }
        }
      }
    }
  })
  
  // Add new label points (only if labels are visible)
  if (showAllLabels) {
    labelPointsToAdd.forEach(labelPoint => {
      featuresWithLabels.push(labelPoint)
    })
  }

  // Process features for icons if zoom is provided
  // Note: label points don't need icon processing, but processFeaturesForIcons will skip them
  let processedFeatures = featuresWithLabels
  if (zoom !== null) {
    processedFeatures = await processFeaturesForIcons(featuresWithLabels, map, zoom, replaceIconsLowZoom)
  }

  // Wrap each feature in markRaw to prevent Vue reactivity
  // This is critical for performance with complex geometries (many coordinates)
  const rawFeatures = processedFeatures.map(f => markRaw(f))

  // Update source with processed features
  // Also wrap the entire data object to prevent any Vue reactivity
  source.setData(markRaw({
    type: 'FeatureCollection',
    features: rawFeatures
  }))

  // Add layers if they don't exist
  ensureLayersExist(map, showAllLabels)
}

