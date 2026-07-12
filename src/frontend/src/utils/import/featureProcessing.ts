/**
 * Feature data processing and transformation utilities for import processing
 */

import { ICON_PROPERTY_NAMES } from './iconDetection';
import type { ImportFeatureItem } from '@/assets/js/types/import-types';

/** Check if feature geometry is a point type. */
export function isPointGeometry(item: ImportFeatureItem | null | undefined): boolean {
  if (!item?.geometry) return false;
  return item.geometry.type === 'Point' || item.geometry.type === 'MultiPoint';
}

/** Check if feature geometry is a line type. */
export function isLineGeometry(item: ImportFeatureItem | null | undefined): boolean {
  if (!item?.geometry) return false;
  return item.geometry.type === 'LineString' || item.geometry.type === 'MultiLineString';
}

/** Check if feature geometry is a polygon type. */
export function isPolygonGeometry(item: ImportFeatureItem | null | undefined): boolean {
  if (!item?.geometry) return false;
  return item.geometry.type === 'Polygon' || item.geometry.type === 'MultiPolygon';
}

/** Initialize default style properties for feature. */
export function initializeFeatureDefaults(item: ImportFeatureItem): ImportFeatureItem {
  // Check if item has a custom icon
  const hasIcon = ICON_PROPERTY_NAMES.some((propName) => {
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

/** Handle stroke color change for polygons (update fill color). */
export function handleStrokeColorChange(item: ImportFeatureItem): ImportFeatureItem {
  // For polygons, automatically update fill color to match stroke with 10% opacity
  if (isPolygonGeometry(item) && item.properties.stroke) {
    item.properties.fill = item.properties.stroke;
    item.properties['fill-opacity'] = 0.1;
  }
  return item;
}

/** Get CSS classes for feature item based on state. */
export function getItemClasses(_item: ImportFeatureItem, isHashDuplicate: boolean, isSkipped: boolean): string {
  let classes = 'rounded-lg shadow-sm border p-6 relative';

  if (isHashDuplicate || isSkipped) {
    classes += ' bg-gray-100 border-gray-300';
  } else {
    classes += ' bg-white border-gray-200';
  }

  return classes;
}

const LOG_LEVEL_NAMES: Record<number, string> = {
  10: 'DEBUG',
  20: 'INFO',
  30: 'WARNING',
  40: 'ERROR',
  50: 'CRITICAL',
};

/** Format logging level name. */
export function getLevelName(level: number): string {
  return LOG_LEVEL_NAMES[level] ?? 'UNKNOWN';
}

/** Get CSS classes for log level. */
export function getLevelClass(level: number): string {
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

/** Filter logs to show only the last N entries, for performance. */
export function filterWorkerLog<T>(logs: T[], maxLength = 100): T[] {
  return logs.slice(-maxLength);
}
