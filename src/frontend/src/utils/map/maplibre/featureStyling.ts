/**
 * MapLibre feature styling utilities
 * Implements the same styling logic as OpenLayers map
 */

import type { Map as MapLibreMap } from 'maplibre-gl'
import { getIconUrl, resolveIconUrl, isSystemIcon } from '@/utils/map/iconUtils'
import { APIHOST } from '@/config'

/** A MapLibre style expression - a nested array whose first element is the operator name. */
export type MapLibreExpression = unknown[]

/** Get MapLibre expression for dynamic color from feature properties. */
export function getColorExpression(propertyName: string, defaultColor = '#ff0000'): MapLibreExpression {
  return [
    'coalesce',
    ['get', propertyName],
    defaultColor
  ]
}

/**
 * Get MapLibre expression for point circle color.
 * Checks _detectedIconColor (for replaced icons), then marker-color property, with fallback to red.
 */
export function getPointColorExpression(): MapLibreExpression {
  return [
    'coalesce',
    ['get', '_detectedIconColor'], // Use detected color from icon if available
    ['get', 'marker-color'],       // Fall back to marker-color property
    '#ff0000'                      // Default to red
  ]
}

/** Get MapLibre expression for line stroke color. Checks stroke property with fallback to red. */
export function getLineColorExpression(): MapLibreExpression {
  return getColorExpression('stroke', '#ff0000')
}

/** Get MapLibre expression for polygon fill color. Checks fill property with fallback to red. */
export function getPolygonFillColorExpression(): MapLibreExpression {
  return getColorExpression('fill', '#ff0000')
}

/** Get MapLibre expression for polygon stroke color. Checks stroke property with fallback to red. */
export function getPolygonStrokeColorExpression(): MapLibreExpression {
  return getColorExpression('stroke', '#ff0000')
}

/** Get MapLibre expression for stroke width. Checks stroke-width property with fallback. */
export function getStrokeWidthExpression(defaultWidth = 2): MapLibreExpression {
  return [
    'coalesce',
    ['get', 'stroke-width'],
    defaultWidth
  ]
}

/**
 * Get MapLibre expression for stroke width with hover/selected highlighting.
 * Makes lines thicker when hovered or selected. `highlightMultiplier` defaults to 1.5.
 */
export function getStrokeWidthExpressionWithHighlight(
  defaultWidth = 2,
  hoveredFeatureId: string | number | null = null,
  selectedFeatureId: string | number | null = null,
  highlightMultiplier = 1.5
): MapLibreExpression {
  const baseWidthExpression: MapLibreExpression = [
    'coalesce',
    ['get', 'stroke-width'],
    defaultWidth
  ]

  // If no hover or selection, return base expression
  if (!hoveredFeatureId && !selectedFeatureId) {
    return baseWidthExpression
  }

  // Build condition to check if feature is hovered or selected
  const conditions: MapLibreExpression[] = []
  if (hoveredFeatureId) {
    conditions.push(['==', ['get', 'database_id'], hoveredFeatureId])
  }
  if (selectedFeatureId) {
    conditions.push(['==', ['get', 'database_id'], selectedFeatureId])
  }

  const isHighlighted = conditions.length === 1
    ? conditions[0]
    : ['any', ...conditions]

  // Return expression that multiplies width by highlightMultiplier if highlighted
  return [
    'case',
    isHighlighted,
    ['*', baseWidthExpression, highlightMultiplier],
    baseWidthExpression
  ]
}

/**
 * Get MapLibre expression for circle radius with hover/selected highlighting.
 * Makes points larger when hovered or selected. `highlightMultiplier` defaults to 1.5.
 */
export function getCircleRadiusExpressionWithHighlight(
  baseRadiusExpression: MapLibreExpression,
  hoveredFeatureId: string | number | null = null,
  selectedFeatureId: string | number | null = null,
  highlightMultiplier = 1.5
): MapLibreExpression {
  // If no hover or selection, return base expression
  if (!hoveredFeatureId && !selectedFeatureId) {
    return baseRadiusExpression
  }

  // Build condition to check if feature is hovered or selected
  const conditions: MapLibreExpression[] = []
  if (hoveredFeatureId) {
    conditions.push(['==', ['get', 'database_id'], hoveredFeatureId])
  }
  if (selectedFeatureId) {
    conditions.push(['==', ['get', 'database_id'], selectedFeatureId])
  }

  const isHighlighted = conditions.length === 1
    ? conditions[0]
    : ['any', ...conditions]

  // MapLibre doesn't allow multiple interpolate expressions in the same expression tree
  // Instead, we need to modify the interpolate stops to include the highlighting logic
  // We'll multiply the output values conditionally within the interpolate stops
  if (baseRadiusExpression[0] === 'interpolate') {
    // baseRadiusExpression structure: ['interpolate', interpolation, input, ...stops]
    // stops are pairs of [input_value, output_value]
    const interpolation = baseRadiusExpression[1]
    const input = baseRadiusExpression[2]
    const stops = baseRadiusExpression.slice(3)

    // Create new stops where each output value is conditionally multiplied
    const highlightedStops: unknown[] = []
    for (let i = 0; i < stops.length; i += 2) {
      const inputValue = stops[i]
      const outputValue = stops[i + 1] as number

      // For each stop, use a case expression to multiply if highlighted
      highlightedStops.push(inputValue) // input value
      highlightedStops.push([
        'case',
        isHighlighted,
        outputValue * highlightMultiplier,
        outputValue
      ])
    }

    // Return modified interpolate expression with conditional stops
    return ['interpolate', interpolation, input, ...highlightedStops]
  }

  // Fallback: if not an interpolate expression, just multiply conditionally
  return [
    'case',
    isHighlighted,
    ['*', baseRadiusExpression, highlightMultiplier],
    baseRadiusExpression
  ]
}

