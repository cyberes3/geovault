/**
 * Icon URL detection and validation for import processing (`ImportProcessPage.vue` /
 * `useImportFeatureEditing.ts`). Thin, `ImportFeatureItem`-typed wrappers around the canonical
 * icon-property/URL helpers in `@/utils/map/iconUtils`.
 */

import { ICON_PROPERTY_NAMES, getIconUrl, resolveIconUrl, isSystemIcon, hasCustomIcon as hasCustomIconOnProperties, handleIconError } from '@/utils/map/iconUtils';
import type { ImportFeatureItem } from '@/assets/js/types/import-types';

export { ICON_PROPERTY_NAMES, resolveIconUrl, isSystemIcon, handleIconError };

/** Get icon URL from an import feature item's properties, resolved to an absolute URL by default. */
export function getFeatureIconUrl(feature: ImportFeatureItem | null | undefined, resolve = true): string | null {
  const iconUrl = getIconUrl(feature?.properties);
  if (!iconUrl) return null;
  return resolve ? resolveIconUrl(iconUrl) : iconUrl;
}

/** Get raw (unresolved) icon URL from an import feature item's properties. */
export function getFeatureIconUrlRaw(feature: ImportFeatureItem | null | undefined): string | null {
  return getFeatureIconUrl(feature, false);
}

/** Check if an import feature item has a custom icon set. */
export function hasCustomIcon(item: ImportFeatureItem | null | undefined): boolean {
  return hasCustomIconOnProperties(item?.properties);
}

/** Check if an import feature item has a non-recolorable icon (user icon or external URL). */
export function hasNonRecolorableIcon(item: ImportFeatureItem | null | undefined): boolean {
  const iconUrl = getFeatureIconUrl(item);
  if (!iconUrl) return false;

  // System icons can be recolored; everything else (user icons, external URLs) can't be.
  return !isSystemIcon(iconUrl);
}
