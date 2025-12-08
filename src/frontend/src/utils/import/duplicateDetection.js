/**
 * Duplicate detection and marking utilities for import processing
 */

/**
 * Count total duplicates across all types
 * @param {Object} duplicates - Duplicates object
 * @param {Array} duplicates.featureStoreHash - Feature store hash duplicates
 * @param {Array} duplicates.featureStoreGeometry - Feature store geometry duplicates
 * @param {Array} duplicates.crossQueueHash - Cross-queue hash duplicates
 * @param {Array} duplicates.crossQueueGeometry - Cross-queue geometry duplicates
 * @returns {number} Total duplicate count
 */
export function calculateTotalDuplicateCount(duplicates) {
  const featureStoreHashDups = duplicates.featureStoreHash || [];
  const featureStoreGeometryDups = duplicates.featureStoreGeometry || [];
  const crossQueueHashDups = duplicates.crossQueueHash || [];
  const crossQueueGeometryDups = duplicates.crossQueueGeometry || [];

  // Use a Set to avoid counting the same feature twice
  // Each duplicate object has a 'hash' property we can use as a unique identifier
  const allFeatureHashes = new Set([
    ...featureStoreHashDups.map(d => d.hash),
    ...featureStoreGeometryDups.map(d => d.hash),
    ...crossQueueHashDups.map(d => d.hash),
    ...crossQueueGeometryDups.map(d => d.hash),
  ]);

  return allFeatureHashes.size;
}

/**
 * Mark duplicate features with appropriate flags
 * @param {Array} items - Array of feature items
 * @param {Object} duplicates - Duplicates object from backend
 */
export function markDuplicateFeatures(items, duplicates) {
  // Reset all duplicate flags
  items.forEach((item) => {
    item.isFeatureStoreHashDup = false;
    item.isFeatureStoreGeometryDup = false;
    item.isCrossQueueHashDup = false;
    item.isCrossQueueGeometryDup = false;
    item.duplicateInfo = {};
  });

  // Mark feature store hash duplicates
  (duplicates.featureStoreHash || []).forEach(dupInfo => {
    const pageIndex = dupInfo.page_index;
    if (pageIndex >= 0 && pageIndex < items.length) {
      items[pageIndex].isFeatureStoreHashDup = true;
      items[pageIndex].duplicateInfo = {
        source: 'feature_store',
        match_type: 'hash',
        feature_store_id: dupInfo.feature_store_id
      };
    }
  });

  // Mark feature store geometry duplicates
  (duplicates.featureStoreGeometry || []).forEach(dupInfo => {
    const pageIndex = dupInfo.page_index;
    if (pageIndex >= 0 && pageIndex < items.length) {
      items[pageIndex].isFeatureStoreGeometryDup = true;
      items[pageIndex].duplicateInfo = {
        source: 'feature_store',
        match_type: 'geometry',
        feature_store_id: dupInfo.feature_store_id
      };
    }
  });

  // Mark cross-queue hash duplicates
  (duplicates.crossQueueHash || []).forEach(dupInfo => {
    const pageIndex = dupInfo.page_index;
    if (pageIndex >= 0 && pageIndex < items.length) {
      items[pageIndex].isCrossQueueHashDup = true;
      items[pageIndex].duplicateInfo = {
        source: 'cross_queue',
        match_type: 'hash',
        hash: dupInfo.hash,
        global_index: dupInfo.global_index,
        queue_item_id: dupInfo.queue_item_id,
        queue_item_filename: dupInfo.queue_item_filename
      };
    }
  });

  // Mark cross-queue geometry duplicates
  (duplicates.crossQueueGeometry || []).forEach(dupInfo => {
    const pageIndex = dupInfo.page_index;
    if (pageIndex >= 0 && pageIndex < items.length) {
      items[pageIndex].isCrossQueueGeometryDup = true;
      items[pageIndex].duplicateInfo = {
        source: 'cross_queue',
        match_type: 'geometry',
        hash: dupInfo.hash,
        global_index: dupInfo.global_index,
        queue_item_id: dupInfo.queue_item_id,
        queue_item_filename: dupInfo.queue_item_filename
      };
    }
  });
}

/**
 * Check if an item is a duplicate (any type)
 * @param {Object} item - Feature item
 * @returns {boolean} True if item is a duplicate
 */
export function isItemDuplicate(item) {
  return !!(item && (
    item.isFeatureStoreHashDup || 
    item.isCrossQueueHashDup ||
    item.isFeatureStoreGeometryDup ||
    item.isCrossQueueGeometryDup
  ));
}

/**
 * Get feature ID from item (hash or index-based)
 * @param {Object} item - Feature item
 * @param {number} index - Page-local index
 * @param {number} currentPage - Current page number
 * @param {number} pageSize - Items per page
 * @returns {string} Unique feature ID
 */
export function getFeatureId(item, index, currentPage, pageSize) {
  if (item && item.properties && item.properties.geojson_hash) {
    return item.properties.geojson_hash;
  }
  // Use global index as fallback - this is unique per feature across all pages
  const globalIndex = (currentPage - 1) * pageSize + index;
  return `index_${globalIndex}`;
}

/**
 * Check if item is skipped
 * @param {Object} item - Feature item
 * @param {number} index - Page-local index
 * @param {Set} skippedFeatureIds - Set of skipped feature IDs
 * @param {number} currentPage - Current page number
 * @param {number} pageSize - Items per page
 * @returns {boolean} True if item is skipped
 */
export function isItemSkipped(item, index, skippedFeatureIds, currentPage, pageSize) {
  if (!item) {
    return false;
  }
  const featureId = getFeatureId(item, index, currentPage, pageSize);
  return skippedFeatureIds.has(featureId);
}

/**
 * Check if item is disabled (imported, duplicate, or skipped)
 * @param {Object} item - Feature item
 * @param {number} index - Page-local index
 * @param {boolean} isImported - Whether upload is imported
 * @param {boolean} isImporting - Whether import is in progress
 * @param {Set} skippedFeatureIds - Set of skipped feature IDs
 * @param {number} currentPage - Current page number
 * @param {number} pageSize - Items per page
 * @returns {boolean} True if item is disabled
 */
export function isItemDisabled(item, index, isImported, isImporting, skippedFeatureIds, currentPage, pageSize) {
  return isImported ||
         isItemDuplicate(item) ||
         isItemSkipped(item, index, skippedFeatureIds, currentPage, pageSize) ||
         isImporting;
}

