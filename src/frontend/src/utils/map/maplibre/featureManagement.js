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
import { calculatePolygonCentroid, calculateLineCenter, calculatePolygonBottomCenter } from './labelPlacement.js'
import { checkLabelBorderIntersection, getResolutionFromZoom } from './labelMarkers.js'

/**
 * Calculate the screen size of a polygon in pixels
 * @param {Object} geometry - GeoJSON geometry
 * @param {number} zoom - Current zoom level
 * @returns {Object} Object with width and height in pixels
 */
function calculatePolygonScreenSize(geometry, zoom) {
  if (!geometry || !geometry.coordinates) {
    return { widthPixels: 0, heightPixels: 0 }
  }

  // Get all coordinates from the polygon
  let allCoords = []
  if (geometry.type === 'Polygon') {
    // For Polygon, coordinates[0] is the outer ring
    allCoords = geometry.coordinates[0] || []
  } else if (geometry.type === 'MultiPolygon') {
    // For MultiPolygon, get all outer rings
    geometry.coordinates.forEach(polygon => {
      if (polygon[0]) {
        allCoords = allCoords.concat(polygon[0])
      }
    })
  }

  if (allCoords.length === 0) {
    return { widthPixels: 0, heightPixels: 0 }
  }

  // Calculate extent in degrees
  let minLon = Infinity, minLat = Infinity, maxLon = -Infinity, maxLat = -Infinity
  allCoords.forEach(coord => {
    const [lon, lat] = coord
    if (isFinite(lon) && isFinite(lat)) {
      minLon = Math.min(minLon, lon)
      minLat = Math.min(minLat, lat)
      maxLon = Math.max(maxLon, lon)
      maxLat = Math.max(maxLat, lat)
    }
  })

  if (!isFinite(minLon) || !isFinite(minLat) || !isFinite(maxLon) || !isFinite(maxLat)) {
    return { widthPixels: 0, heightPixels: 0 }
  }

  const widthDegrees = maxLon - minLon
  const heightDegrees = maxLat - minLat

  // Convert degrees to pixels at the given zoom level
  // At zoom level 0, the world is 256 pixels wide (one tile)
  // Each zoom level doubles the pixel width
  // The world width in degrees is 360
  const tileSize = 256
  const worldWidthPixels = tileSize * Math.pow(2, zoom)
  const pixelsPerDegree = worldWidthPixels / 360

  const widthPixels = widthDegrees * pixelsPerDegree
  const heightPixels = heightDegrees * pixelsPerDegree

  return { widthPixels, heightPixels }
}

/**
 * Calculate the screen size of a line in pixels
 * @param {Object} geometry - GeoJSON geometry
 * @param {number} zoom - Current zoom level
 * @returns {number} Length in pixels
 */
function calculateLineScreenSize(geometry, zoom) {
  if (!geometry || !geometry.coordinates) {
    return 0
  }

  // Get all coordinates from the line
  let allCoords = []
  if (geometry.type === 'LineString') {
    allCoords = geometry.coordinates || []
  } else if (geometry.type === 'MultiLineString') {
    geometry.coordinates.forEach(line => {
      allCoords = allCoords.concat(line)
    })
  }

  if (allCoords.length < 2) {
    return 0
  }

  // Calculate total length by summing segments
  let totalLengthDegrees = 0
  for (let i = 0; i < allCoords.length - 1; i++) {
    const [lon1, lat1] = allCoords[i]
    const [lon2, lat2] = allCoords[i + 1]
    
    if (isFinite(lon1) && isFinite(lat1) && isFinite(lon2) && isFinite(lat2)) {
      // Simple Euclidean distance in degrees (good enough for screen size estimation)
      const dx = lon2 - lon1
      const dy = lat2 - lat1
      const segmentLength = Math.sqrt(dx * dx + dy * dy)
      totalLengthDegrees += segmentLength
    }
  }

  // Convert degrees to pixels at the given zoom level
  const tileSize = 256
  const worldWidthPixels = tileSize * Math.pow(2, zoom)
  const pixelsPerDegree = worldWidthPixels / 360

  return totalLengthDegrees * pixelsPerDegree
}

/**
 * Update small feature flags for all features at the current zoom level
 * This is more efficient than reprocessing all features
 * @param {Object} map - MapLibre map instance
 * @param {number} zoom - Current zoom level
 */
