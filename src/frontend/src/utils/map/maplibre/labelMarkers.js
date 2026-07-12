/**
 * HTML marker management for MapLibre labels
 * Uses HTML markers instead of symbol layers for stable, non-shifting labels
 */

import maplibregl from 'maplibre-gl'
import * as turf from '@turf/turf'
import { calculatePolygonCentroid, calculateLineCenter } from './labelPlacement.js'

// Web Mercator constants
// Using the same formula as OpenLayers for consistency
const WEB_MERCATOR_WORLD_SIZE = 156543.03392 // meters per pixel at zoom 0 (equator)

// Maximum number of visible labels to prevent clutter
const MAX_VISIBLE_LABELS = 200

/**
 * Convert MapLibre zoom level to resolution (meters per pixel)
 * Uses Web Mercator projection formula (matches OpenLayers)
 * @param {number} zoom - MapLibre zoom level
 * @returns {number} Resolution in meters per pixel
 */
export function getResolutionFromZoom(zoom) {
  // Resolution = 156543.03392 / 2^zoom
  // This matches OpenLayers' resolution calculation
  return WEB_MERCATOR_WORLD_SIZE / Math.pow(2, zoom)
}

/**
 * Calculate distance from a point to a line segment using Turf.js
 * @param {Array<number>} point - Point [lon, lat]
 * @param {Array<number>} lineStart - Line segment start [lon, lat]
 * @param {Array<number>} lineEnd - Line segment end [lon, lat]
 * @returns {number} Distance in meters
 */
function distanceToLineSegment(point, lineStart, lineEnd) {
    const pointFeature = turf.point(point)
    const lineFeature = turf.lineString([lineStart, lineEnd])
    return turf.pointToLineDistance(pointFeature, lineFeature, { units: 'meters' })
}

/**
 * Calculate the length of a LineString or MultiLineString in meters using Turf.js
 * @param {Object} geometry - GeoJSON LineString or MultiLineString geometry
 * @returns {number} Length in meters
 */
function calculateLineLength(geometry) {
  if (!geometry || !geometry.coordinates) return 0
  return turf.length(geometry, { units: 'meters' })
}

/**
 * Check if a polygon label would intersect with the polygon's border
 * Based on OpenLayers implementation from textUtils.ts
 * @param {Object} geometry - GeoJSON Polygon or MultiPolygon geometry
 * @param {Array<number>} labelPosition - [lon, lat] coordinates where label will be placed
 * @param {string} text - Label text
 * @param {number} resolution - Map resolution (meters per pixel)
 * @param {number} strokeWidth - Stroke width in pixels (default: 2)
 * @returns {boolean} True if label would intersect with border
 */
