/**
 * HTML marker management for MapLibre labels
 * Uses HTML markers instead of symbol layers for stable, non-shifting labels
 */

import maplibregl from 'maplibre-gl'
import { calculatePolygonCentroid, calculateLineCenter } from './labelPlacement.js'

// Web Mercator constants
const EARTH_CIRCUMFERENCE = 40075016.686 // meters at equator

/**
 * Convert MapLibre zoom level to resolution (meters per pixel)
 * Uses Web Mercator projection formula
 * @param {number} zoom - MapLibre zoom level
 * @returns {number} Resolution in meters per pixel
 */
function getResolutionFromZoom(zoom) {
  // Resolution = Earth circumference / (tile size * 2^zoom)
  // Tile size in MapLibre is 512 pixels (at scale 1)
  return EARTH_CIRCUMFERENCE / (512 * Math.pow(2, zoom))
}

/**
 * Calculate distance from a point to a line segment
 * @param {Array<number>} point - Point [lon, lat]
 * @param {Array<number>} lineStart - Line segment start [lon, lat]
 * @param {Array<number>} lineEnd - Line segment end [lon, lat]
 * @returns {number} Distance in meters
 */
function distanceToLineSegment(point, lineStart, lineEnd) {
  const [px, py] = point
  const [x1, y1] = lineStart
  const [x2, y2] = lineEnd
  
  const dx = x2 - x1
  const dy = y2 - y1
  const lengthSquared = dx * dx + dy * dy

  if (lengthSquared === 0) {
    // Line segment is a point
    const dx2 = px - x1
    const dy2 = py - y1
    // Convert to meters
    const dxMeters = dx2 * 111320 * Math.cos((py * Math.PI) / 180)
    const dyMeters = dy2 * 110540
    return Math.sqrt(dxMeters * dxMeters + dyMeters * dyMeters)
  }

  // Calculate parameter t (position along line segment)
  const t = Math.max(0, Math.min(1,
    ((px - x1) * dx + (py - y1) * dy) / lengthSquared
  ))

  // Find closest point on line segment
  const closestX = x1 + t * dx
  const closestY = y1 + t * dy

  // Calculate distance in meters
  const dx2 = px - closestX
  const dy2 = py - closestY
  const dxMeters = dx2 * 111320 * Math.cos(((py + closestY) / 2 * Math.PI) / 180)
  const dyMeters = dy2 * 110540
  return Math.sqrt(dxMeters * dxMeters + dyMeters * dyMeters)
}

/**
 * Calculate the length of a LineString or MultiLineString in meters
 * Uses simple Euclidean distance in Web Mercator projection
 * @param {Object} geometry - GeoJSON LineString or MultiLineString geometry
 * @returns {number} Length in meters
 */
