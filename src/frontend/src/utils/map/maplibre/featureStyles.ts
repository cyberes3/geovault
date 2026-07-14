/**
 * MapLibre feature styling configuration
 * Centralized styling for points, lines, and polygons
 * Uses data-driven styling to match OpenLayers behavior
 */

import type { MapLibreExpression } from './featureStyling.js'
import {
  getPointColorExpression,
  getLineColorExpression,
  getPolygonFillColorExpression,
  getPolygonStrokeColorExpression,
  getStrokeWidthExpression,
  getFillOpacityExpression
} from './featureStyling.js'

/** Layer paint/layout override bag, spread on top of `defaultFeatureStyles`. */
export interface FeatureLayerOverrides {
  layout?: Record<string, unknown>
  paint?: Record<string, unknown>
}

/**
 * Create zoom-based radius expression with exponential scaling.
 * Applies minimum size constraint for visibility at low zoom levels.
 * `baseZoom` (default 10) is the zoom level where full size is reached, `scaleFactor` (default 0.6) is the exponential scaling factor.
 */
export function createZoomBasedRadiusExpression(baseRadius: number, minRadius: number, baseZoom = 10, scaleFactor = 0.6): MapLibreExpression {
  const exponentialBase = Math.pow(2, scaleFactor) // 2^0.6 ≈ 1.516

  // Calculate zoom level where we hit minimum radius
  // Formula: minRadius = baseRadius * 2^((zoomMin - baseZoom) * scaleFactor)
  // Solving: zoomMin = baseZoom + log2(minRadius / baseRadius) / scaleFactor
  const zoomAtMin = baseZoom + Math.log2(minRadius / baseRadius) / scaleFactor

  return [
    'interpolate',
    ['exponential', exponentialBase],
    ['zoom'],
    zoomAtMin, minRadius,  // At calculated zoom: minimum size
    baseZoom, baseRadius,  // At base zoom: full size
    22, baseRadius         // At zoom 22: stays at full size
  ]
}

/**
 * Default feature styles with data-driven expressions
 */
export const defaultFeatureStyles = {
  points: {
    paint: {
      'circle-radius': 4,
      'circle-color': getPointColorExpression(), // Dynamic from marker-color property
      'circle-stroke-width': 0, // No border
      'circle-stroke-color': '#ffffff'
    }
  },
  lines: {
    layout: {
      'line-cap': 'round',
      'line-join': 'round'
    },
    paint: {
      'line-color': getLineColorExpression(), // Dynamic from stroke property
      'line-width': getStrokeWidthExpression(2), // Dynamic from stroke-width property
      'line-opacity': 1
    }
  },
  polygons: {
    paint: {
      'fill-color': getPolygonFillColorExpression(), // Dynamic from fill property
      'fill-opacity': getFillOpacityExpression() // Dynamic from fill-opacity property
    }
  },
  polygonOutlines: {
    layout: {
      'line-cap': 'round',
      'line-join': 'round'
    },
    paint: {
      'line-color': getPolygonStrokeColorExpression(), // Dynamic from stroke property
      'line-width': getStrokeWidthExpression(2), // Dynamic from stroke-width property
      'line-opacity': 1
    }
  },
  labels: {
    layout: {
      'text-field': ['coalesce', ['get', 'name'], ''],
      'text-font': ['Noto Sans Regular', 'Arial Unicode MS Regular'],
      'text-size': 12,
      'text-offset': [0, 1.25],
      'text-anchor': 'top',
      'text-allow-overlap': false,
      'text-ignore-placement': false
    },
    paint: {
      'text-color': '#000000',
      'text-halo-color': '#ffffff',
      'text-halo-width': 2
    }
  },
  labelPoints: {
    layout: {
      'text-field': ['coalesce', ['get', 'name'], ''],
      'text-font': ['Noto Sans Regular', 'Arial Unicode MS Regular'],
      'text-size': 12,
      'text-offset': [0, 1.25], // Offset below the point
      'text-anchor': 'top', // Anchor at top of text
      'text-allow-overlap': true, // Always show, even if overlapping
      'text-ignore-placement': true, // Don't move for other features
      'text-optional': false // Always show text, don't hide it
    },
    paint: {
      'text-color': '#000000',
      'text-halo-color': '#ffffff',
      'text-halo-width': 2
    }
  }
}