export function checkLabelBorderIntersection(geometry, labelPosition, text, resolution, strokeWidth = 2) {
  if (!geometry || !labelPosition || resolution <= 0) {
    return false
  }

  const geometryType = geometry.type
  if (geometryType !== 'Polygon' && geometryType !== 'MultiPolygon') {
    return false
  }

  // Estimate text dimensions
  // Font is 12px, approximate character width is 7px, height is 12px
  const fontHeightPixels = 12
  const avgCharWidthPixels = 7
  const textWidthPixels = text.length * avgCharWidthPixels
  const textHeightPixels = fontHeightPixels

  // Convert to meters
  const textHeightMeters = textHeightPixels * resolution
  const textWidthMeters = textWidthPixels * resolution
  const strokeWidthMeters = strokeWidth * resolution

  // Calculate polygon extent
  const coords = geometryType === 'Polygon' 
    ? geometry.coordinates[0] // outer ring
    : geometry.coordinates[0][0] // first polygon's outer ring

  if (!coords || coords.length === 0) return false

  let minLon = Infinity, minLat = Infinity, maxLon = -Infinity, maxLat = -Infinity
  coords.forEach(([lon, lat]) => {
    minLon = Math.min(minLon, lon)
    minLat = Math.min(minLat, lat)
    maxLon = Math.max(maxLon, lon)
    maxLat = Math.max(maxLat, lat)
  })

  // Convert extent to meters
  const widthMeters = (maxLon - minLon) * 111320 * Math.cos(((minLat + maxLat) / 2 * Math.PI) / 180)
  const heightMeters = (maxLat - minLat) * 110540
  const minDimensionMeters = Math.min(widthMeters, heightMeters)

  // Check if polygon is too small to fit label without intersection
  // Label needs space: text height/2 on each side + stroke width
  const minRequiredDimension = textHeightMeters + (strokeWidthMeters * 2)

  if (minDimensionMeters < minRequiredDimension) {
    return true
  }

  // Get all exterior rings
  const exteriorRings = []
  if (geometryType === 'Polygon') {
    exteriorRings.push(geometry.coordinates[0])
  } else if (geometryType === 'MultiPolygon') {
    geometry.coordinates.forEach(polygon => {
      if (polygon && polygon.length > 0) {
        exteriorRings.push(polygon[0])
      }
    })
  }

  // Check distance from label position to nearest point on boundary
  let minDistanceToBoundary = Infinity
  for (const ring of exteriorRings) {
    for (let i = 0; i < ring.length - 1; i++) {
      const p1 = ring[i]
      const p2 = ring[i + 1]
      const distance = distanceToLineSegment(labelPosition, p1, p2)
      minDistanceToBoundary = Math.min(minDistanceToBoundary, distance)
    }
  }

  // If label position is too close to boundary, label would intersect
  // Use text width as the primary dimension (since text is horizontal)
  // Multiply by 0.35 to hide only when text would actually touch the border
  // This accounts for the fact that labels are centered, so we need width/2
  const labelRadius = (textWidthMeters * 0.35) + strokeWidthMeters
  const requiredDistance = labelRadius
  
  return minDistanceToBoundary < requiredDistance
}

/**
 * Calculate extent of a polygon geometry
 * @param {Object} geometry - GeoJSON geometry
 * @returns {Array<number>|null} Extent [minLon, minLat, maxLon, maxLat] or null
 */
function calculatePolygonExtent(geometry) {
  if (!geometry || !geometry.coordinates) return null
  
  let allCoords = []
  if (geometry.type === 'Polygon') {
    allCoords = geometry.coordinates[0] || []
  } else if (geometry.type === 'MultiPolygon') {
    geometry.coordinates.forEach(polygon => {
      if (polygon[0]) {
        allCoords = allCoords.concat(polygon[0])
      }
    })
  }
  
  if (allCoords.length === 0) return null
  
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
    return null
  }
  
  return [minLon, minLat, maxLon, maxLat]
}

/**
 * Check if a feature's label should be visible at the current zoom level
 * Based on feature size and zoom thresholds from OpenLayers implementation
 * @param {Object} feature - GeoJSON feature
 * @param {Array<number>} labelPosition - [lon, lat] coordinates where label will be placed
 * @param {number} zoom - Current zoom level
 * @returns {boolean} True if label should be visible
 */
function shouldShowLabel(feature, labelPosition, zoom) {
  if (!feature || !feature.geometry) return false

  const geometry = feature.geometry
  const resolution = getResolutionFromZoom(zoom)

  // For points, hide labels when zoomed out to county level or lower (zoom <= 8)
  if (geometry.type === 'Point') {
    return zoom > 8
  }

  // For polygons, check size and border intersection
  if (geometry.type === 'Polygon' || geometry.type === 'MultiPolygon') {
    const name = feature.properties?.name || ''
    if (!name || !labelPosition) return false
    
    // Calculate polygon screen size
    const extent = calculatePolygonExtent(geometry)
    if (!extent) return false
    
    const widthDegrees = extent[2] - extent[0]
    const heightDegrees = extent[3] - extent[1]
    
    // Convert to pixels
    const tileSize = 256
    const worldWidthPixels = tileSize * Math.pow(2, zoom)
    const pixelsPerDegree = worldWidthPixels / 360
    
    const widthPixels = widthDegrees * pixelsPerDegree
    const heightPixels = heightDegrees * pixelsPerDegree
    
    // Hide text for polygons < 50 pixels when zoomed out
    // Threshold is approx Zoom 13 (19.1 m/px)
    const minPolygonSizePixels = 50
    const maxResolutionForSmallPolygons = 19.1 // meters per pixel (approx Zoom 13)
    
    const minDimensionPixels = Math.min(widthPixels, heightPixels)
    if (minDimensionPixels < minPolygonSizePixels && resolution > maxResolutionForSmallPolygons) {
      return false // Hide text for small polygons (including dots) when zoomed out
    }
    
    // Check if label would intersect with polygon border
    // If it would, the label will be placed below the polygon (handled in feature processing)
    // So we return true here - don't hide the label
    const strokeWidth = feature.properties?.['stroke-width'] || 2
    const wouldIntersect = checkLabelBorderIntersection(geometry, labelPosition, name, resolution, strokeWidth)
    
    // Always return true for polygons that pass the size check
    // Border intersection is handled by placing label below polygon (via _placeLabelBelow flag)
    return true
  }

  // For lines, check if they're too small when zoomed out
  if (geometry.type === 'LineString' || geometry.type === 'MultiLineString') {
    const lengthMeters = calculateLineLength(geometry)
    const lengthPixels = lengthMeters / resolution

    // Hide text for lines < 50 pixels when zoomed out
    // Threshold is approx Zoom 13 (19.1 m/px)
    const minLineLengthPixels = 50
    const maxResolutionForSmallLines = 19.1 // meters per pixel (approx Zoom 13)

    if (lengthPixels < minLineLengthPixels && resolution > maxResolutionForSmallLines) {
      return false // Hide text for small lines when zoomed out
    }
  }

  return true
}

