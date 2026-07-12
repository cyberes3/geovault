/**
 * Coordinate utilities: parsing free-text coordinate strings, validating GeoJSON coordinate
 * arrays, and tolerance-based coordinate/geometry/feature matching. All three operate on the same
 * `[lon, lat(, elevation)]` coordinate shape, so they live together in one module.
 */

import Coordinates from 'coordinate-parser';
import type { GeoJsonFeature } from '@/types/geospatial';

type GeoJsonGeometry = GeoJsonFeature['geometry'];

// `new Error(message, { cause })` needs an ES2022 lib target this project doesn't build with yet;
// setting `.cause` directly is functionally identical and works under the current ES2020 target.
function errorWithCause(message: string, cause: unknown): Error {
    const error = new Error(message);
    (error as Error & { cause?: unknown }).cause = cause;
    return error;
}

// ---------------------------------------------------------------------------
// Free-text coordinate parsing (e.g. "39.126, -104.889" or DMS strings typed
// into a search box), backed by the `coordinate-parser` library.
// ---------------------------------------------------------------------------

export interface ParsedLatLng {
    lat: number;
    lng: number;
}

/** Attempts to parse a string as coordinates in any format `coordinate-parser` supports. */
export function parseCoordinates(input: string | null | undefined): ParsedLatLng | null {
    if (!input || typeof input !== 'string') {
        return null;
    }

    try {
        const position = new Coordinates(input.trim());
        return {
            lat: position.getLatitude(),
            lng: position.getLongitude(),
        };
    } catch {
        return null;
    }
}

/**
 * Returns true if the string looks like a coordinate attempt (only N/S/E/W/D letters, 2-6 numbers,
 * valid cardinal orientation). Used to show "Invalid coordinate format" instead of geocoding.
 * Mirrors Android CoordinateParser.looksLikeCoordinates / Validator.
 */
export function looksLikeCoordinates(input: string | null | undefined): boolean {
    if (!input || typeof input !== 'string') return false;
    const s = input.trim();
    if (!s) return false;
    // Only allow letters n, s, e, w, d (e.g. "39 N 104 W")
    if (/(?![neswd])[a-z]/i.test(s)) return false;
    // Valid cardinal orientation: optional N/S and E/W in order
    if (!/^[^nsew]*[ns]?[^nsew]*[ew]?[^nsew]*$/i.test(s)) return false;
    // 2, 4, or 6 numbers (lat/lon pairs)
    const numbers = s.match(/-?\d+(\.\d+)?/g);
    const count = numbers ? numbers.length : 0;
    if (count === 0 || count % 2 !== 0 || count > 6) return false;
    return true;
}

// ---------------------------------------------------------------------------
// GeoJSON coordinate validation: structure, bounds, and lat/lon-swap detection.
// ---------------------------------------------------------------------------

export interface CoordinateValidationResult {
    valid: boolean;
    error: string | null;
}

interface LonLat {
    lon: number;
    lat: number;
}

/** Validate a single point coordinate [lon, lat] or [lon, lat, elevation]. */
function validatePointCoordinate(point: unknown): LonLat {
    if (!Array.isArray(point)) {
        throw new Error(`Point coordinate must be an array, got ${typeof point}`);
    }

    if (point.length < 2) {
        throw new Error(`Point coordinate must have at least 2 elements [lon, lat], got ${point.length}`);
    }

    const lon: unknown = point[0];
    const lat: unknown = point[1];

    // These `typeof` checks also rule out null/undefined (whose typeof is 'object'/'undefined'),
    // so they double as the "coordinate contains null or undefined values" check.
    if (typeof lon !== 'number' && typeof lon !== 'string') {
        throw new Error(`Longitude must be a number, got ${typeof lon}`);
    }
    if (typeof lat !== 'number' && typeof lat !== 'string') {
        throw new Error(`Latitude must be a number, got ${typeof lat}`);
    }

    const lonNum = parseFloat(lon as string);
    const latNum = parseFloat(lat as string);

    if (isNaN(lonNum) || isNaN(latNum)) {
        throw new Error('Coordinate values must be valid numbers');
    }

    if (!isFinite(lonNum) || !isFinite(latNum)) {
        throw new Error('Coordinate values cannot be Infinity');
    }

    // Check bounds — flag obvious lat/lon swap before generic latitude out-of-bounds text.
    if (!(-180 <= lonNum && lonNum <= 180)) {
        throw new Error(`Longitude ${lonNum} is out of bounds [-180, 180]`);
    }
    if (Math.abs(latNum) > 90) {
        throw new Error(`Coordinates appear to be swapped. Latitude ${latNum} is outside valid range [-90, 90].`);
    }
    if (!(-90 <= latNum && latNum <= 90)) {
        throw new Error(`Latitude ${latNum} is out of bounds [-90, 90]`);
    }

    return { lon: lonNum, lat: latNum };
}

