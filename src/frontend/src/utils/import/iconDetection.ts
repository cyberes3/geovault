/**
 * Icon URL detection and validation utilities for import processing
 */

import { APIHOST } from '@/config';
import type { ImportFeatureItem } from '@/assets/js/types/import-types';

/** Common property names that might contain icon URLs. */
export const ICON_PROPERTY_NAMES = [
  'icon',
  'icon-href',
  'iconUrl',
  'icon_url',
  'marker-icon',
  'marker-symbol',
  'symbol',
] as const;

/** Get icon URL from feature properties. */
export function getFeatureIconUrl(feature: ImportFeatureItem | null | undefined, resolve = true): string | null {
  if (!feature?.properties) {
    return null;
  }

  for (const propName of ICON_PROPERTY_NAMES) {
    const value = feature.properties[propName];
    if (value && typeof value === 'string') {
      const iconUrl = value.trim();
      if (iconUrl) {
        return resolve ? resolveIconUrl(iconUrl) : iconUrl;
      }
    }
  }

  return null;
}

/** Get raw (unresolved) icon URL from feature properties. */
export function getFeatureIconUrlRaw(feature: ImportFeatureItem | null | undefined): string | null {
  return getFeatureIconUrl(feature, false);
}

/** Resolve icon URL to absolute URL. */
export function resolveIconUrl(iconUrl: string): string {
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

/** Check if feature has a custom icon. */
export function hasCustomIcon(item: ImportFeatureItem | null | undefined): boolean {
  if (!item?.properties) return false;

  for (const propName of ICON_PROPERTY_NAMES) {
    const iconValue = item.properties[propName];
    if (iconValue && typeof iconValue === 'string' && iconValue.trim() !== '') {
      return true;
    }
  }
  return false;
}

/** Check if icon URL is a system icon (can be recolored). */
export function isSystemIcon(iconUrl: string | null | undefined): boolean {
  return !!iconUrl && iconUrl.includes('/api/icons/system/');
}

/** Check if item has a non-recolorable icon (user icon or external URL). */
export function hasNonRecolorableIcon(item: ImportFeatureItem | null | undefined): boolean {
  if (!item?.properties) return false;
  const iconUrl = getFeatureIconUrl(item);
  if (!iconUrl) return false;

  // System icons can be recolored
  if (isSystemIcon(iconUrl)) return false;

  // Everything else (user icons, external URLs) can't be recolored
  return true;
}

/** Handle icon loading error. */
export function handleIconError(event: Event): void {
  const target = event.target as HTMLElement | null;
  if (target?.parentElement) {
    target.parentElement.style.display = 'none';
  }
}