/**
 * Calculate point radius at a given zoom level (matches featureStyles.js and OpenLayers behavior)
 * Icons/points stay at full size at zoom 10+, only scale down when zoomed out
 * @param {number} zoom - Current zoom level
 * @param {boolean} hasIcon - Whether the point has an icon
 * @returns {number} Point radius in pixels
 */
function getPointRadiusAtZoom(zoom, hasIcon = false) {
  const baseZoom = 10
  const scaleFactor = 0.6
  
  // For points with icons, use icon size calculation
  if (hasIcon) {
    // Icon base scale is 1.0 at zoom 10+, min scale 0.5
    // Icons are normalized to 20x20px, so effective size is scale * 20
    const baseScale = 1.0
    const minScale = 0.5
    
    // At zoom 10 and above, icons stay at full size (OpenLayers behavior)
    if (zoom >= baseZoom) {
      return baseScale * 20 / 2 // Half of icon size for spacing calculation
    }
    
    // When zoomed out below zoom 10, apply exponential scaling
    const scale = Math.pow(2, (zoom - baseZoom) * scaleFactor)
    const clampedScale = Math.max(minScale, baseScale * scale)
    return clampedScale * 20 / 2
  }
  
  // For circles (no icon), use circle radius calculation
  // Base radius is 4px at zoom 10+, min radius 2px
  const baseRadius = 4
  const minRadius = 2
  
  // At zoom 10 and above, circles stay at full size (OpenLayers behavior)
  if (zoom >= baseZoom) {
    return baseRadius
  }
  
  // When zoomed out below zoom 10, apply exponential scaling
  const radiusMultiplier = Math.pow(2, (zoom - baseZoom) * scaleFactor)
  return Math.max(minRadius, baseRadius * radiusMultiplier)
}

/**
 * Truncate label text based on zoom level
 * @param {string} text - Full label text
 * @param {number} zoom - Current zoom level
 * @returns {string} Truncated or full text based on zoom
 */
function truncateLabelText(text, zoom) {
  if (!text) return text
  
  // Ensure zoom is a number
  const zoomLevel = typeof zoom === 'number' ? zoom : parseFloat(zoom) || 10
  
  // At zoom 10 and above, show full text
  if (zoomLevel >= 10) {
    return text
  }
  
  // At zoom 9, show up to 15 characters
  if (zoomLevel >= 9) {
    return text.length > 15 ? text.substring(0, 12) + '...' : text
  }
  
  // At zoom 8, show up to 10 characters
  if (zoomLevel >= 8) {
    return text.length > 10 ? text.substring(0, 7) + '...' : text
  }
  
  // At zoom 7 and below, show up to 8 characters
  return text.length > 8 ? text.substring(0, 5) + '...' : text
}

