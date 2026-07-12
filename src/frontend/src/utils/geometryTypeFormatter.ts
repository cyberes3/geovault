/**
 * Format geometry types for user-facing display.
 * Converts technical geometry type names to user-friendly names.
 */
export function formatGeometryTypeForDisplay(geometryType: string | null | undefined): string {
  if (!geometryType) return 'Unknown';

  // Convert LineString and MultiLineString to "Line" for user display
  if (geometryType === 'LineString' || geometryType === 'MultiLineString') {
    return 'Line';
  }

  // Return other types unchanged
  return geometryType;
}
