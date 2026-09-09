/** Calculate importable feature count (non-hash-duplicates, non-skipped). */
export function calculateImportableCount(totalFeatures: number, hashDuplicateCount: number, skippedCount: number): number {
    return Math.max(0, totalFeatures - hashDuplicateCount - skippedCount);
}

/** Validate page number for jump-to-page. */
export function isValidPageNumber(pageNumber: number, totalPages: number): boolean {
    return pageNumber >= 1 && pageNumber <= totalPages;
}
