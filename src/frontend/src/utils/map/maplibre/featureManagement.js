/**
 * Feature management utilities for MapLibre
 */

import { markRaw } from 'vue'
import * as turf from '@turf/turf'
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

// Web Mercator constant (matches OpenLayers)
const WEB_MERCATOR_WORLD_SIZE = 156543.03392 // meters per pixel at zoom 0

/**
 * Calculate the screen size of a polygon in pixels using Turf.js for accuracy
 * @param {Object} geometry - GeoJSON geometry
 * @param {number} zoom - Current zoom level
 * @returns {Object} Object with width and height in pixels
 */
function calculatePolygonScreenSize(geometry, zoom) {
  if (!geometry || !geometry.coordinates) {
    return { widthPixels: 0, heightPixels: 0 }
  }

  try {
    // Use Turf.js to get bbox (returns [minLon, minLat, maxLon, maxLat])
    const bbox = turf.bbox(geometry)
    const [minLon, minLat, maxLon, maxLat] = bbox

    // Create points at the bbox corners to measure distances
    const bottomLeft = turf.point([minLon, minLat])
    const bottomRight = turf.point([maxLon, minLat])
    const topLeft = turf.point([minLon, maxLat])

    // Calculate geodesic distances in meters
    const widthMeters = turf.distance(bottomLeft, bottomRight, { units: 'meters' })
    const heightMeters = turf.distance(bottomLeft, topLeft, { units: 'meters' })

    // Convert to pixels using Web Mercator resolution
    const resolution = WEB_MERCATOR_WORLD_SIZE / Math.pow(2, zoom)
    const widthPixels = widthMeters / resolution
    const heightPixels = heightMeters / resolution

    return { widthPixels, heightPixels }
  } catch (e) {
    console.warn('Error calculating polygon screen size:', e)
    return { widthPixels: 0, heightPixels: 0 }
  }
}

/**
 * Calculate the screen size of a line in pixels using Turf.js for accuracy
 * @param {Object} geometry - GeoJSON geometry
 * @param {number} zoom - Current zoom level
 * @returns {number} Length in pixels
 */
