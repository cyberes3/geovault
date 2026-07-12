/**
 * Duplicate detection and marking utilities for import processing
 */

import type { ImportDuplicateSets, ImportFeatureItem } from '@/assets/js/types/import-types';

type DuplicateSets = Partial<Pick<ImportDuplicateSets, 'featureStoreHash' | 'featureStoreGeometry' | 'crossQueueHash' | 'crossQueueGeometry'>>;

/** Count total duplicates across all types. */
export function calculateTotalDuplicateCount(duplicates: DuplicateSets): number {
  const featureStoreHashDups = duplicates.featureStoreHash ?? [];
  const featureStoreGeometryDups = duplicates.featureStoreGeometry ?? [];
  const crossQueueHashDups = duplicates.crossQueueHash ?? [];
  const crossQueueGeometryDups = duplicates.crossQueueGeometry ?? [];

  // Use a Set to avoid counting the same feature twice.
  // Each duplicate object has a 'hash' property we can use as a unique identifier.
  const allFeatureHashes = new Set<string | undefined>([
    ...featureStoreHashDups.map((d) => d.hash),
    ...featureStoreGeometryDups.map((d) => d.hash),
    ...crossQueueHashDups.map((d) => d.hash),
    ...crossQueueGeometryDups.map((d) => d.hash),
  ]);

  return allFeatureHashes.size;
}

/** Count hash duplicates only (permanently blocked, cannot be restored). */
export function calculateHashDuplicateCount(duplicates: DuplicateSets): number {
  const featureStoreHashDups = duplicates.featureStoreHash ?? [];
  const crossQueueHashDups = duplicates.crossQueueHash ?? [];

  const hashDuplicateHashes = new Set<string | undefined>([
    ...featureStoreHashDups.map((d) => d.hash),
    ...crossQueueHashDups.map((d) => d.hash),
  ]);

  return hashDuplicateHashes.size;
}

/** Mark duplicate features with appropriate flags, based on the backend's duplicate lists for the current page. */
export function markDuplicateFeatures(items: ImportFeatureItem[], duplicates: DuplicateSets): void {
  // Reset all duplicate flags
  items.forEach((item) => {
    item.isFeatureStoreHashDup = false;
    item.isFeatureStoreGeometryDup = false;
    item.isCrossQueueHashDup = false;
    item.isCrossQueueGeometryDup = false;
    item.duplicateInfo = {};
  });

  (duplicates.featureStoreHash ?? []).forEach((dupInfo) => {
    const pageIndex = dupInfo.page_index;
    if (pageIndex >= 0 && pageIndex < items.length) {
      items[pageIndex].isFeatureStoreHashDup = true;
      items[pageIndex].duplicateInfo = {
        source: 'feature_store',
        match_type: 'hash',
        feature_store_id: dupInfo.feature_store_id,
      };
    }
  });

  (duplicates.featureStoreGeometry ?? []).forEach((dupInfo) => {
    const pageIndex = dupInfo.page_index;
    if (pageIndex >= 0 && pageIndex < items.length) {
      items[pageIndex].isFeatureStoreGeometryDup = true;
      items[pageIndex].duplicateInfo = {
        source: 'feature_store',
        match_type: 'geometry',
        feature_store_id: dupInfo.feature_store_id,
      };
    }
  });

  (duplicates.crossQueueHash ?? []).forEach((dupInfo) => {
    const pageIndex = dupInfo.page_index;
    if (pageIndex >= 0 && pageIndex < items.length) {
      items[pageIndex].isCrossQueueHashDup = true;
      items[pageIndex].duplicateInfo = {
        source: 'cross_queue',
        match_type: 'hash',
        hash: dupInfo.hash,
        global_index: dupInfo.global_index,
        queue_item_id: dupInfo.queue_item_id,
        queue_item_filename: dupInfo.queue_item_filename,
      };
    }
  });

  (duplicates.crossQueueGeometry ?? []).forEach((dupInfo) => {
    const pageIndex = dupInfo.page_index;
    if (pageIndex >= 0 && pageIndex < items.length) {
      items[pageIndex].isCrossQueueGeometryDup = true;
      items[pageIndex].duplicateInfo = {
        source: 'cross_queue',
        match_type: 'geometry',
        hash: dupInfo.hash,
        global_index: dupInfo.global_index,
        queue_item_id: dupInfo.queue_item_id,
        queue_item_filename: dupInfo.queue_item_filename,
      };
    }
  });
}

/** Check if an item is a duplicate (any type). */
export function isItemDuplicate(item: ImportFeatureItem | null | undefined): boolean {
  return !!(item && (
    item.isFeatureStoreHashDup ||
    item.isCrossQueueHashDup ||
    item.isFeatureStoreGeometryDup ||
    item.isCrossQueueGeometryDup
  ));
}

/** Check if an item is a hash duplicate (always blocked, cannot be restored). */
export function isItemHashDuplicate(item: ImportFeatureItem | null | undefined): boolean {
  return !!(item && (
    item.isFeatureStoreHashDup ||
    item.isCrossQueueHashDup
  ));
}

/** Get feature ID from item (hash or index-based). */
export function getFeatureId(item: ImportFeatureItem | null | undefined, index: number, currentPage: number, pageSize: number): string {
  if (item?.properties.geojson_hash) {
    return item.properties.geojson_hash;
  }
  // Use global index as fallback - this is unique per feature across all pages
  const globalIndex = (currentPage - 1) * pageSize + index;
  return `index_${globalIndex}`;
}

/** Check if item is skipped. */
export function isItemSkipped(item: ImportFeatureItem | null | undefined, index: number, skippedFeatureIds: Set<string>, currentPage: number, pageSize: number): boolean {
  if (!item) {
    return false;
  }
  const featureId = getFeatureId(item, index, currentPage, pageSize);
  return skippedFeatureIds.has(featureId);
}

/** Check if item is disabled (imported, hash duplicate, or skipped). */
export function isItemDisabled(
  item: ImportFeatureItem | null | undefined,
  index: number,
  isImported: boolean,
  isImporting: boolean,
  skippedFeatureIds: Set<string>,
  currentPage: number,
  pageSize: number,
): boolean {
  return isImported ||
    isItemHashDuplicate(item) ||
    isItemSkipped(item, index, skippedFeatureIds, currentPage, pageSize) ||
    isImporting;
}