function calculateLineLength(geometry) {
  if (!geometry || !geometry.coordinates) return 0

  const calculateSegmentLength = (coords) => {
    let length = 0
    for (let i = 1; i < coords.length; i++) {
      const [lon1, lat1] = coords[i - 1]
      const [lon2, lat2] = coords[i]
      // Simple Euclidean distance in degrees, converted to meters
      // This is approximate but sufficient for label visibility decisions
      const dx = (lon2 - lon1) * 111320 * Math.cos((lat1 * Math.PI) / 180)
      const dy = (lat2 - lat1) * 110540
      length += Math.sqrt(dx * dx + dy * dy)
    }
    return length
  }

  if (geometry.type === 'LineString') {
    return calculateSegmentLength(geometry.coordinates)
  } else if (geometry.type === 'MultiLineString') {
    let totalLength = 0
    geometry.coordinates.forEach(coords => {
      totalLength += calculateSegmentLength(coords)
    })
    return totalLength
  }

  return 0
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
function checkLabelBorderIntersection(geometry, labelPosition, text, resolution, strokeWidth = 2) {
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

  // For polygons, check if label would intersect with border
  if (geometry.type === 'Polygon' || geometry.type === 'MultiPolygon') {
    const name = feature.properties?.name || ''
    if (!name || !labelPosition) return false
    
    // Get stroke width from feature properties (default 2)
    const strokeWidth = feature.properties?.['stroke-width'] || 2
    
    // Check if label would intersect with polygon border
    const wouldIntersect = checkLabelBorderIntersection(geometry, labelPosition, name, resolution, strokeWidth)
    return !wouldIntersect
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
 * Create an HTML element for a label marker
 * @param {string} text - Label text
 * @param {boolean} isLabelPoint - Whether this is a label point (polygon/line)
 * @returns {HTMLElement} HTML element for the marker
 */
function createLabelElement(text, isLabelPoint = false) {
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
  el.textContent = text
  
  el.style.cssText = `
    padding: 0;
    margin: 0;
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

  // For polygons, calculate centroid
  if (geometry.type === 'Polygon' || geometry.type === 'MultiPolygon') {
    return calculatePolygonCentroid(geometry)
  }

  // For lines, calculate center
  if (geometry.type === 'LineString' || geometry.type === 'MultiLineString') {
    return calculateLineCenter(geometry)
  }

  return null
}

/**
 * Label marker manager
 */
export class LabelMarkerManager {
  constructor(map) {
    this.map = map
    this.markers = new Map() // Map of featureId -> { marker, isLabelPoint }
    this.showAllLabels = true
  }

  /**
   * Set label visibility
   * @param {boolean} show - Whether to show labels
   */
  setVisibility(show) {
    this.showAllLabels = show
    // Update all existing markers
    this.markers.forEach(({ marker }) => {
      marker.getElement().style.display = show ? 'block' : 'none'
    })
  }

  /**
   * Update markers based on features
   * @param {Array} features - Array of GeoJSON features
   */
  updateMarkers(features) {
    if (!this.map) return

    const zoom = this.map.getZoom()
    const featureMap = new Map()
    const labelPoints = new Map()

    // Separate regular features from label points
    features.forEach(f => {
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
    })

    // Track which features should have markers
    const shouldHaveMarker = new Set()

    // Process label points first (for polygons/lines)
    labelPoints.forEach((labelPoint, originalId) => {
      const position = getLabelPosition(labelPoint)
      if (position) {
        // Get the original feature to check its geometry for size filtering
        const originalFeature = featureMap.get(originalId)
        
        // Check if label should be visible based on zoom and feature size
        if (originalFeature && !shouldShowLabel(originalFeature, position, zoom)) {
          // Skip this label - it would intersect with border or is too small
          return
        }
        
        shouldHaveMarker.add(originalId)
        const name = labelPoint.properties?.name || ''
        // Label points (polygons/lines) should be centered
        this.ensureMarker(originalId, name, position, true)
      }
    })

    // Process regular features (points with names)
    featureMap.forEach((feature, id) => {
      // Skip if this feature already has a label point marker
      if (shouldHaveMarker.has(id)) return

      const position = getLabelPosition(feature)
      if (position) {
        // Check if label should be visible based on zoom and feature size
        if (!shouldShowLabel(feature, position, zoom)) {
          // Skip this label - it's too small at this zoom level
          return
        }
        
        shouldHaveMarker.add(id)
        // Regular points should have label below (anchor: bottom)
        this.ensureMarker(id, feature.properties?.name || '', position, false)
      }
    })

    // Remove markers for features that no longer exist
    const markersToRemove = []
    this.markers.forEach((data, featureId) => {
      if (!shouldHaveMarker.has(featureId)) {
        markersToRemove.push(featureId)
      }
    })

    markersToRemove.forEach(featureId => {
      this.removeMarker(featureId)
    })
  }

  /**
   * Ensure a marker exists for a feature
   * @param {string} featureId - Feature ID
   * @param {string} text - Label text
   * @param {Array<number>} position - [lon, lat] coordinates
   * @param {boolean} isLabelPoint - Whether this is a label point (polygon/line) or regular point
   */
  ensureMarker(featureId, text, position, isLabelPoint = false) {
    if (!this.map || !position || !text || text.trim() === '') return

    if (this.markers.has(featureId)) {
      const { marker, isLabelPoint: existingIsLabelPoint } = this.markers.get(featureId)
      
      // If anchor type changed, recreate the marker
      if (existingIsLabelPoint !== isLabelPoint) {
        marker.remove()
        const newEl = createLabelElement(text, isLabelPoint)
        newEl.style.display = this.showAllLabels ? 'block' : 'none'
        
        const finalAnchor = isLabelPoint ? 'center' : 'bottom'
        
        const newMarker = new maplibregl.Marker({
          element: newEl,
          anchor: finalAnchor
        })
          .setLngLat(position)
          .addTo(this.map)
        this.markers.set(featureId, { marker: newMarker, isLabelPoint })
        return
      }
      
      // Update existing marker position and text
      marker.setLngLat(position)
      const el = marker.getElement()
      if (el.textContent !== text) {
        el.textContent = text
      }
      el.style.display = this.showAllLabels ? 'block' : 'none'
    } else {
      // Create new marker
      const el = createLabelElement(text, isLabelPoint)
      el.style.display = this.showAllLabels ? 'block' : 'none'
      
      // For label points (polygons/lines), use center anchor; for points, use bottom anchor
      const anchor = isLabelPoint ? 'center' : 'bottom'
      
      const marker = new maplibregl.Marker({
        element: el,
        anchor: anchor
      })
        .setLngLat(position)
        .addTo(this.map)

      this.markers.set(featureId, { marker, isLabelPoint })
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
    this.markers.forEach(({ marker }) => marker.remove())
    this.markers.clear()
  }

  /**
   * Update marker positions when map moves (for performance, only update visible markers)
   */
  updatePositions() {
    // Markers automatically update their positions when the map moves
    // This method can be used for any additional position updates if needed
  }
}