/** A MapLibre layer configuration object, as passed to `map.addLayer()`. */
export interface LayerConfig {
  id: string
  type: string
  source: string
  filter?: MapLibreExpression
  layout?: Record<string, unknown>
  paint?: Record<string, unknown>
}

/**
 * Get point layer configuration (for circles, when no icon or at low zoom).
 * For regular points only (not replacements).
 * Applies zoom-based exponential scaling matching OpenLayers behavior.
 */
export function getPointLayerConfig(overrides: FeatureLayerOverrides = {}): LayerConfig {
  return {
    id: 'points',
    type: 'circle',
    source: 'geojson-data',
    filter: ['all', 
      ['==', ['geometry-type'], 'Point'], 
      ['!', ['has', '_on_border']],
      ['!', ['has', '_isLabelPoint']], // Exclude label points
      ['!', ['has', '_isSmallFeatureReplacement']] // Exclude replacement points (separate layer)
    ],
    paint: {
      ...defaultFeatureStyles.points.paint,
      // Zoom-based radius: 2px minimum, 4px at zoom 10+
      'circle-radius': createZoomBasedRadiusExpression(4, 2),
      // Small black border for better visibility (for points that replace icons at low zoom)
      'circle-stroke-width': 1,
      'circle-stroke-color': '#000000',
      'circle-stroke-opacity': 1,
      ...overrides.paint
    }
  }
}

/**
 * Get replacement point layer configuration (for small polygons/lines).
 * Applies zoom-based exponential scaling matching OpenLayers behavior.
 */
export function getReplacementPointLayerConfig(overrides: FeatureLayerOverrides = {}): LayerConfig {
  return {
    id: 'replacement-points',
    type: 'circle',
    source: 'geojson-data',
    filter: ['all', 
      ['==', ['geometry-type'], 'Point'], 
      ['has', '_isSmallFeatureReplacement'] // Only replacement points
    ],
    paint: {
      // Zoom-based radius: 1.5px minimum, 3px at zoom 10+
      'circle-radius': createZoomBasedRadiusExpression(3, 1.5),
      // Dynamic color from marker-color property
      'circle-color': getPointColorExpression(),
      // Small black border for better visibility
      'circle-stroke-width': 1,
      'circle-stroke-color': '#000000',
      'circle-stroke-opacity': 1,
      ...overrides.paint
    }
  }
}

/**
 * Get point icon layer configuration (for icons at high zoom).
 * Applies zoom-based exponential scaling matching OpenLayers behavior.
 */
export function getPointIconLayerConfig(overrides: Partial<LayerConfig> = {}): LayerConfig {
  return {
    ...overrides,
    id: 'point-icons',
    type: 'symbol',
    source: 'geojson-data',
    filter: ['all', 
      ['==', ['geometry-type'], 'Point'], 
      ['!', ['has', '_on_border']],
      ['!', ['has', '_isLabelPoint']], // Exclude label points
      ['!', ['has', '_isSmallFeatureReplacement']], // Exclude replacement points (separate layer)
      ['has', '_icon-id'] // Only show features with icons
    ],
    layout: {
      'icon-image': [
        'coalesce',
        ['get', '_icon-id'],
        ''
      ],
      // Fixed size: 1.0 (no scaling, always 20px since icons are normalized to 20x20)
      'icon-size': 1.0,
      'icon-anchor': 'bottom',
      'icon-allow-overlap': true,
      'icon-ignore-placement': true
    }
  }
}