/**
 * Create an HTML element for a label marker
 * @param {string} text - Label text (will be truncated based on zoom)
 * @param {boolean} isLabelPoint - Whether this is a label point (polygon/line)
 * @param {number} zoom - Current zoom level
 * @param {boolean} hasIcon - Whether the point has an icon
 * @param {boolean} placeLabelBelow - Whether to place label below (for small polygons)
 * @returns {HTMLElement} HTML element for the marker
 */
function createLabelElement(text, isLabelPoint = false, zoom = 10, hasIcon = false, placeLabelBelow = false) {
  // Truncate text based on zoom level
  const displayText = truncateLabelText(text, zoom)
  // Create white text outline using multiple text-shadows
  const textOutline = `
    -1px -1px 0 #ffffff,
     1px -1px 0 #ffffff,
    -1px  1px 0 #ffffff,
     1px  1px 0 #ffffff,
    -1px  0   0 #ffffff,
     1px  0   0 #ffffff,
     0   -1px 0 #ffffff,
     0    1px 0 #ffffff
  `
  
  // Create label element
  const el = document.createElement('div')
  el.className = 'maplibre-label-marker'
  el.textContent = displayText
  
  // Calculate margin-top based on point size at current zoom
  let marginTop
  if (isLabelPoint && !placeLabelBelow) {
    // Regular label point (at centroid) - no offset
    marginTop = '0'
  } else if (isLabelPoint && placeLabelBelow) {
    // Label point placed below polygon - offset like points
    // Use 15px offset to match OpenLayers behavior for small polygons
    marginTop = '15px'
  } else {
    // Regular point - offset below based on icon/circle size
    // Get point radius/size at current zoom
    const pointSize = getPointRadiusAtZoom(zoom, hasIcon)
    // Add spacing: point size + 4px buffer
    const spacing = pointSize + 4
    marginTop = `${spacing}px`
  }
  
  el.style.cssText = `
    padding: 0;
    margin: 0;
    margin-top: ${marginTop};
    font-size: 12px;
    font-family: 'Noto Sans Regular', 'Arial Unicode MS Regular', sans-serif;
    color: #000000;
    white-space: nowrap;
    pointer-events: none;
    user-select: none;
    text-shadow: ${textOutline};
  `
  return el
}

/**
 * Get label position for a feature
 * @param {Object} feature - GeoJSON feature
 * @returns {Array<number>|null} [lon, lat] coordinates or null
 */
function getLabelPosition(feature) {
  if (!feature || !feature.geometry) return null

  const geometry = feature.geometry
  const name = feature.properties?.name

  if (!name || name.trim() === '') return null

  // For label points (polygons/lines), use the point coordinates
  if (feature.properties?._isLabelPoint && geometry.type === 'Point') {
    return geometry.coordinates
  }

  // For regular points, use point coordinates
  if (geometry.type === 'Point') {
    return geometry.coordinates
  }

  // Polygons and lines should ONLY use label points - never reach here
  // This function is only for getting positions from points and label points
  return null
}

/**
 * Label marker manager with performance optimizations
 */
export class LabelMarkerManager {
  constructor(map) {
    this.map = map
    this.markers = new Map() // Map of featureId -> { marker, isLabelPoint, text, fullText, position, hasIcon, zoom }
    this.showAllLabels = true
    this.updateTimeout = null // Debounce updates
    this.isUpdating = false // Prevent concurrent updates
    this.lastZoom = null // Track zoom changes
    this.lastUpdateTime = 0 // Track last update time for throttling
  }

  /**
   * Set label visibility
   * @param {boolean} show - Whether to show labels
   */
  setVisibility(show) {
    this.showAllLabels = show
    // Batch visibility updates using CSS
    const display = show ? 'block' : 'none'
    this.markers.forEach(({ marker }) => {
      const el = marker.getElement()
      if (el) el.style.display = display
    })
  }

