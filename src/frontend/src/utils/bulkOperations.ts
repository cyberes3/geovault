// Shared helpers for bulk operations (tags + styling)
// Used by ImportProcess, TagsPage, and CollectionsPage.

export interface BulkOperations {
  tags: string[];
  pointColor: string | null;
  pointIcon: string | null;
  lineColor: string | null;
  polyColor: string | null;
  [key: string]: unknown;
}

/** Raw bulk operations as loaded from/saved to the backend; keys may be entirely absent. */
export type RawBulkOperations = Partial<BulkOperations> & Record<string, unknown>;

export const DEFAULT_BULK_OPERATIONS: BulkOperations = {
  tags: [],
  pointColor: null,
  pointIcon: null,
  lineColor: null,
  polyColor: null,
};

export function createEmptyBulkOperations(): BulkOperations {
  return cloneBulkOperations();
}

export function cloneBulkOperations(ops: RawBulkOperations | BulkOperations = {}): BulkOperations {
  return {
    tags: Array.isArray(ops.tags) ? [...ops.tags] : [],
    pointColor: ops.pointColor ?? null,
    pointIcon: ops.pointIcon ?? null,
    lineColor: ops.lineColor ?? null,
    polyColor: ops.polyColor ?? null,
  };
}

export function hasBulkOperationsConfigured(ops: RawBulkOperations | null | undefined): boolean {
  if (!ops) return false;

  if (Array.isArray(ops.tags) && ops.tags.length > 0) {
    return true;
  }

  if (ops.pointColor != null) return true;
  if (ops.pointIcon != null) return true;
  if (ops.lineColor != null) return true;
  if (ops.polyColor != null) return true;

  return false;
}

export function areBulkOperationsEqual(a: RawBulkOperations | null | undefined, b: RawBulkOperations | null | undefined): boolean {
  // Check if keys exist in original dicts (before normalization)
  // This distinguishes between "key not set" and "key set to null"
  const aHasPointIcon = a && 'pointIcon' in a;
  const bHasPointIcon = b && 'pointIcon' in b;
  if (aHasPointIcon !== bHasPointIcon) return false;

  const aHasPointColor = a && 'pointColor' in a;
  const bHasPointColor = b && 'pointColor' in b;
  if (aHasPointColor !== bHasPointColor) return false;

  const aHasLineColor = a && 'lineColor' in a;
  const bHasLineColor = b && 'lineColor' in b;
  if (aHasLineColor !== bHasLineColor) return false;

  const aHasPolyColor = a && 'polyColor' in a;
  const bHasPolyColor = b && 'polyColor' in b;
  if (aHasPolyColor !== bHasPolyColor) return false;

  // Now compare normalized values
  const left = cloneBulkOperations(a ?? {});
  const right = cloneBulkOperations(b ?? {});

  const leftTags = left.tags.slice().sort();
  const rightTags = right.tags.slice().sort();
  if (JSON.stringify(leftTags) !== JSON.stringify(rightTags)) {
    return false;
  }

  if (left.pointColor !== right.pointColor) return false;
  if (left.pointIcon !== right.pointIcon) return false;
  if (left.lineColor !== right.lineColor) return false;
  if (left.polyColor !== right.polyColor) return false;

  return true;
}