/** Get line layer configuration. */
export function getLineLayerConfig(overrides: FeatureLayerOverrides = {}): LayerConfig {
  // Filter out lines that are too small (they'll be shown as points instead)
  const lineFilter: MapLibreExpression = ['all',
    ['any', ['==', ['geometry-type'], 'LineString'], ['==', ['geometry-type'], 'MultiLineString']],
    ['!', ['has', '_isTooSmall']] // Hide lines that are too small
  ]
  return {
    id: 'lines',
    type: 'line',
    source: 'geojson-data',
    filter: lineFilter,
    layout: {
      ...defaultFeatureStyles.lines.layout,
      ...overrides.layout
    },
    paint: {
      ...defaultFeatureStyles.lines.paint,
      ...overrides.paint
    }
  }
}

/** Get polygon fill layer configuration. */
export function getPolygonLayerConfig(overrides: FeatureLayerOverrides = {}): LayerConfig {
  // Filter out polygons that are too small (they'll be shown as points instead)
  const polygonFilter: MapLibreExpression = ['all',
    ['any', ['==', ['geometry-type'], 'Polygon'], ['==', ['geometry-type'], 'MultiPolygon']],
    ['!', ['has', '_isTooSmall']] // Hide polygons that are too small
  ]
  return {
    id: 'polygons',
    type: 'fill',
    source: 'geojson-data',
    filter: polygonFilter,
    paint: {
      ...defaultFeatureStyles.polygons.paint,
      ...overrides.paint
    }
  }
}

/** Get polygon outline layer configuration. */
export function getPolygonOutlineLayerConfig(overrides: FeatureLayerOverrides = {}): LayerConfig {
  // Filter out polygon outlines that are too small (they'll be shown as points instead)
  const polygonFilter: MapLibreExpression = ['all',
    ['any', ['==', ['geometry-type'], 'Polygon'], ['==', ['geometry-type'], 'MultiPolygon']],
    ['!', ['has', '_isTooSmall']] // Hide polygon outlines that are too small
  ]
  return {
    id: 'polygon-outlines',
    type: 'line',
    source: 'geojson-data',
    filter: polygonFilter,
    layout: {
      ...defaultFeatureStyles.polygonOutlines.layout,
      ...overrides.layout
    },
    paint: {
      ...defaultFeatureStyles.polygonOutlines.paint,
      ...overrides.paint
    }
  }
}

/** Get labels layer configuration (for regular points with collision detection). */
export function getLabelsLayerConfig(showAllLabels = true, overrides: FeatureLayerOverrides = {}): LayerConfig {
  return {
    id: 'labels',
    type: 'symbol',
    source: 'geojson-data',
    layout: {
      ...defaultFeatureStyles.labels.layout,
      'text-offset': [
        'case',
        // For regular points, check if they have an icon
        ['all', ['==', ['geometry-type'], 'Point'], ['has', '_icon-id']],
        ['literal', [0, 1.25]], // Points with icons: offset below icon
        // For regular points without icons (circles), offset more
        ['==', ['geometry-type'], 'Point'],
        ['literal', [0, 1.75]], // Points without icons: offset below circle
        // Default for other types
        ['literal', [0, 1.25]]
      ],
      'visibility': showAllLabels ? 'visible' : 'none',
      ...overrides.layout
    },
    paint: {
      ...defaultFeatureStyles.labels.paint,
      ...overrides.paint
    },
    // Show labels for regular points (not label points)
    filter: ['all',
      ['!=', ['coalesce', ['get', 'name'], ''], ''],
      ['!', ['has', '_isLabelPoint']] // Exclude label points
    ]
  }
}

/** Get label points layer configuration (for polygons/lines - static positioning). */
export function getLabelPointsLayerConfig(showAllLabels = true, overrides: FeatureLayerOverrides = {}): LayerConfig {
  return {
    id: 'label-points',
    type: 'symbol',
    source: 'geojson-data',
    layout: {
      ...defaultFeatureStyles.labelPoints.layout,
      'visibility': showAllLabels ? 'visible' : 'none',
      ...overrides.layout
    },
    paint: {
      ...defaultFeatureStyles.labelPoints.paint,
      ...overrides.paint
    },
    // Show labels only for label points (polygons/lines)
    filter: ['all',
      ['has', '_isLabelPoint'],
      ['!=', ['coalesce', ['get', 'name'], ''], '']
    ]
  }
}