  /**
   * Update markers based on features (with optional immediate mode for zoom)
   * @param {Array} features - Array of GeoJSON features
   * @param {boolean} immediate - If true, update immediately without debouncing (for zoom events)
   */
  updateMarkers(features, immediate = false) {
    const now = Date.now()
    const currentZoom = this.map ? this.map.getZoom() : null
    
    // If immediate update is requested (during zoom), update right away
    if (immediate) {
      // Cancel any pending debounced update
      if (this.updateTimeout) {
        clearTimeout(this.updateTimeout)
        this.updateTimeout = null
      }
      
      this.lastZoom = currentZoom
      this.performUpdate(features)
      return
    }
    
    // For non-immediate updates, use debouncing
    // Debounce updates to avoid excessive re-renders during pan
    if (this.updateTimeout) {
      clearTimeout(this.updateTimeout)
    }
    
    this.updateTimeout = setTimeout(() => {
      this.lastZoom = currentZoom
      this.performUpdate(features)
    }, 50) // Short debounce for normal updates
  }

  /**
   * Perform the actual marker update
   * @param {Array} features - Array of GeoJSON features
   */
  performUpdate(features) {
    if (!this.map || this.isUpdating) return
    
    // Use requestAnimationFrame for smooth rendering
    requestAnimationFrame(() => {
      this.isUpdating = true
      this.lastUpdateTime = Date.now()
      
      try {
        const zoom = this.map.getZoom()
        const bounds = this.map.getBounds()
        
        // Early exit if no features
        if (!features || features.length === 0) {
          this.clearAllMarkers()
          return
        }

        const featureMap = new Map()
        const labelPoints = new Map()

        // Separate regular features from label points
        for (const f of features) {
          if (f.properties?._isLabelPoint) {
            const originalId = f.properties?._originalFeatureId
            if (originalId) {
              labelPoints.set(String(originalId), f)
            }
          } else {
            const id = f.properties?.database_id
            if (id) {
              featureMap.set(String(id), f)
            }
          }
        }

        // Track which features should have markers
        const shouldHaveMarker = new Set()
        const candidateLabels = []

        // Process label points first (for polygons/lines)
        labelPoints.forEach((labelPoint, originalId) => {
          const position = getLabelPosition(labelPoint)
          if (!position) return
          
          // Quick viewport check
          if (position[0] < bounds.getWest() || position[0] > bounds.getEast() ||
              position[1] < bounds.getSouth() || position[1] > bounds.getNorth()) {
            return // Skip labels outside viewport
          }

          // Get the original feature to check its geometry for size filtering
          const originalFeature = featureMap.get(originalId)
          
          // Check if label should be visible based on zoom and feature size
          if (originalFeature && !shouldShowLabel(originalFeature, position, zoom)) {
            return // Skip this label - it would intersect with border or is too small
          }
          
          const name = labelPoint.properties?.name
          if (!name || name.trim() === '') return
          
          // Check if this label should be placed below the polygon
          const placeLabelBelow = labelPoint.properties?._placeLabelBelow || false
          
          candidateLabels.push({
            id: originalId,
            name,
            position,
            isLabelPoint: true,
            hasIcon: false, // Label points don't have icons
            placeLabelBelow: placeLabelBelow
          })
        })

        // Process regular features (points with names)
        featureMap.forEach((feature, id) => {
          // Skip if this feature already has a label point marker
          if (candidateLabels.some(l => l.id === id)) return
          
          // Skip polygons and lines - they should only use label points
          const geometryType = feature.geometry?.type
          if (geometryType === 'Polygon' || geometryType === 'MultiPolygon' ||
              geometryType === 'LineString' || geometryType === 'MultiLineString') {
            return
          }
          
          // Skip small feature replacement points - they shouldn't have labels
          if (feature.properties?._isSmallFeatureReplacement) {
            return
          }

          const position = getLabelPosition(feature)
          if (!position) return
          
          // Quick viewport check
          if (position[0] < bounds.getWest() || position[0] > bounds.getEast() ||
              position[1] < bounds.getSouth() || position[1] > bounds.getNorth()) {
            return // Skip labels outside viewport
          }

          // Check if label should be visible based on zoom and feature size
          if (!shouldShowLabel(feature, position, zoom)) {
            return // Skip this label - it's too small at this zoom level
          }
          
          const name = feature.properties?.name
          if (!name || name.trim() === '') return
          
          candidateLabels.push({
            id,
            name,
            position,
            isLabelPoint: false,
            hasIcon: !!feature.properties?.['_icon-id'], // Track if feature has an icon
            placeLabelBelow: false // Regular points don't use this flag
          })
        })

        // Limit to MAX_VISIBLE_LABELS and batch process
        const labelsToShow = candidateLabels.slice(0, MAX_VISIBLE_LABELS)

        // Apply collision detection to filter out overlapping labels
        const visibleLabels = this.detectLabelCollisions(labelsToShow, zoom)

        // Batch create/update markers
        visibleLabels.forEach(({ id, name, position, isLabelPoint, hasIcon, placeLabelBelow }) => {
          shouldHaveMarker.add(id)
          this.ensureMarker(id, name, position, isLabelPoint, hasIcon, zoom, placeLabelBelow)
        })
        
        // Update text truncation for existing markers when zoom changes
        this.updateLabelTexts(zoom)

        // Batch remove markers that are no longer needed
        const markersToRemove = []
        this.markers.forEach((data, featureId) => {
          if (!shouldHaveMarker.has(featureId)) {
            markersToRemove.push(featureId)
          }
        })

        // Remove in batch
        for (const featureId of markersToRemove) {
          this.removeMarker(featureId)
        }
      } finally {
        this.isUpdating = false
      }
    })
  }

