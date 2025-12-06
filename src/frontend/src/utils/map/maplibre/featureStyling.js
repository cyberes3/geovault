/**
 * MapLibre feature styling utilities
 * Implements the same styling logic as OpenLayers map
 */

import { getIconUrl, resolveIconUrl, isSystemIcon, detectPrimaryColor } from '@/utils/map/iconUtils'
import { APIHOST } from '@/config'

/**
 * Get color from feature properties with fallback
 * Checks marker-color, stroke, fill in that order
 * @param {Object} properties - Feature properties
 * @param {string} defaultColor - Default color if none found
 * @returns {string} Hex color string
 */
function getColorFromProperties(properties, defaultColor = '#ff0000') {
  if (!properties) return defaultColor
  
  // Check marker-color first (for points)
  if (properties['marker-color'] && typeof properties['marker-color'] === 'string') {
    return properties['marker-color']
  }
  
  // Check stroke (for lines and polygon outlines)
  if (properties.stroke && typeof properties.stroke === 'string') {
    return properties.stroke
  }
  
  // Check fill (for polygons)
  if (properties.fill && typeof properties.fill === 'string') {
    return properties.fill
  }
  
  return defaultColor
}

/**
 * Convert hex color to RGBA with opacity
 * @param {string} hexColor - Hex color string (e.g., '#ff0000')
 * @param {number} opacity - Opacity value (0-1)
 * @returns {string} RGBA color string
 */
function hexToRgba(hexColor, opacity) {
  const hex = hexColor.replace('#', '')
  const r = parseInt(hex.slice(0, 2), 16)
  const g = parseInt(hex.slice(2, 4), 16)
  const b = parseInt(hex.slice(4, 6), 16)
  return `rgba(${r}, ${g}, ${b}, ${opacity})`
}

/**
 * Get MapLibre expression for dynamic color from feature properties
 * @param {string} propertyName - Property name to check (e.g., 'marker-color', 'stroke', 'fill')
 * @param {string} defaultColor - Default color if property not found
 * @returns {Array} MapLibre expression array
 */
export function getColorExpression(propertyName, defaultColor = '#ff0000') {
  return [
    'coalesce',
    ['get', propertyName],
    defaultColor
  ]
}

/**
 * Get MapLibre expression for point circle color
 * Checks _detectedIconColor (for replaced icons), then marker-color property, with fallback to red
 * @returns {Array} MapLibre expression array
 */
export function getPointColorExpression() {
  return [
    'coalesce',
    ['get', '_detectedIconColor'], // Use detected color from icon if available
    ['get', 'marker-color'],       // Fall back to marker-color property
    '#ff0000'                      // Default to red
  ]
}

/**
 * Get MapLibre expression for line stroke color
 * Checks stroke property with fallback to red
 * @returns {Array} MapLibre expression array
 */
export function getLineColorExpression() {
  return getColorExpression('stroke', '#ff0000')
}

/**
 * Get MapLibre expression for polygon fill color
 * Checks fill property with fallback to red
 * @returns {Array} MapLibre expression array
 */
export function getPolygonFillColorExpression() {
  return getColorExpression('fill', '#ff0000')
}

/**
 * Get MapLibre expression for polygon stroke color
 * Checks stroke property with fallback to red
 * @returns {Array} MapLibre expression array
 */
export function getPolygonStrokeColorExpression() {
  return getColorExpression('stroke', '#ff0000')
}

/**
 * Get MapLibre expression for stroke width
 * Checks stroke-width property with fallback
 * @param {number} defaultWidth - Default width if property not found
 * @returns {Array} MapLibre expression array
 */
export function getStrokeWidthExpression(defaultWidth = 2) {
  return [
    'coalesce',
    ['get', 'stroke-width'],
    defaultWidth
  ]
}

/**
 * Get MapLibre expression for fill opacity
 * Checks fill-opacity property and applies it to fill color
 * @returns {Array} MapLibre expression array for fill-opacity
 */
export function getFillOpacityExpression() {
  return [
    'coalesce',
    ['get', 'fill-opacity'],
    0.3
  ]
}

/**
 * Get icon URL from feature properties
 * @param {Object} properties - Feature properties
 * @returns {string|null} Icon URL or null
 */
export function getFeatureIconUrl(properties) {
  if (!properties) return null
  return getIconUrl(properties)
}

/**
 * Resolve icon URL to absolute URL
 * @param {string} iconUrl - Icon URL (relative or absolute)
 * @returns {string} Absolute icon URL
 */
export function resolveFeatureIconUrl(iconUrl) {
  return resolveIconUrl(iconUrl)
}

