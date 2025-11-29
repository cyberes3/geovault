// Shared helpers for bulk operations (tags + styling)
// Used by ImportProcess, TagsPage, and CollectionsPage.

export const DEFAULT_BULK_OPERATIONS = {
  tags: [],
  pointColor: null,
  pointIcon: null,
  lineColor: null,
  polyColor: null
};

export function createEmptyBulkOperations() {
  return cloneBulkOperations();
}

export function cloneBulkOperations(ops = {}) {
  return {
    tags: Array.isArray(ops.tags) ? [...ops.tags] : [],
    pointColor: ops.pointColor ?? null,
    pointIcon: ops.pointIcon ?? null,
    lineColor: ops.lineColor ?? null,
    polyColor: ops.polyColor ?? null
  };
}

export function hasBulkOperationsConfigured(ops) {
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

export function areBulkOperationsEqual(a, b) {
  const left = cloneBulkOperations(a);
  const right = cloneBulkOperations(b);

  const leftTags = (left.tags || []).slice().sort();
  const rightTags = (right.tags || []).slice().sort();
  if (JSON.stringify(leftTags) !== JSON.stringify(rightTags)) {
    return false;
  }

  if (left.pointColor !== right.pointColor) return false;
  if (left.pointIcon !== right.pointIcon) return false;
  if (left.lineColor !== right.lineColor) return false;
  if (left.polyColor !== right.polyColor) return false;

  return true;
}


