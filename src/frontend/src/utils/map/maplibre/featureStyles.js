/**
 * MapLibre feature styling configuration
 * Centralized styling for points, lines, and polygons
 * Uses data-driven styling to match OpenLayers behavior
 */

import {
  getPointColorExpression,
  getLineColorExpression,
  getPolygonFillColorExpression,
  getPolygonStrokeColorExpression,
  getStrokeWidthExpression,
  getFillOpacityExpression
} from './featureStyling.js'

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
  }
}

/**
 * Get point layer configuration (for circles, when no icon or at low zoom)
 * @param {Object} overrides - Style overrides
 * @returns {Object} Point layer configuration
 */
export function getPointLayerConfig(overrides = {}) {
  return {
    id: 'points',
    type: 'circle',
    source: 'geojson-data',
    filter: ['all', ['==', ['geometry-type'], 'Point'], ['!', ['has', '_on_border']]],
    paint: {
      ...defaultFeatureStyles.points.paint,
      ...overrides.paint
    }
  }
}

/**
 * Get point icon layer configuration (for icons at high zoom)
 * @param {Object} overrides - Style overrides
 * @returns {Object} Point icon layer configuration
 */
export function getPointIconLayerConfig(overrides = {}) {
  return {
    id: 'point-icons',
    type: 'symbol',
    source: 'geojson-data',
    filter: ['all', ['==', ['geometry-type'], 'Point'], ['!', ['has', '_on_border']]],
    layout: {
      'icon-image': [
        'coalesce',
        ['get', '_icon-id'],
        ''
      ],
      'icon-size': [
        'coalesce',
        ['get', '_icon-scale'],
        0.4
      ],
      'icon-anchor': 'bottom',
      'icon-allow-overlap': true,
      'icon-ignore-placement': true
    },
    ...overrides
  }
}

/**
 * Get line layer configuration
 * @param {Object} overrides - Style overrides
 * @returns {Object} Line layer configuration
 */
export function getLineLayerConfig(overrides = {}) {
  const lineFilter = ['any', ['==', ['geometry-type'], 'LineString'], ['==', ['geometry-type'], 'MultiLineString']]
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

/**
 * Get polygon fill layer configuration
 * @param {Object} overrides - Style overrides
 * @returns {Object} Polygon layer configuration
 */
export function getPolygonLayerConfig(overrides = {}) {
  const polygonFilter = ['any', ['==', ['geometry-type'], 'Polygon'], ['==', ['geometry-type'], 'MultiPolygon']]
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

/**
 * Get polygon outline layer configuration
 * @param {Object} overrides - Style overrides
 * @returns {Object} Polygon outline layer configuration
 */
export function getPolygonOutlineLayerConfig(overrides = {}) {
  const polygonFilter = ['any', ['==', ['geometry-type'], 'Polygon'], ['==', ['geometry-type'], 'MultiPolygon']]
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

/**
 * Get labels layer configuration
 * @param {boolean} showAllLabels - Whether to show labels
 * @param {Object} overrides - Style overrides
 * @returns {Object} Labels layer configuration
 */
export function getLabelsLayerConfig(showAllLabels = true, overrides = {}) {
  return {
    id: 'labels',
    type: 'symbol',
    source: 'geojson-data',
    layout: {
      ...defaultFeatureStyles.labels.layout,
      'visibility': showAllLabels ? 'visible' : 'none',
      ...overrides.layout
    },
    paint: {
      ...defaultFeatureStyles.labels.paint,
      ...overrides.paint
    },
    filter: ['!=', ['get', 'name'], '']
  }
}