/**
 * Get icon source URL with recoloring support for system icons
 * @param {string} iconUrl - Icon URL
 * @param {Object} properties - Feature properties (for marker-color)
 * @returns {string} Resolved icon source URL
 */
export function getIconSourceUrl(iconUrl, properties) {
  const builtInIcon = isSystemIcon(iconUrl)
  const markerColor = properties?.['marker-color']
  
  if (builtInIcon && markerColor) {
    // Extract icon path relative to assets/icons/ for recolor endpoint
    const iconPathForRecolor = iconUrl.replace('/api/icons/system/', '')
    const encodedColor = encodeURIComponent(markerColor)
    const encodedIcon = encodeURIComponent(iconPathForRecolor)
    return `${APIHOST}/api/icons/recolor/?icon=${encodedIcon}&color=${encodedColor}`
  }
  
  return resolveIconUrl(iconUrl)
}

/**
 * Check if a feature should use an icon at current zoom level
 * At zoom level 8 or below, replace image-based icons (not system icons) with colored circles
 * @param {number} zoom - Current zoom level
 * @param {string} iconUrl - Icon URL
 * @param {boolean} replaceIconsLowZoom - Whether to replace icons at low zoom
 * @returns {boolean} True if should use icon, false if should use circle
 */
export function shouldUseIcon(zoom, iconUrl, replaceIconsLowZoom = true) {
  if (!iconUrl) return false
  
  const isLowZoom = zoom <= 8
  const isSystem = isSystemIcon(iconUrl)
  
  // Always use system icons, or use custom icons at high zoom
  if (isSystem || !isLowZoom || !replaceIconsLowZoom) {
    return true
  }
  
  // At low zoom with replaceIconsLowZoom enabled, use circle instead of icon
  return false
}

/**
 * Load icon image into MapLibre map
 * @param {Object} map - MapLibre map instance
 * @param {string} iconId - Unique identifier for the icon
 * @param {string} iconUrl - Icon URL
 * @returns {Promise<void>} Promise that resolves when icon is loaded
 */
export async function loadIconImage(map, iconId, iconUrl) {
  return new Promise((resolve, reject) => {
    // Check if image already exists
    if (map.hasImage(iconId)) {
      resolve()
      return
    }
    
    const img = new Image()
    img.crossOrigin = 'anonymous'
    
    img.onload = () => {
      try {
        // Double-check if image was added by another call while loading
        if (!map.hasImage(iconId)) {
          // Normalize icon to 32x32 pixels
          const canvas = document.createElement('canvas')
          canvas.width = 32
          canvas.height = 32
          const ctx = canvas.getContext('2d')
          
          // Draw the image scaled to 32x32
          ctx.drawImage(img, 0, 0, 32, 32)
          
          // Add the normalized image to the map
          map.addImage(iconId, canvas)
        }
        resolve()
      } catch (error) {
        // If error is about duplicate image, that's ok - another call added it
        if (error.message && error.message.includes('already exists')) {
          resolve()
        } else {
          reject(error)
        }
      }
    }
    
    img.onerror = () => {
      reject(new Error(`Failed to load icon: ${iconUrl}`))
    }
    
    img.src = iconUrl
  })
}

/**
 * Preload icon images for features
 * @param {Object} map - MapLibre map instance
 * @param {Array} features - Array of GeoJSON features
 * @returns {Promise<void>} Promise that resolves when all icons are loaded
 */
export async function preloadFeatureIcons(map, features) {
  if (!map || !features) return
  
  const iconPromises = []
  const loadedIcons = new Set()
  
  features.forEach(feature => {
    if (!feature.properties) return
    
    const iconUrl = getIconUrl(feature.properties)
    if (!iconUrl) return
    
    const resolvedUrl = getIconSourceUrl(iconUrl, feature.properties)
    const iconId = `icon-${resolvedUrl.replace(/[^a-zA-Z0-9]/g, '_')}`
    
    if (loadedIcons.has(iconId)) return
    loadedIcons.add(iconId)
    
    iconPromises.push(
      loadIconImage(map, iconId, resolvedUrl).catch(err => {
        console.warn(`Failed to load icon ${iconId}:`, err)
      })
    )
  })
  
  await Promise.all(iconPromises)
  return loadedIcons
}

/**
 * Get MapLibre expression for icon image
 * @param {string} iconProperty - Property name containing icon URL
 * @returns {Array} MapLibre expression array
 */
export function getIconImageExpression(iconProperty = 'icon') {
  return [
    'coalesce',
    ['get', iconProperty],
    ''
  ]
}

