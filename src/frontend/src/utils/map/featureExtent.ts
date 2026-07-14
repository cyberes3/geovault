/**
 * Feature extent and filtering utilities
 */
import type { Geometry, Position } from 'geojson'
import type { LngLatBounds } from 'maplibre-gl'

export interface ExtentFeature {
  geometry?: Geometry | null
  properties?: { _isLabelPoint?: boolean; _isSmallFeatureReplacement?: boolean; [key: string]: unknown } | null
}

export interface BufferedBounds {
  west: number
  east: number
  south: number
  north: number
}

/** Get [lon, lat] coordinates from a GeoJSON geometry. */
export function getCoordinatesFromGeometry(geometry: Geometry | null | undefined): Position[] {
  if (!geometry) return [];

  switch (geometry.type) {
    case 'Point':
      return [geometry.coordinates];
    case 'MultiPoint':
    case 'LineString':
      return geometry.coordinates;
    case 'MultiLineString':
    case 'Polygon':
      return geometry.coordinates.flat();
    case 'MultiPolygon':
      return geometry.coordinates.flat(2);
    case 'GeometryCollection':
      return geometry.geometries.flatMap(g => getCoordinatesFromGeometry(g));
    default:
      return [];
  }
}

/** Check if feature is in bounds. */
export function isFeatureInBounds(feature: ExtentFeature | null | undefined, bounds: LngLatBounds | null | undefined): boolean {
  if (!feature?.geometry || !bounds) return false;

  const coords = getCoordinatesFromGeometry(feature.geometry);
  return coords.some(coord => {
    const [lon, lat] = coord;
    return lon >= bounds.getWest() && lon <= bounds.getEast() &&
           lat >= bounds.getSouth() && lat <= bounds.getNorth();
  });
}

/** Filter features by bounds. */
export function filterFeaturesByBounds<T extends ExtentFeature>(
  features: T[] | null | undefined,
  bounds: LngLatBounds | null | undefined,
  excludeLabels = true,
  excludeReplacements = true
): T[] {
  if (!features || !bounds) return [];

  return features.filter(f => {
    // Skip label points
    if (excludeLabels && f.properties?._isLabelPoint) return false;

    // Skip small feature replacement points
    if (excludeReplacements && f.properties?._isSmallFeatureReplacement) return false;

    return isFeatureInBounds(f, bounds);
  });
}

/** Calculate buffered bounds. */
export function calculateBufferedBounds(bounds: LngLatBounds, bufferMiles = 500): BufferedBounds {
  // Convert miles to degrees (approximate at equator: 1 degree ≈ 69.172 miles)
  const bufferDegrees = bufferMiles / 69.172;

  return {
    west: bounds.getWest() - bufferDegrees,
    east: bounds.getEast() + bufferDegrees,
    south: bounds.getSouth() - bufferDegrees,
    north: bounds.getNorth() + bufferDegrees
  };
}

/** Check if feature is far outside bounds (true if ALL coordinates are outside buffered bounds). */
export function isFeatureFarOutside(feature: ExtentFeature | null | undefined, bufferedBounds: BufferedBounds): boolean {
  if (!feature?.geometry) return true;

  const coords = getCoordinatesFromGeometry(feature.geometry);

  // Feature is far outside if ALL coordinates are outside buffered bounds
  return coords.every(coord => {
    const [lon, lat] = coord;
    return lon < bufferedBounds.west || lon > bufferedBounds.east ||
           lat < bufferedBounds.south || lat > bufferedBounds.north;
  });
}

export interface CleanupDistantFeaturesResult<T> {
  filteredFeatures: T[]
  removedCount: number
}

/** Clean up features far outside viewport. */
export function cleanupDistantFeatures<T extends ExtentFeature>(
  features: T[] | null | undefined,
  bounds: LngLatBounds | null | undefined,
  getCoords: (geometry: Geometry | null | undefined) => Position[],
  bufferMiles = 500
): CleanupDistantFeaturesResult<T> {
  if (!features || !bounds) return { filteredFeatures: features ?? [], removedCount: 0 };

  const bufferedBounds = calculateBufferedBounds(bounds, bufferMiles);

  const filteredFeatures = features.filter(f => {
    if (!f.geometry) return false;

    const coords = getCoords(f.geometry);

    // Check if any coordinate is within the buffered bounds
    return coords.some(coord => {
      const [lon, lat] = coord;
      return lon >= bufferedBounds.west && lon <= bufferedBounds.east &&
             lat >= bufferedBounds.south && lat <= bufferedBounds.north;
    });
  });

  return {
    filteredFeatures,
    removedCount: features.length - filteredFeatures.length
  };
}