/**
 * Get MapLibre expression for icon size with hover/selected highlighting.
 * Makes point icons larger when hovered or selected. `highlightMultiplier` defaults to 1.5.
 */
export function getIconSizeExpressionWithHighlight(
  baseSize: number | MapLibreExpression = 1.0,
  hoveredFeatureId: string | number | null = null,
  selectedFeatureId: string | number | null = null,
  highlightMultiplier = 1.5
): number | MapLibreExpression {
  // If no hover or selection, return base expression
  if (!hoveredFeatureId && !selectedFeatureId) {
    return baseSize
  }

  // Build condition to check if feature is hovered or selected
  const conditions: MapLibreExpression[] = []
  if (hoveredFeatureId) {
    conditions.push(['==', ['get', 'database_id'], hoveredFeatureId])
  }
  if (selectedFeatureId) {
    conditions.push(['==', ['get', 'database_id'], selectedFeatureId])
  }

  const isHighlighted = conditions.length === 1
    ? conditions[0]
    : ['any', ...conditions]

  // Return expression that multiplies size by highlightMultiplier if highlighted
  return [
    'case',
    isHighlighted,
    ['*', baseSize, highlightMultiplier],
    baseSize
  ]
}

/** Get MapLibre expression for fill opacity. Checks fill-opacity property and applies it to fill color. */
export function getFillOpacityExpression(): MapLibreExpression {
  return [
    'coalesce',
    ['get', 'fill-opacity'],
    0.3
  ]
}

/** Get icon URL from feature properties. */
export function getFeatureIconUrl(properties: Record<string, unknown> | null | undefined): string | null {
  if (!properties) return null
  return getIconUrl(properties)
}

/** Resolve icon URL to absolute URL. */
export function resolveFeatureIconUrl(iconUrl: string): string {
  return resolveIconUrl(iconUrl)
}

/** Get icon source URL with recoloring support for system icons. `properties` is used for marker-color. */
export function getIconSourceUrl(iconUrl: string, properties: Record<string, unknown> | null | undefined): string {
  const builtInIcon = isSystemIcon(iconUrl)
  const markerColor = properties?.['marker-color']

  if (builtInIcon && typeof markerColor === 'string' && markerColor) {
    // Extract icon path relative to assets/icons/ for recolor endpoint
    const iconPathForRecolor = iconUrl.replace('/api/icons/system/', '')
    const encodedColor = encodeURIComponent(markerColor)
    const encodedIcon = encodeURIComponent(iconPathForRecolor)
    return `${APIHOST}/api/icons/recolor/?icon=${encodedIcon}&color=${encodedColor}`
  }

  return resolveIconUrl(iconUrl)
}

/**
 * Check if a feature should use an icon at current zoom level.
 * At zoom level 8 or below, replace icons with colored circles when replaceIconsLowZoom is enabled.
 */
export function shouldUseIcon(zoom: number, iconUrl: string | null | undefined, replaceIconsLowZoom = true): boolean {
  if (!iconUrl) return false

  const isLowZoom = zoom <= 8

  // If replaceIconsLowZoom is disabled or we're at high zoom, always show icon
  if (!replaceIconsLowZoom || !isLowZoom) {
    return true
  }

  // At low zoom with replaceIconsLowZoom enabled, use circle instead of icon
  return false
}

/** Load icon image into MapLibre map. */
export async function loadIconImage(map: MapLibreMap, iconId: string, iconUrl: string): Promise<void> {
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
          // Normalize icon to 20x20 pixels
          const canvas = document.createElement('canvas')
          canvas.width = 20
          canvas.height = 20
          const ctx = canvas.getContext('2d')
          if (!ctx) {
            reject(new Error('Could not get canvas context'))
            return
          }

          // Draw the image scaled to 20x20
          ctx.drawImage(img, 0, 0, 20, 20)

          // Get image data for MapLibre
          const imageData = ctx.getImageData(0, 0, 20, 20)

          // Add the normalized image to the map
          map.addImage(iconId, imageData)
        }
        resolve()
      } catch (error) {
        // If error is about duplicate image, that's ok - another call added it
        if (error instanceof Error && error.message.includes('already exists')) {
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

/** Preload icon images for features. Returns the set of icon ids that were queued for loading. */
export async function preloadFeatureIcons(map: MapLibreMap | null | undefined, features: { properties?: Record<string, unknown> | null }[] | null | undefined): Promise<Set<string> | undefined> {
  if (!map || !features) return

  const iconPromises: Promise<void>[] = []
  const loadedIcons = new Set<string>()

  features.forEach(feature => {
    if (!feature.properties) return

    const iconUrl = getIconUrl(feature.properties)
    if (!iconUrl) return

    const resolvedUrl = getIconSourceUrl(iconUrl, feature.properties)
    const iconId = `icon-${resolvedUrl.replace(/[^a-zA-Z0-9]/g, '_')}`

    if (loadedIcons.has(iconId)) return
    loadedIcons.add(iconId)

    iconPromises.push(
      loadIconImage(map, iconId, resolvedUrl).catch((err: unknown) => {
        console.warn(`Failed to load icon ${iconId}:`, err)
      })
    )
  })

  await Promise.all(iconPromises)
  return loadedIcons
}

/** Get MapLibre expression for icon image. */
export function getIconImageExpression(iconProperty = 'icon'): MapLibreExpression {
  return [
    'coalesce',
    ['get', iconProperty],
    ''
  ]
}