  /**
   * Detect label collisions and return only non-overlapping labels
   * Uses screen-space bounding boxes to check for overlaps
   * @param {Array} labels - Array of label candidates with { id, name, position, isLabelPoint, hasIcon, placeLabelBelow }
   * @param {number} zoom - Current zoom level
   * @returns {Array} Filtered array of labels without collisions
   */
  detectLabelCollisions(labels, zoom) {
    if (!this.map || labels.length === 0) return labels

    // Estimate label dimensions in pixels
    // Font is 12px, approximate character width is 7px, height is 12px
    const fontHeightPixels = 12
    const avgCharWidthPixels = 7
    const paddingPixels = 4 // Padding around label to prevent tight overlaps

    // Convert labels to screen coordinates with bounding boxes
    const labelBoxes = labels.map(label => {
      const { name, position } = label
      const textWidthPixels = name.length * avgCharWidthPixels
      const textHeightPixels = fontHeightPixels
      
      // Convert geographic position to screen pixel coordinates
      // Handle terrain errors gracefully - terrain tiles may not be loaded yet
      let point
      try {
        point = this.map.project([position[0], position[1]])
      } catch (error) {
        // If terrain is not ready or coordinates are out of range, skip this label
        // This can happen when terrain tiles are still loading
        if (error.message && error.message.includes('DEM')) {
          return null // Skip this label
        }
        throw error // Re-throw other errors
      }
      
      if (!point) {
        return null // Skip if projection failed
      }
      
      // Calculate bounding box in screen space
      // Labels are positioned relative to the point based on their type
      let labelCenterY
      if (label.isLabelPoint && !label.placeLabelBelow) {
        // Centered label points - text is centered on the point
        labelCenterY = point.y
      } else if (label.isLabelPoint && label.placeLabelBelow) {
        // Label points placed below polygon - offset 15px below point
        labelCenterY = point.y + 15
      } else {
        // Regular points - offset below based on icon/circle size
        const pointSize = getPointRadiusAtZoom(zoom, label.hasIcon)
        labelCenterY = point.y + pointSize + 4
      }
      
      const x = point.x
      const y = labelCenterY
      
      // Bounding box: [left, top, right, bottom]
      // For centered labels, text is centered vertically on y
      // For offset labels, text starts at y (top anchor)
      const topOffset = label.isLabelPoint && !label.placeLabelBelow 
        ? textHeightPixels / 2 // Centered: half height above center
        : 0 // Offset: text starts at y
      
      const left = x - (textWidthPixels / 2) - paddingPixels
      const top = y - topOffset - paddingPixels
      const right = x + (textWidthPixels / 2) + paddingPixels
      const bottom = y - topOffset + textHeightPixels + paddingPixels
      
      return {
        ...label,
        bbox: [left, top, right, bottom],
        area: (right - left) * (bottom - top) // Larger labels have priority
      }
    }).filter(box => box !== null) // Filter out labels that failed to project

    // Sort by area (larger labels first) and then by position (top to bottom, left to right)
    // This ensures more important/visible labels are kept
    labelBoxes.sort((a, b) => {
      // First sort by area (descending)
      if (Math.abs(b.area - a.area) > 100) {
        return b.area - a.area
      }
      // Then by vertical position (top to bottom)
      if (Math.abs(a.bbox[1] - b.bbox[1]) > 5) {
        return a.bbox[1] - b.bbox[1]
      }
      // Finally by horizontal position (left to right)
      return a.bbox[0] - b.bbox[0]
    })

    // Check for collisions and keep only non-overlapping labels
    const visibleLabels = []
    const occupiedBoxes = []

    for (const labelBox of labelBoxes) {
      let hasCollision = false
      
      // Check against all previously placed labels
      for (const occupied of occupiedBoxes) {
        if (this.boxesOverlap(labelBox.bbox, occupied.bbox)) {
          hasCollision = true
          break
        }
      }
      
      if (!hasCollision) {
        visibleLabels.push({
          id: labelBox.id,
          name: labelBox.name,
          position: labelBox.position,
          isLabelPoint: labelBox.isLabelPoint,
          hasIcon: labelBox.hasIcon,
          placeLabelBelow: labelBox.placeLabelBelow
        })
        occupiedBoxes.push(labelBox)
      }
    }

    return visibleLabels
  }

