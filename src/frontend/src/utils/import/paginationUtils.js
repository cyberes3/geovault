/**
 * Pagination calculation utilities for import processing
 */

/**
 * Calculate adjusted total pages when hiding duplicates
 * @param {number} totalFeatures - Total number of features
 * @param {number} duplicateCount - Number of duplicate features
 * @param {number} pageSize - Items per page
 * @param {boolean} hideDuplicates - Whether duplicates are hidden
 * @param {number} originalTotalPages - Original total pages from backend
 * @returns {number} Adjusted total pages
 */
export function calculateAdjustedTotalPages(totalFeatures, duplicateCount, pageSize, hideDuplicates, originalTotalPages) {
  if (!hideDuplicates) {
    return originalTotalPages;
  }

  const nonDuplicateCount = totalFeatures - duplicateCount;
  return Math.max(1, Math.ceil(nonDuplicateCount / pageSize));
}

/**
 * Calculate adjusted "has next" when hiding duplicates
 * @param {number} currentPage - Current page number
 * @param {number} adjustedTotalPages - Adjusted total pages
 * @param {boolean} hideDuplicates - Whether duplicates are hidden
 * @param {boolean} originalHasNext - Original hasNext from backend
 * @returns {boolean} Whether there is a next page
 */
export function calculateAdjustedHasNext(currentPage, adjustedTotalPages, hideDuplicates, originalHasNext) {
  if (!hideDuplicates) {
    return originalHasNext;
  }
  return currentPage < adjustedTotalPages;
}

/**
 * Calculate adjusted "has previous" when hiding duplicates
 * @param {number} currentPage - Current page number
 * @param {boolean} hideDuplicates - Whether duplicates are hidden
 * @param {boolean} originalHasPrevious - Original hasPrevious from backend
 * @returns {boolean} Whether there is a previous page
 */
export function calculateAdjustedHasPrevious(currentPage, hideDuplicates, originalHasPrevious) {
  if (!hideDuplicates) {
    return originalHasPrevious;
  }
  return currentPage > 1;
}

/**
 * Calculate importable feature count (non-hash-duplicates, non-skipped)
 * @param {number} totalFeatures - Total number of features
 * @param {number} hashDuplicateCount - Number of hash duplicate features (permanently blocked)
 * @param {Set} skippedFeatureIds - Set of skipped feature IDs (geometry duplicates that are skipped)
 * @returns {number} Importable feature count
 */
export function calculateImportableCount(totalFeatures, hashDuplicateCount, skippedFeatureIds) {
  // Only hash duplicates are permanently blocked
  // Geometry duplicates can be restored (removed from skippedFeatureIds)
  return totalFeatures - hashDuplicateCount - skippedFeatureIds.size;
}

/**
 * Validate page number for jump-to-page
 * @param {number} pageNumber - Page number to validate
 * @param {number} totalPages - Total number of pages
 * @returns {boolean} True if page number is valid
 */
export function isValidPageNumber(pageNumber, totalPages) {
  return pageNumber >= 1 && pageNumber <= totalPages;
}

