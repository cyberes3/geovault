/**
 * Pagination calculation utilities for import processing
 */

/** Calculate adjusted total pages when hiding duplicates. */
export function calculateAdjustedTotalPages(totalFeatures: number, duplicateCount: number, pageSize: number, hideDuplicates: boolean, originalTotalPages: number): number {
  if (!hideDuplicates) {
    return originalTotalPages;
  }

  const nonDuplicateCount = totalFeatures - duplicateCount;
  return Math.max(1, Math.ceil(nonDuplicateCount / pageSize));
}

/** Calculate adjusted "has next" when hiding duplicates. */
export function calculateAdjustedHasNext(currentPage: number, adjustedTotalPages: number, hideDuplicates: boolean, originalHasNext: boolean): boolean {
  if (!hideDuplicates) {
    return originalHasNext;
  }
  return currentPage < adjustedTotalPages;
}

/** Calculate adjusted "has previous" when hiding duplicates. */
export function calculateAdjustedHasPrevious(currentPage: number, hideDuplicates: boolean, originalHasPrevious: boolean): boolean {
  if (!hideDuplicates) {
    return originalHasPrevious;
  }
  return currentPage > 1;
}

/** Calculate importable feature count (non-hash-duplicates, non-skipped). */
export function calculateImportableCount(totalFeatures: number, hashDuplicateCount: number, skippedFeatureIds: Set<string>): number {
  // Only hash duplicates are permanently blocked
  // Geometry duplicates can be restored (removed from skippedFeatureIds)
  return totalFeatures - hashDuplicateCount - skippedFeatureIds.size;
}

/** Validate page number for jump-to-page. */
export function isValidPageNumber(pageNumber: number, totalPages: number): boolean {
  return pageNumber >= 1 && pageNumber <= totalPages;
}