export function updateSmallFeatureFlags(map, zoom) {
  if (!map || !map.getSource('geojson-data') || zoom === null) return

  const source = map.getSource('geojson-data')
  const currentData = source._data || { type: 'FeatureCollection', features: [] }
  const features = currentData.features || []
  
  if (features.length === 0) return

  const MIN_PIXEL_SIZE = 2
  let needsUpdate = false
  const replacementPointsToAdd = []
  const featuresToKeep = []

  // First pass: update flags and collect features to keep
  for (const feature of features) {
    // Skip label points
    if (feature.properties?._isLabelPoint) {
      featuresToKeep.push(feature)
      continue
    }

    // Remove old replacement points (they'll be regenerated if needed)
    if (feature.properties?._isSmallFeatureReplacement) {
      needsUpdate = true
      continue
    }

    const geometry = feature.geometry
    const geometryType = geometry?.type

    // Check if polygon or line is too small
    let isSmallFeature = false
    let replacementCenter = null
    let replacementColor = null

    if (geometryType === 'Polygon' || geometryType === 'MultiPolygon') {
      const { widthPixels, heightPixels } = calculatePolygonScreenSize(geometry, zoom)
      if (widthPixels < MIN_PIXEL_SIZE || heightPixels < MIN_PIXEL_SIZE) {
        isSmallFeature = true
        replacementCenter = calculatePolygonCentroid(geometry)
        replacementColor = feature.properties.stroke || '#ff0000'
      }
    } else if (geometryType === 'LineString' || geometryType === 'MultiLineString') {
      const lengthPixels = calculateLineScreenSize(geometry, zoom)
      if (lengthPixels < MIN_PIXEL_SIZE) {
        isSmallFeature = true
        replacementCenter = calculateLineCenter(geometry)
        replacementColor = feature.properties.stroke || '#ff0000'
      }
    }

    // Update the flag
    const wasSmall = feature.properties?._isTooSmall === true
    if (isSmallFeature && !wasSmall) {
      feature.properties._isTooSmall = true
      needsUpdate = true
    } else if (!isSmallFeature && wasSmall) {
      delete feature.properties._isTooSmall
      needsUpdate = true
    }

    featuresToKeep.push(feature)

    // Create replacement point if needed
    if (isSmallFeature && replacementCenter) {
      replacementPointsToAdd.push({
        type: 'Feature',
        properties: {
          database_id: `${feature.properties.database_id}_small_replacement`,
          name: feature.properties.name,
          _isSmallFeatureReplacement: true,
          _originalFeatureId: feature.properties.database_id,
          _originalGeometryType: geometryType,
          'marker-color': replacementColor
        },
        geometry: {
          type: 'Point',
          coordinates: replacementCenter
        }
      })
    }
  }

  // Only update if something changed
  if (needsUpdate || replacementPointsToAdd.length > 0) {
    const allFeatures = [...featuresToKeep, ...replacementPointsToAdd]
    source.setData(markRaw({
      type: 'FeatureCollection',
      features: allFeatures.map(f => markRaw(f))
    }))
  }
}


/**
 * Process features to add icon metadata and prepare for rendering
 * Also handles small polygon/line replacement with colored dots
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
  const MIN_PIXEL_SIZE = 2 // Minimum size threshold in pixels

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

    const geometry = feature.geometry
    const geometryType = geometry?.type

    // Check if polygon or line is too small to render at current zoom
    // If so, mark it as small and add a replacement point alongside it
    let isSmallFeature = false
    let replacementCenter = null
    let replacementColor = null

    if (geometryType === 'Polygon' || geometryType === 'MultiPolygon') {
      const { widthPixels, heightPixels } = calculatePolygonScreenSize(geometry, zoom)
      
      // Mark as small if either dimension is less than 2 pixels
      if (widthPixels < MIN_PIXEL_SIZE || heightPixels < MIN_PIXEL_SIZE) {
        isSmallFeature = true
        replacementCenter = calculatePolygonCentroid(geometry)
        replacementColor = feature.properties.stroke || '#ff0000'
      }
    } else if (geometryType === 'LineString' || geometryType === 'MultiLineString') {
      const lengthPixels = calculateLineScreenSize(geometry, zoom)
      
      // Mark as small if line is less than 2 pixels
      if (lengthPixels < MIN_PIXEL_SIZE) {
        isSmallFeature = true
        replacementCenter = calculateLineCenter(geometry)
        replacementColor = feature.properties.stroke || '#ff0000'
      }
    }

    // Mark the feature if it's too small (will be filtered by layer filters)
    if (isSmallFeature) {
      feature.properties._isTooSmall = true
    } else {
      // Remove the flag when zoomed in enough
      delete feature.properties._isTooSmall
    }

    // Add the original feature
    processedFeatures.push(feature)

    // If we need a replacement point, create it alongside the original
    if (isSmallFeature && replacementCenter) {
      const replacementFeature = {
        type: 'Feature',
        properties: {
          database_id: `${feature.properties.database_id}_small_replacement`,
          name: feature.properties.name,
          _isSmallFeatureReplacement: true,
          _originalFeatureId: feature.properties.database_id,
          _originalGeometryType: geometryType,
          'marker-color': replacementColor
        },
        geometry: {
          type: 'Point',
          coordinates: replacementCenter
        }
      }
      
      // Add the replacement point (will be shown when original is filtered)
      processedFeatures.push(replacementFeature)
      continue
    }

    // Normal icon processing for point features
    if (geometryType === 'Point') {
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
  
  // Separate existing features from label points and replacement points
  if (currentData.features) {
    currentData.features.forEach(f => {
      if (f.properties?._isLabelPoint) {
        // Store label points separately, keyed by original feature ID
        const originalId = f.properties?._originalFeatureId
        if (originalId) {
          existingLabelPoints.set(String(originalId), f)
        }
      } else if (f.properties?._isSmallFeatureReplacement) {
        // Skip replacement points - they will be regenerated if needed
        return
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
              // First, calculate centroid for polygon
              const centroid = calculatePolygonCentroid(geometry)
              
              if (centroid && zoom !== null) {
                // Check if label at centroid would intersect with border
                const resolution = getResolutionFromZoom(zoom)
                const strokeWidth = feature.properties?.['stroke-width'] || 2
                const wouldIntersect = checkLabelBorderIntersection(
                  geometry,
                  centroid,
                  name,
                  resolution,
                  strokeWidth
                )
                
                if (wouldIntersect) {
                  // Place label below polygon instead
                  labelPoint = calculatePolygonBottomCenter(geometry)
                } else {
                  // Use centroid
                  labelPoint = centroid
                }
              } else {
                // Fallback to centroid if zoom not available
                labelPoint = centroid
              }
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