  /**
   * Check if two bounding boxes overlap
   * @param {Array} bbox1 - [left, top, right, bottom]
   * @param {Array} bbox2 - [left, top, right, bottom]
   * @returns {boolean} True if boxes overlap
   */
  boxesOverlap(bbox1, bbox2) {
    const [left1, top1, right1, bottom1] = bbox1
    const [left2, top2, right2, bottom2] = bbox2
    
    // Check if boxes don't overlap (inverse check)
    // Boxes don't overlap if one is completely to the left, right, above, or below the other
    return !(right1 < left2 || left1 > right2 || bottom1 < top2 || top1 > bottom2)
  }

  /**
   * Clear all markers efficiently
   */
  clearAllMarkers() {
    this.markers.forEach(({ marker }) => marker.remove())
    this.markers.clear()
  }

  /**
   * Update label texts for all markers based on current zoom level
   * @param {number} zoom - Current zoom level
   */
  updateLabelTexts(zoom) {
    this.markers.forEach((data, featureId) => {
      const { marker, fullText } = data
      // If no fullText stored, try to get it from the current text content
      // This handles markers created before fullText was added
      const textToUse = fullText || (marker.getElement()?.textContent || '')
      if (!textToUse) return
      
      const displayText = truncateLabelText(textToUse, zoom)
      const el = marker.getElement()
      if (el && el.textContent !== displayText) {
        el.textContent = displayText
      }
      // Update stored zoom and fullText if not already stored
      data.zoom = zoom
      if (!data.fullText && textToUse) {
        data.fullText = textToUse
      }
    })
  }