/** Check multiple points to detect a consistent lat/lon-swapping pattern. */
function checkMultiplePointsForSwap(points: LonLat[]): string | null {
    if (points.length < 2) {
        return null;
    }

    let swapCount = 0;
    let totalChecked = 0;

    for (const { lon, lat } of points) {
        // Skip if either is clearly out of bounds (already caught by validatePointCoordinate).
        if (Math.abs(lon) > 180 || Math.abs(lat) > 90) {
            continue;
        }

        totalChecked += 1;

        // If both are in valid ranges but lon is in lat range and lat is in lon range, this looks like a swap.
        if (-90 <= lon && lon <= 90 && -180 <= lat && lat <= 180 && Math.abs(lon) > Math.abs(lat)) {
            swapCount += 1;
        }
    }

    if (totalChecked >= 2 && swapCount > totalChecked * 0.5) {
        return (
            'Multiple coordinates appear to be swapped. ' +
            'Expected [longitude, latitude] format. ' +
            'Longitude should be first (range -180 to 180), latitude second (range -90 to 90).'
        );
    }

    return null;
}

// A `Map` (rather than a `Record`) so an unknown key soundly types as `number | undefined`
// instead of TS unsoundly assuming an index signature lookup always succeeds.
const COORDINATE_DEPTH_BY_GEOMETRY_TYPE = new Map<string, number>([
    ['point', 0],
    ['linestring', 1],
    ['polygon', 2],
    ['multipoint', 1],
    ['multilinestring', 2],
    ['multipolygon', 3],
]);

/** Validate coordinate structure and depth, returning all points for swap detection. */
function validateCoordinatesStructure(coordinates: unknown, expectedDepth: number, geometryType: string): LonLat[] {
    if (!Array.isArray(coordinates)) {
        throw new Error(`${geometryType} coordinates must be an array`);
    }

    if (expectedDepth === 0) {
        // Point: [lon, lat] or [lon, lat, elevation]
        return [validatePointCoordinate(coordinates)];
    }

    if (expectedDepth === 1) {
        // LineString or MultiPoint: [[lon, lat], ...]
        if (coordinates.length === 0) {
            throw new Error(`${geometryType} must have at least one coordinate`);
        }

        return coordinates.map((coord, i) => {
            try {
                return validatePointCoordinate(coord);
            } catch (e) {
                throw errorWithCause(`Invalid coordinate at index ${i}: ${e instanceof Error ? e.message : String(e)}`, e);
            }
        });
    }

    if (expectedDepth === 2) {
        // Polygon or MultiLineString: [[[lon, lat], ...], ...]
        if (coordinates.length === 0) {
            throw new Error(`${geometryType} must have at least one ring/line`);
        }

        const allPoints: LonLat[] = [];
        coordinates.forEach((ring: unknown, ringIdx: number) => {
            if (!Array.isArray(ring)) {
                throw new Error(`${geometryType} ring/line at index ${ringIdx} must be an array`);
            }
            if (ring.length === 0) {
                throw new Error(`${geometryType} ring/line at index ${ringIdx} must have at least one coordinate`);
            }

            ring.forEach((point, pointIdx) => {
                try {
                    allPoints.push(validatePointCoordinate(point));
                } catch (e) {
                    throw errorWithCause(`Invalid coordinate at ring/line ${ringIdx}, point ${pointIdx}: ${e instanceof Error ? e.message : String(e)}`, e);
                }
            });
        });

        return allPoints;
    }

    if (expectedDepth === 3) {
        // MultiPolygon: [[[[lon, lat], ...], ...], ...]
        if (coordinates.length === 0) {
            throw new Error(`${geometryType} must have at least one polygon`);
        }

        const allPoints: LonLat[] = [];
        coordinates.forEach((polygon: unknown, polyIdx: number) => {
            if (!Array.isArray(polygon)) {
                throw new Error(`${geometryType} polygon at index ${polyIdx} must be an array`);
            }
            if (polygon.length === 0) {
                throw new Error(`${geometryType} polygon at index ${polyIdx} must have at least one ring`);
            }

            polygon.forEach((ring: unknown, ringIdx: number) => {
                if (!Array.isArray(ring)) {
                    throw new Error(`${geometryType} polygon ${polyIdx}, ring ${ringIdx} must be an array`);
                }
                if (ring.length === 0) {
                    throw new Error(`${geometryType} polygon ${polyIdx}, ring ${ringIdx} must have at least one coordinate`);
                }

                ring.forEach((point, pointIdx) => {
                    try {
                        allPoints.push(validatePointCoordinate(point));
                    } catch (e) {
                        throw errorWithCause(`Invalid coordinate at polygon ${polyIdx}, ring ${ringIdx}, point ${pointIdx}: ${e instanceof Error ? e.message : String(e)}`, e);
                    }
                });
            });
        });

        return allPoints;
    }

    throw new Error(`Unsupported coordinate depth: ${expectedDepth}`);
}

