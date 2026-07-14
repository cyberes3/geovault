/**
 * Utility functions for handling elevation data in GeoJSON features.
 *
 * MapLibre strips elevation (3rd coordinate) from geometry coordinates and stores
 * it in `_elevation` (points) or `_elevations` (lines) properties. This module
 * provides functions to restore elevation data back into coordinates before
 * sending to the backend.
 *
 * IMPORTANT: Before sending any full GeoJSON Feature object to update endpoints
 * (like `/api/feature/<id>/update/`), call `restoreElevationInGeometry()` to
 * ensure elevation data is preserved in the coordinates.
 *
 * Example usage:
 * ```typescript
 * import { restoreElevationInGeometry } from '@/utils/elevationUtils.js'
 *
 * // Before sending to update endpoint
 * const featureWithElevation = restoreElevationInGeometry(feature)
 * await fetch(`/api/feature/${id}/update/`, {
 *   method: 'PUT',
 *   body: JSON.stringify(featureWithElevation)
 * })
 * ```
 */

interface ElevationGeometry {
  type?: string;
  coordinates?: unknown;
  [key: string]: unknown;
}

export interface ElevationFeature {
  type?: string;
  geometry?: ElevationGeometry | null;
  properties?: Record<string, unknown> | null;
  [key: string]: unknown;
}

export interface ElevationFeatureCollection {
  type?: string;
  features?: ElevationFeature[];
  [key: string]: unknown;
}

/**
 * Restores elevation data from properties back into geometry coordinates.
 *
 * For Points: Restores `_elevation` to `coordinates[2]`
 * For MultiPoints: Restores `_elevation` to first point's `coordinates[2]`
 * For LineStrings: Restores `_elevations` array to each coordinate's `coordinates[2]`
 * For MultiLineStrings: Restores `_elevations` array (flattened) to each coordinate's `coordinates[2]`
 *
 * After restoration, removes `_elevation` and `_elevations` from properties.
 */
export function restoreElevationInGeometry<T extends ElevationFeature | null | undefined>(feature: T): T {
  if (!feature?.geometry || !feature.properties) {
    return feature;
  }

  // Create a deep copy to avoid mutating the original
  const restoredFeature = JSON.parse(JSON.stringify(feature)) as T & ElevationFeature;
  const geometry = restoredFeature.geometry;
  const properties = restoredFeature.properties;

  if (!geometry || !properties) {
    return restoredFeature;
  }

  const geometryType = geometry.type;

  if (geometryType === 'Point') {
    if (properties._elevation != null && Array.isArray(geometry.coordinates)) {
      const coordinates = geometry.coordinates as unknown[];
      if (coordinates.length >= 2) {
        coordinates[2] = properties._elevation;
      }
      delete properties._elevation;
    }
  } else if (geometryType === 'MultiPoint') {
    if (properties._elevation != null && Array.isArray(geometry.coordinates)) {
      const coordinates = geometry.coordinates as unknown[];
      // Restore elevation to first point (MapLibre stores elevation from first point)
      if (coordinates.length > 0 && Array.isArray(coordinates[0])) {
        const firstPoint = coordinates[0] as unknown[];
        if (firstPoint.length >= 2) {
          firstPoint[2] = properties._elevation;
        }
      }
      delete properties._elevation;
    }
  } else if (geometryType === 'LineString') {
    if (properties._elevations != null && Array.isArray(geometry.coordinates)) {
      const coordinates = geometry.coordinates as unknown[];
      const elevationArray = parseElevationArray(properties._elevations);

      if (Array.isArray(elevationArray)) {
        coordinates.forEach((coord, index) => {
          if (Array.isArray(coord) && coord.length >= 2 && elevationArray[index] != null) {
            (coord as unknown[])[2] = elevationArray[index];
          }
        });
      }

      delete properties._elevations;
    }
  } else if (geometryType === 'MultiLineString') {
    if (properties._elevations != null && Array.isArray(geometry.coordinates)) {
      const coordinates = geometry.coordinates as unknown[];
      const elevationArray = parseElevationArray(properties._elevations);

      if (Array.isArray(elevationArray)) {
        // Flatten the elevation array index to match flattened coordinates
        let elevationIndex = 0;

        coordinates.forEach((lineCoords) => {
          if (Array.isArray(lineCoords)) {
            lineCoords.forEach((coord: unknown) => {
              if (Array.isArray(coord) && coord.length >= 2 && elevationArray[elevationIndex] != null) {
                (coord as unknown[])[2] = elevationArray[elevationIndex];
                elevationIndex++;
              }
            });
          }
        });
      }

      delete properties._elevations;
    }
  }

  return restoredFeature;
}

/** Parse elevations if stored as a JSON string (sometimes stored that way), otherwise pass through. */
function parseElevationArray(elevations: unknown): unknown[] | null {
  if (typeof elevations === 'string') {
    try {
      const parsed: unknown = JSON.parse(elevations);
      return Array.isArray(parsed) ? parsed : null;
    } catch {
      console.warn('Failed to parse _elevations as JSON:', elevations);
      return null;
    }
  }
  return Array.isArray(elevations) ? elevations : null;
}

/** Restores elevation for a FeatureCollection. */
export function restoreElevationInFeatureCollection<T extends ElevationFeatureCollection | null | undefined>(featureCollection: T): T {
  if (!featureCollection || featureCollection.type !== 'FeatureCollection') {
    return featureCollection;
  }

  const restored = JSON.parse(JSON.stringify(featureCollection)) as T & ElevationFeatureCollection;

  if (Array.isArray(restored.features)) {
    restored.features = restored.features.map((feature) => restoreElevationInGeometry(feature));
  }

  return restored;
}