  /**
   * Ensure a marker exists for a feature (optimized for performance)
   * @param {string} featureId - Feature ID
   * @param {string} text - Label text (full text)
   * @param {Array<number>} position - [lon, lat] coordinates
   * @param {boolean} isLabelPoint - Whether this is a label point (polygon/line) or regular point
   * @param {boolean} hasIcon - Whether the feature has an icon
   * @param {number} zoom - Current zoom level
   * @param {boolean} placeLabelBelow - Whether to place label below (for small polygons)
   */
  ensureMarker(featureId, text, position, isLabelPoint = false, hasIcon = false, zoom = 10, placeLabelBelow = false) {
    if (!this.map || !position || !text || text.trim() === '') return

    const existingMarker = this.markers.get(featureId)
    
    if (existingMarker) {
      const { marker, isLabelPoint: existingIsLabelPoint, fullText: existingFullText, position: existingPosition, hasIcon: existingHasIcon, zoom: existingZoom, placeLabelBelow: existingPlaceLabelBelow } = existingMarker
      
      // Check if anything actually changed to avoid unnecessary DOM updates
      const positionChanged = existingPosition[0] !== position[0] || existingPosition[1] !== position[1]
      const textChanged = existingFullText !== text
      const anchorChanged = existingIsLabelPoint !== isLabelPoint
      const iconChanged = existingHasIcon !== hasIcon
      const zoomChanged = Math.abs((existingZoom || 10) - zoom) > 0.5 // Only recreate if zoom changed significantly
      const placementChanged = existingPlaceLabelBelow !== placeLabelBelow
      
      // For label points, styling doesn't change with zoom (offset is fixed)
      // For regular points, styling changes with zoom (offset based on icon/circle size)
      // Only recreate marker if styling actually needs to change
      const needsRecreate = anchorChanged || iconChanged || placementChanged || 
        (!isLabelPoint && zoomChanged) // Only regular points need recreation on zoom change
      
      if (!positionChanged && !textChanged && !needsRecreate) {
        // Nothing changed, but still update text truncation if zoom changed
        if (zoomChanged) {
          const displayText = truncateLabelText(text, zoom)
          const el = marker.getElement()
          if (el && el.textContent !== displayText) {
            el.textContent = displayText
          }
        }
        // Always update stored data to ensure fullText and zoom are current
        this.markers.set(featureId, { marker, isLabelPoint, fullText: text || existingFullText, position: [...position], hasIcon, zoom, placeLabelBelow })
        return
      }
      
      // If anchor type, icon status, placement changed, or regular point zoom changed, recreate the marker
      if (needsRecreate) {
        marker.remove()
        const newEl = createLabelElement(text, isLabelPoint, zoom, hasIcon, placeLabelBelow)
        newEl.style.display = this.showAllLabels ? 'block' : 'none'
        
        // For label points placed below polygon, use 'top' anchor (like regular points)
        // For label points at centroid, use 'center' anchor
        // For regular points, use 'top' anchor
        const finalAnchor = (isLabelPoint && !placeLabelBelow) ? 'center' : 'top'
        
        const newMarker = new maplibregl.Marker({
          element: newEl,
          anchor: finalAnchor
        })
          .setLngLat(position)
          .addTo(this.map)
        this.markers.set(featureId, { marker: newMarker, isLabelPoint, fullText: text, position: [...position], hasIcon, zoom, placeLabelBelow })
        return
      }
      
      // Update position if changed
      if (positionChanged) {
        marker.setLngLat(position)
      }
      
      // Update text if changed (use truncated version for display)
      if (textChanged) {
        const displayText = truncateLabelText(text, zoom)
        const el = marker.getElement()
        if (el) el.textContent = displayText
      } else if (zoomChanged) {
        // Text didn't change but zoom did, update truncation
        const displayText = truncateLabelText(text, zoom)
        const el = marker.getElement()
        if (el) el.textContent = displayText
      }
      
      // Update stored data
      this.markers.set(featureId, { marker, isLabelPoint, fullText: text, position: [...position], hasIcon, zoom, placeLabelBelow })
      
      // Ensure visibility is correct
      const el = marker.getElement()
      if (el) el.style.display = this.showAllLabels ? 'block' : 'none'
    } else {
      // Create new marker
      const el = createLabelElement(text, isLabelPoint, zoom, hasIcon, placeLabelBelow)
      el.style.display = this.showAllLabels ? 'block' : 'none'
      
      // For label points placed below polygon, use 'top' anchor (like regular points)
      // For label points at centroid, use 'center' anchor
      // For regular points, use 'top' anchor
      const anchor = (isLabelPoint && !placeLabelBelow) ? 'center' : 'top'
      
      const marker = new maplibregl.Marker({
        element: el,
        anchor: anchor
      })
        .setLngLat(position)
        .addTo(this.map)

      this.markers.set(featureId, { marker, isLabelPoint, fullText: text, position: [...position], hasIcon, zoom, placeLabelBelow })
    }
  }

  /**
   * Remove a marker
   * @param {string} featureId - Feature ID
   */
  removeMarker(featureId) {
    const data = this.markers.get(featureId)
    if (data) {
      data.marker.remove()
      this.markers.delete(featureId)
    }
  }

  /**
   * Remove all markers
   */
  clear() {
    // Cancel any pending updates
    if (this.updateTimeout) {
      clearTimeout(this.updateTimeout)
      this.updateTimeout = null
    }
    this.clearAllMarkers()
  }

  /**
   * Update marker positions when map moves (for performance, only update visible markers)
   */
  updatePositions() {
    // Markers automatically update their positions when the map moves
    // This method can be used for any additional position updates if needed
  }
}