/**
 * Validate a coordinates array matches the expected structure for the geometry type: structure
 * depth, bounds (lon: -180 to 180, lat: -90 to 90), and lat/lon-swap detection.
 */
export function validateCoordinates(coordinates: unknown, geometryType: string | null | undefined): CoordinateValidationResult {
    try {
        if (coordinates === null || coordinates === undefined) {
            return { valid: false, error: 'Coordinates cannot be null or empty' };
        }

        if (!Array.isArray(coordinates)) {
            return { valid: false, error: `Coordinates must be an array, got ${typeof coordinates}` };
        }

        const geomType = (geometryType ?? '').toLowerCase();

        const expectedDepth = COORDINATE_DEPTH_BY_GEOMETRY_TYPE.get(geomType);
        if (expectedDepth === undefined) {
            return { valid: false, error: `Unsupported geometry type: ${geometryType}` };
        }

        if (expectedDepth === 0 && coordinates.length === 0) {
            return { valid: false, error: 'Point coordinates cannot be empty. Must be [longitude, latitude]' };
        }
        if (expectedDepth > 0 && coordinates.length === 0) {
            return { valid: false, error: `${geometryType} coordinates cannot be empty` };
        }

        const points = validateCoordinatesStructure(coordinates, expectedDepth, geometryType ?? '');

        if (points.length > 1) {
            const swapError = checkMultiplePointsForSwap(points);
            if (swapError) {
                return { valid: false, error: swapError };
            }
        }

        return { valid: true, error: null };
    } catch (e) {
        return { valid: false, error: e instanceof Error ? e.message : String(e) };
    }
}

// ---------------------------------------------------------------------------
// Tolerance-based coordinate/geometry/feature matching, mirroring the backend's
// tolerance-based duplicate-coordinate matching logic.
// ---------------------------------------------------------------------------

type NestedCoordinates = number | NestedCoordinates[];

/** Normalize coordinates by rounding to the given tolerance, recursing into nested arrays. */
export function normalizeCoordinates(coords: NestedCoordinates, tolerance = 1e-6): NestedCoordinates {
    if (!Array.isArray(coords)) {
        return coords;
    }

    // Handle nested arrays (for LineString, Polygon, etc.)
    if (Array.isArray(coords[0])) {
        return coords.map((coord) => normalizeCoordinates(coord, tolerance));
    }

    // Handle individual coordinate pairs/triples.
    return coords.map((coord) => (typeof coord === 'number' ? Math.round(coord / tolerance) * tolerance : coord));
}

/** Check if two coordinate sets match within tolerance. */
export function coordinatesMatch(coord1: NestedCoordinates | null | undefined, coord2: NestedCoordinates | null | undefined, tolerance = 1e-6): boolean {
    if (!coord1 || !coord2) {
        return false;
    }

    const norm1 = normalizeCoordinates(coord1, tolerance);
    const norm2 = normalizeCoordinates(coord2, tolerance);

    return JSON.stringify(norm1) === JSON.stringify(norm2);
}

/** Check if two geometry objects have matching type and coordinates. */
export function geometriesMatch(geom1: GeoJsonGeometry | null | undefined, geom2: GeoJsonGeometry | null | undefined, tolerance = 1e-6): boolean {
    if (!geom1 || !geom2) {
        return false;
    }

    if (geom1.type !== geom2.type) {
        return false;
    }

    return coordinatesMatch(geom1.coordinates as NestedCoordinates, geom2.coordinates as NestedCoordinates, tolerance);
}

/** Check if two GeoJSON features have matching coordinates. */
export function featuresMatch(feature1: GeoJsonFeature | null | undefined, feature2: GeoJsonFeature | null | undefined, tolerance = 1e-6): boolean {
    if (!feature1 || !feature2) {
        return false;
    }

    return geometriesMatch(feature1.geometry, feature2.geometry, tolerance);
}

/** Find features in an array that match the given feature's coordinates. */
export function findMatchingFeatures(targetFeature: GeoJsonFeature | null | undefined, features: GeoJsonFeature[], tolerance = 1e-6): GeoJsonFeature[] {
    if (!targetFeature || !Array.isArray(features)) {
        return [];
    }

    return features.filter((feature) => featuresMatch(targetFeature, feature, tolerance));
}

/** Check if a feature has duplicate coordinates elsewhere in the given array. */
export function hasDuplicateCoordinates(feature: GeoJsonFeature, features: GeoJsonFeature[], tolerance = 1e-6): boolean {
    const matches = findMatchingFeatures(feature, features, tolerance);
    return matches.length > 1; // More than just itself.
}
