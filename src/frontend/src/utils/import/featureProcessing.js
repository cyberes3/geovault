/**
 * Feature data processing and transformation utilities for import processing
 */

import { ICON_PROPERTY_NAMES } from './iconDetection.js';

/**
 * Check if feature geometry is a point type
 * @param {Object} item - Feature item
 * @returns {boolean} True if point geometry
 */
export function isPointGeometry(item) {
  if (!item || !item.geometry) return false;
  return item.geometry.type === 'Point' || item.geometry.type === 'MultiPoint';
}

/**
 * Check if feature geometry is a line type
 * @param {Object} item - Feature item
 * @returns {boolean} True if line geometry
 */
export function isLineGeometry(item) {
  if (!item || !item.geometry) return false;
  return item.geometry.type === 'LineString' || item.geometry.type === 'MultiLineString';
}

/**
 * Check if feature geometry is a polygon type
 * @param {Object} item - Feature item
 * @returns {boolean} True if polygon geometry
 */
export function isPolygonGeometry(item) {
  if (!item || !item.geometry) return false;
  return item.geometry.type === 'Polygon' || item.geometry.type === 'MultiPolygon';
}

/**
 * Initialize default style properties for feature
 * @param {Object} item - Feature item
 * @returns {Object} Feature item with initialized properties
 */
export function initializeFeatureDefaults(item) {
  if (!item.properties) {
    item.properties = {};
  }

  // Check if item has a custom icon
  const hasIcon = ICON_PROPERTY_NAMES.some(propName => {
    const iconValue = item.properties[propName];
    return iconValue && typeof iconValue === 'string' && iconValue.trim() !== '';
  });
  
  // Set default marker-color for points if not present and no custom icon
  if ((item.geometry.type === 'Point' || item.geometry.type === 'MultiPoint') && 
      !item.properties['marker-color'] && 
      !hasIcon) {
    item.properties['marker-color'] = '#ff0000';
  }
  
  // Set default stroke for lines and polygons if not present
  if ((item.geometry.type === 'LineString' || item.geometry.type === 'MultiLineString' ||
       item.geometry.type === 'Polygon' || item.geometry.type === 'MultiPolygon') && 
      !item.properties.stroke) {
    item.properties.stroke = '#ff0000';
  }

  return item;
}

/**
 * Handle stroke color change for polygons (update fill color)
 * @param {Object} item - Feature item
 * @returns {Object} Updated feature item
 */
export function handleStrokeColorChange(item) {
  // For polygons, automatically update fill color to match stroke with 10% opacity
  if (isPolygonGeometry(item) && item.properties.stroke) {
    item.properties.fill = item.properties.stroke;
    item.properties['fill-opacity'] = 0.1;
  }
  return item;
}

/**
 * Get CSS classes for feature item based on state
 * @param {Object} item - Feature item
 * @param {boolean} isHashDuplicate - Whether item is a hash duplicate (permanently blocked)
 * @param {boolean} isSkipped - Whether item is skipped
 * @returns {string} CSS classes
 */
export function getItemClasses(item, isHashDuplicate, isSkipped) {
  let classes = 'rounded-lg shadow-sm border p-6 relative';

  if (isHashDuplicate || isSkipped) {
    classes += ' bg-gray-100 border-gray-300';
  } else {
    classes += ' bg-white border-gray-200';
  }

  return classes;
}

/**
 * Format logging level name
 * @param {number} level - Log level number
 * @returns {string} Level name
 */
export function getLevelName(level) {
  const levelMap = {
    10: 'DEBUG',
    20: 'INFO',
    30: 'WARNING',
    40: 'ERROR',
    50: 'CRITICAL'
  };
  return levelMap[level] || 'UNKNOWN';
}

/**
 * Get CSS classes for log level
 * @param {number} level - Log level number
 * @returns {string} CSS classes
 */
export function getLevelClass(level) {
  if (level >= 40) { // ERROR or CRITICAL
    return 'bg-red-100 text-red-800';
  } else if (level >= 30) { // WARNING
    return 'bg-yellow-100 text-yellow-800';
  } else if (level >= 20) { // INFO
    return 'bg-blue-100 text-blue-700';
  } else { // DEBUG
    return 'bg-gray-100 text-gray-800';
  }
}

/**
 * Filter logs to show only relevant entries
 * @param {Array} logs - All logs
 * @param {number} maxLength - Maximum number of logs to show
 * @returns {Array} Filtered logs
 */
export function filterWorkerLog(logs, maxLength = 100) {
  // Return last N logs for performance
  return logs.slice(-maxLength);
}

