/**
 * Icon URL detection and validation utilities for import processing
 */

import { APIHOST } from '@/config.js';

/**
 * Common property names that might contain icon URLs
 */
export const ICON_PROPERTY_NAMES = [
  'icon',
  'icon-href',
  'iconUrl',
  'icon_url',
  'marker-icon',
  'marker-symbol',
  'symbol',
];

/**
 * Get icon URL from feature properties
 * @param {Object} feature - Feature object with properties
 * @param {boolean} resolve - Whether to resolve relative URLs to absolute
 * @returns {string|null} Icon URL if found, null otherwise
 */
export function getFeatureIconUrl(feature, resolve = true) {
  if (!feature || !feature.properties) {
    return null;
  }

  for (const propName of ICON_PROPERTY_NAMES) {
    if (feature.properties[propName] && typeof feature.properties[propName] === 'string') {
      const iconUrl = feature.properties[propName].trim();
      if (iconUrl) {
        return resolve ? resolveIconUrl(iconUrl) : iconUrl;
      }
    }
  }

  return null;
}

/**
 * Get raw (unresolved) icon URL from feature properties
 * @param {Object} feature - Feature object with properties
 * @returns {string|null} Raw icon URL if found, null otherwise
 */
export function getFeatureIconUrlRaw(feature) {
  return getFeatureIconUrl(feature, false);
}

/**
 * Resolve icon URL to absolute URL
 * @param {string} iconUrl - Icon URL (relative or absolute)
 * @returns {string} Absolute icon URL
 */
export function resolveIconUrl(iconUrl) {
  // If already absolute URL, return as is
  if (iconUrl.startsWith('http://') || iconUrl.startsWith('https://')) {
    return iconUrl;
  }

  // If relative URL starting with /api/, prepend APIHOST
  if (iconUrl.startsWith('/api/')) {
    return `${APIHOST}${iconUrl}`;
  }

  // Fallback: assume it's a relative path and prepend APIHOST
  return `${APIHOST}${iconUrl.startsWith('/') ? '' : '/'}${iconUrl}`;
}

/**
 * Check if feature has a custom icon
 * @param {Object} item - Feature item with properties
 * @returns {boolean} True if feature has custom icon
 */
export function hasCustomIcon(item) {
  if (!item || !item.properties) return false;
  
  for (const propName of ICON_PROPERTY_NAMES) {
    const iconValue = item.properties[propName];
    if (iconValue && typeof iconValue === 'string' && iconValue.trim() !== '') {
      return true;
    }
  }
  return false;
}

/**
 * Check if icon URL is a system icon (can be recolored)
 * @param {string} iconUrl - Icon URL
 * @returns {boolean} True if system icon
 */
export function isSystemIcon(iconUrl) {
  return iconUrl && iconUrl.includes('/api/icons/system/');
}

/**
 * Check if item has a non-recolorable icon (user icon or external URL)
 * @param {Object} item - Feature item
 * @returns {boolean} True if icon cannot be recolored
 */
export function hasNonRecolorableIcon(item) {
  if (!item || !item.properties) return false;
  const iconUrl = getFeatureIconUrl(item);
  if (!iconUrl) return false;
  
  // System icons can be recolored
  if (isSystemIcon(iconUrl)) return false;
  
  // Everything else (user icons, external URLs) can't be recolored
  return true;
}

/**
 * Handle icon loading error
 * @param {Event} event - Error event
 */
export function handleIconError(event) {
  if (event.target && event.target.parentElement) {
    event.target.parentElement.style.display = 'none';
  }
}