function calculateLineScreenSize(geometry, zoom) {
  if (!geometry || !geometry.coordinates) {
    return 0
  }

  try {
    // Use Turf.js to calculate geodesic length in meters
    const lengthMeters = turf.length(geometry, { units: 'meters' })

    // Convert to pixels using Web Mercator resolution
    const resolution = WEB_MERCATOR_WORLD_SIZE / Math.pow(2, zoom)
    const lengthPixels = lengthMeters / resolution

    return lengthPixels
  } catch (e) {
    console.warn('Error calculating line screen size:', e)
    return 0
  }
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
  
  // Use serialize() method for MapLibre v5 compatibility
  const serialized = source.serialize()
  const currentData = serialized.data || { type: 'FeatureCollection', features: [] }
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
 * Extract elevation and timestamps from geometry coordinates and store in properties
 * MapLibre strips the 3rd coordinate (elevation) when storing internally,
 * and may not preserve coordinateProperties, so we need to preserve them
 * @param {Object} feature - GeoJSON feature
 * @returns {Object} Feature with elevation and timestamps in properties
 */
function preserveElevationInProperties(feature) {
  if (!feature || !feature.geometry) return feature
  
  const geometry = feature.geometry
  const coords = geometry.coordinates
  
  // Only process Point and MultiPoint features
  if (geometry.type === 'Point') {
    if (Array.isArray(coords) && coords.length >= 3 && coords[2] != null) {
      // Store elevation in properties
      if (!feature.properties) feature.properties = {}
      feature.properties._elevation = coords[2]
    }
  } else if (geometry.type === 'MultiPoint') {
    if (Array.isArray(coords) && coords.length > 0) {
      const firstPoint = coords[0]
      if (Array.isArray(firstPoint) && firstPoint.length >= 3 && firstPoint[2] != null) {
        // Store elevation from first point in properties
        if (!feature.properties) feature.properties = {}
        feature.properties._elevation = firstPoint[2]
      }
    }
  } else if (geometry.type === 'LineString') {
    // Store elevation values for elevation profile
    if (Array.isArray(coords) && coords.length > 0) {
      const elevations = coords
        .filter(coord => Array.isArray(coord) && coord.length >= 3 && coord[2] != null)
        .map(coord => coord[2])
      
      if (elevations.length > 0) {
        if (!feature.properties) feature.properties = {}
        // Store just the elevation values
        feature.properties._elevations = elevations
      }
    }
    
    // Preserve timestamps from coordinateProperties if they exist
    // This is needed for track statistics (speed, moving time, etc.)
    if (feature.properties?.coordinateProperties?.times) {
      if (!feature.properties._coordinateProperties) {
        feature.properties._coordinateProperties = {}
      }
      feature.properties._coordinateProperties.times = feature.properties.coordinateProperties.times
    }
  } else if (geometry.type === 'MultiLineString') {
    // Flatten and store all elevation values from all line segments
    if (Array.isArray(coords) && coords.length > 0) {
      const elevations = []
      
      coords.forEach(lineCoords => {
        if (Array.isArray(lineCoords)) {
          lineCoords.forEach(coord => {
            if (Array.isArray(coord) && coord.length >= 3 && coord[2] != null) {
              elevations.push(coord[2])
            }
          })
        }
      })
      
      if (elevations.length > 0) {
        if (!feature.properties) feature.properties = {}
        // Store just the elevation values
        feature.properties._elevations = elevations
      }
    }
    
    // Preserve timestamps from coordinateProperties if they exist
    // For MultiLineString, flatten the nested timestamp arrays
    if (feature.properties?.coordinateProperties?.times) {
      if (!feature.properties._coordinateProperties) {
        feature.properties._coordinateProperties = {}
      }
      feature.properties._coordinateProperties.times = feature.properties.coordinateProperties.times
    }
  }
  
  return feature
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
  const colorDetectionPromises = []
  const featuresNeedingColorDetection = []
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

    // If we need a replacement point, add both the original feature and replacement
    if (isSmallFeature && replacementCenter) {
      // Add the original feature
      processedFeatures.push(feature)
      
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

    // For non-small polygons/lines, just add the feature
    if (geometryType !== 'Point') {
      processedFeatures.push(feature)
      continue
    }

    // Normal icon processing for point features only
    const iconUrl = getFeatureIconUrl(feature.properties)
    const shouldUseIconImage = iconUrl && shouldUseIcon(zoom, iconUrl, replaceIconsLowZoom)

    if (shouldUseIconImage) {
      // Add icon metadata to feature
      const resolvedUrl = getIconSourceUrl(iconUrl, feature.properties)
      const iconId = `icon-${resolvedUrl.replace(/[^a-zA-Z0-9]/g, '_')}`
      
      // Store icon ID in feature properties for MapLibre expressions
      feature.properties['_icon-id'] = iconId
      
      // Load icon image
      iconLoadPromises.push(
        loadIconImage(map, iconId, resolvedUrl).catch(err => {
          console.warn(`Failed to load icon ${iconId}:`, err)
          // Remove icon metadata on failure
          delete feature.properties['_icon-id']
        })
      )
      
      // Add the point feature after icon processing
      processedFeatures.push(feature)
    } else if (iconUrl && !shouldUseIconImage) {
      // Icon exists but should be replaced with circle at low zoom
      // Detect primary color from icon image for the replacement circle
      const resolvedUrl = getIconSourceUrl(iconUrl, feature.properties)
      
      // Check if we already have a detected color stored
      if (feature.properties['_detectedIconColor']) {
        // Color already detected, safe to add feature
        delete feature.properties['_icon-id']
        processedFeatures.push(feature)
      } else {
        // Need to detect color - don't add feature yet, wait for detection
        // Check if detection is already in progress (from a previous call)
        if (!feature.properties['_colorDetectionInProgress']) {
          // Start new color detection
          feature.properties['_colorDetectionInProgress'] = true
          
          // Start color detection asynchronously
          const colorDetectionPromise = detectPrimaryColor(resolvedUrl)
            .then(color => {
              // Store detected color in feature properties
              feature.properties['_detectedIconColor'] = color
              feature.properties['_colorDetectionInProgress'] = false
              return feature
            })
            .catch(() => {
              // On error, use marker-color as fallback
              feature.properties['_detectedIconColor'] = feature.properties['marker-color'] || '#ff0000'
              feature.properties['_colorDetectionInProgress'] = false
              return feature
            })
          
          colorDetectionPromises.push(colorDetectionPromise)
        } else {
          // Color detection already in progress - create a promise that waits for it to complete
          // by polling the feature properties
          const colorDetectionPromise = new Promise((resolve) => {
            const checkInterval = setInterval(() => {
              if (feature.properties['_detectedIconColor'] && !feature.properties['_colorDetectionInProgress']) {
                clearInterval(checkInterval)
                resolve(feature)
              }
            }, 50) // Check every 50ms
            
            // Timeout after 5 seconds to prevent infinite waiting
            setTimeout(() => {
              clearInterval(checkInterval)
              // Use fallback color if detection takes too long
              if (!feature.properties['_detectedIconColor']) {
                feature.properties['_detectedIconColor'] = feature.properties['marker-color'] || '#ff0000'
                feature.properties['_colorDetectionInProgress'] = false
              }
              resolve(feature)
            }, 5000)
          })
          
          colorDetectionPromises.push(colorDetectionPromise)
        }
        
        featuresNeedingColorDetection.push(feature)
        
        // Remove icon metadata since we're using a circle
        delete feature.properties['_icon-id']
      }
    } else {
      // No icon, remove icon metadata
      delete feature.properties['_icon-id']
      // Add the point feature after icon processing
      processedFeatures.push(feature)
    }
  }

  // Wait for all icons to load and color detection to complete
  await Promise.all([...iconLoadPromises, ...colorDetectionPromises])
  
  // Add features that were waiting for color detection
  for (const feature of featuresNeedingColorDetection) {
    // Only add if color detection completed (should always be true after Promise.all)
    if (feature.properties['_detectedIconColor'] && !feature.properties['_colorDetectionInProgress']) {
      processedFeatures.push(feature)
    }
  }

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
  
  // Use serialize() method for MapLibre v5 compatibility
  const serialized = source.serialize()
  const currentData = serialized.data || { type: 'FeatureCollection', features: [] }
  
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
        // Preserve elevation in properties before adding to MapLibre
        preserveElevationInProperties(f)
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
            let placeLabelBelow = false
            
            if (geometry.type === 'Polygon' || geometry.type === 'MultiPolygon') {
              // Calculate centroid for polygon
              const centroid = calculatePolygonCentroid(geometry)
              
              // Check if label would intersect with polygon border
              // Use a fixed zoom level (10) for consistent positioning across all zoom levels
              // This ensures label positions are stable and don't move when zooming
              // Zoom 10 is the base zoom where features are at full size
              if (centroid && feature.properties?.name) {
                const fixedZoom = 10 // Use fixed zoom for consistent label placement
                const resolution = getResolutionFromZoom(fixedZoom)
                const strokeWidth = feature.properties['stroke-width'] || 2
                const shouldPlaceBelow = checkLabelBorderIntersection(
                  geometry,
                  centroid,
                  feature.properties.name,
                  resolution,
                  strokeWidth
                )
                
                if (shouldPlaceBelow) {
                  // Place label below polygon at bottom center
                  labelPoint = calculatePolygonBottomCenter(geometry)
                  placeLabelBelow = true
                } else {
                  // Place label at centroid
                  labelPoint = centroid
                }
              } else {
                // Fallback to centroid if name not available
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
                  _originalFeatureId: featureId,
                  _placeLabelBelow: placeLabelBelow
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

