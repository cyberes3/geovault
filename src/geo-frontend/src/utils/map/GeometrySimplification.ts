/**
 * Geometry Simplification Utility
 *
 * This utility class provides optimized geometry simplification using the Douglas-Peucker algorithm
 * with performance optimizations for handling large datasets in real-time map applications.
 *
 * ## Key Features:
 *
 * - **Optimized Douglas-Peucker Algorithm**: Iterative implementation for better performance
 * - **Zoom-based Simplification**: More aggressive simplification at lower zoom levels
 * - **Adaptive Tolerance**: Automatically adjusts based on geometry complexity
 * - **Feature Count Scaling**: Integrates with tier system for dynamic scaling
 * - **Performance Monitoring**: Tracks processing time and coordinate reduction
 *
 * ## Algorithm Optimizations:
 *
 * - **Iterative vs Recursive**: Uses stack-based iteration to avoid call stack limits
 * - **Pre-calculated Vectors**: Eliminates redundant distance calculations
 * - **Early Exit Conditions**: Skips processing for very small tolerances
 * - **Coordinate Deduplication**: Removes consecutive duplicate points
 */

import type {GeoJsonFeature, GeoJsonFeatureCollection, SimplificationOptions, SimplificationStats} from '@/types/geospatial';

export class GeometrySimplification {
    /**
     * Count total coordinates in a geometry
     * @param geometry - GeoJSON geometry
     * @returns Total coordinate count
     */
    public static countCoordinates(geometry: any): number {
        if (!geometry || !geometry.coordinates) {
            return 0;
        }

        const {type, coordinates} = geometry;

        switch (type) {
            case 'Point':
                return 1;
            case 'LineString':
                return coordinates.length;
            case 'Polygon':
                return coordinates.reduce((sum: number, ring: any) => sum + ring.length, 0);
            case 'MultiPoint':
                return coordinates.length;
            case 'MultiLineString':
                return coordinates.reduce((sum: number, line: any) => sum + line.length, 0);
            case 'MultiPolygon':
                return coordinates.reduce((sum: number, polygon: any) =>
                    sum + polygon.reduce((ringSum: number, ring: any) => ringSum + ring.length, 0), 0);
            default:
                return 0;
        }
    }

    /**
     * Simplify a GeoJSON feature
     * @param feature - GeoJSON feature
     * @param zoom - Current zoom level
     * @param options - Simplification options
     * @param featureCountMultiplier - Multiplier based on feature count (optional)
     * @returns Simplified feature
     */
    public static simplifyFeature(
        feature: GeoJsonFeature,
        zoom: number,
        options: SimplificationOptions,
        featureCountMultiplier: number = 1.0
    ): GeoJsonFeature {
        if (!feature || !feature.geometry) {
            return feature;
        }

        const {
            baseTolerance = 0.001,
            minZoom = 1,
            maxZoom = 20,
            enableSimplification = true
        } = options;

        // Don't simplify if disabled or zoom is out of range
        if (!enableSimplification || zoom < minZoom || zoom > maxZoom) {
            return feature;
        }

        // Calculate tolerance based on zoom level
        let tolerance = this.calculateToleranceFromZoom(zoom, baseTolerance);

        // Apply feature count multiplier for dynamic scaling
        tolerance = tolerance * featureCountMultiplier;

        // Apply adaptive tolerance based on geometry complexity
        tolerance = this.calculateAdaptiveTolerance(feature.geometry, tolerance);

        // Simplify the geometry
        const simplifiedGeometry = this.simplifyGeometry(feature.geometry, tolerance);

        return {
            ...feature,
            geometry: simplifiedGeometry
        };
    }

    /**
     * Simplify an array of GeoJSON features
     * @param features - Array of GeoJSON features
     * @param zoom - Current zoom level
     * @param options - Simplification options
     * @param featureCountMultiplier - Multiplier based on feature count (optional)
     * @returns Array of simplified features
     */
    public static simplifyFeatures(
        features: GeoJsonFeature[],
        zoom: number,
        options: SimplificationOptions,
        featureCountMultiplier: number = 1.0
    ): GeoJsonFeature[] {
        if (!Array.isArray(features)) {
            return features;
        }

        return features.map(feature => this.simplifyFeature(feature, zoom, options, featureCountMultiplier));
    }

    /**
     * Simplify a GeoJSON FeatureCollection
     * @param featureCollection - GeoJSON FeatureCollection
     * @param zoom - Current zoom level
     * @param options - Simplification options
     * @param featureCountMultiplier - Multiplier based on feature count (optional)
     * @returns Simplified FeatureCollection
     */
    public static simplifyFeatureCollection(
        featureCollection: GeoJsonFeatureCollection,
        zoom: number,
        options: SimplificationOptions,
        featureCountMultiplier: number = 1.0
    ): GeoJsonFeatureCollection {
        if (!featureCollection || featureCollection.type !== 'FeatureCollection') {
            return featureCollection;
        }

        const simplifiedFeatures = this.simplifyFeatures(featureCollection.features, zoom, options, featureCountMultiplier);

        return {
            ...featureCollection,
            features: simplifiedFeatures
        };
    }

    /**
     * Get simplification statistics for debugging
     * @param originalGeometry - Original geometry
     * @param simplifiedGeometry - Simplified geometry
     * @returns Statistics object
     */
    public static getSimplificationStats(originalGeometry: any, simplifiedGeometry: any): SimplificationStats | null {
        if (!originalGeometry || !simplifiedGeometry) {
            return null;
        }

        const originalCoords = this.countCoordinates(originalGeometry);
        const simplifiedCoords = this.countCoordinates(simplifiedGeometry);

        return {
            originalCoordinateCount: originalCoords,
            simplifiedCoordinateCount: simplifiedCoords,
            reductionPercentage: originalCoords > 0 ?
                ((originalCoords - simplifiedCoords) / originalCoords * 100).toFixed(2) : '0',
            compressionRatio: originalCoords > 0 ?
                (originalCoords / simplifiedCoords).toFixed(2) : '1'
        };
    }

    /**
     * Calculate the perpendicular distance from a point to a line segment
     * @param point - [x, y] coordinate
     * @param lineStart - [x, y] start of line segment
     * @param lineEnd - [x, y] end of line segment
     * @returns Perpendicular distance
     */
    private static perpendicularDistance(point: [number, number], lineStart: [number, number], lineEnd: [number, number]): number {
        const [x0, y0] = point;
        const [x1, y1] = lineStart;
        const [x2, y2] = lineEnd;

        const A = x0 - x1;
        const B = y0 - y1;
        const C = x2 - x1;
        const D = y2 - y1;

        const dot = A * C + B * D;
        const lenSq = C * C + D * D;

        if (lenSq === 0) {
            // Line segment is actually a point
            return Math.sqrt(A * A + B * B);
        }

        const param = dot / lenSq;

        let xx, yy;
        if (param < 0) {
            xx = x1;
            yy = y1;
        } else if (param > 1) {
            xx = x2;
            yy = y2;
        } else {
            xx = x1 + param * C;
            yy = y1 + param * D;
        }

        const dx = x0 - xx;
        const dy = y0 - yy;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Optimized perpendicular distance calculation with pre-calculated values
     * @param point - [x, y] coordinate
     * @param lineStart - [x, y] start of line segment
     * @param lineEnd - [x, y] end of line segment
     * @param dx - Pre-calculated line vector x component
     * @param dy - Pre-calculated line vector y component
     * @param lineLengthSq - Pre-calculated line length squared
     * @returns Perpendicular distance
     */
    private static perpendicularDistanceOptimized(
        point: [number, number],
        lineStart: [number, number],
        lineEnd: [number, number],
        dx: number,
        dy: number,
        lineLengthSq: number
    ): number {
        const [x0, y0] = point;
        const [x1, y1] = lineStart;

        if (lineLengthSq === 0) {
            // Line segment is actually a point
            const dx2 = x0 - x1;
            const dy2 = y0 - y1;
            return Math.sqrt(dx2 * dx2 + dy2 * dy2);
        }

        const A = x0 - x1;
        const B = y0 - y1;

        const dot = A * dx + B * dy;
        const param = dot / lineLengthSq;

        let xx, yy;
        if (param < 0) {
            xx = x1;
            yy = y1;
        } else if (param > 1) {
            xx = lineEnd[0];
            yy = lineEnd[1];
        } else {
            xx = x1 + param * dx;
            yy = y1 + param * dy;
        }

        const dx2 = x0 - xx;
        const dy2 = y0 - yy;
        return Math.sqrt(dx2 * dx2 + dy2 * dy2);
    }

    /**
     * Optimized Douglas-Peucker line simplification algorithm
     * Uses iterative approach for better performance and to avoid call stack limits
     * @param coordinates - Array of [x, y] coordinates
     * @param tolerance - Simplification tolerance (higher = more simplified)
     * @returns Simplified coordinates
     */
    private static douglasPeucker(coordinates: [number, number][], tolerance: number): [number, number][] {
        if (coordinates.length <= 2) {
            return coordinates;
        }

        // Early exit for very small tolerances (no simplification needed)
        if (tolerance < 1e-10) {
            return coordinates;
        }

        // Use iterative approach instead of recursive for better performance
        const result: [number, number][] = [];
        const stack: Array<{ start: number; end: number }> = [{start: 0, end: coordinates.length - 1}];

        while (stack.length > 0) {
            const {start, end} = stack.pop()!;

            if (end - start <= 1) {
                // Add the point(s)
                if (start === end) {
                    result.push(coordinates[start]);
                } else {
                    result.push(coordinates[start]);
                    result.push(coordinates[end]);
                }
                continue;
            }

            // Find the point with the maximum distance
            let maxDistance = 0;
            let maxIndex = start;

            const startPoint = coordinates[start];
            const endPoint = coordinates[end];

            // Pre-calculate line vector for efficiency
            const dx = endPoint[0] - startPoint[0];
            const dy = endPoint[1] - startPoint[1];
            const lineLengthSq = dx * dx + dy * dy;

            for (let i = start + 1; i < end; i++) {
                const distance = this.perpendicularDistanceOptimized(
                    coordinates[i],
                    startPoint,
                    endPoint,
                    dx,
                    dy,
                    lineLengthSq
                );

                if (distance > maxDistance) {
                    maxDistance = distance;
                    maxIndex = i;
                }
            }

            // If max distance is greater than tolerance, split and continue
            if (maxDistance > tolerance) {
                stack.push({start: maxIndex, end: end});
                stack.push({start: start, end: maxIndex});
            } else {
                // All points are within tolerance, add endpoints
                result.push(startPoint);
                result.push(endPoint);
            }
        }

        // Remove duplicate consecutive points
        const finalResult: [number, number][] = [];
        for (let i = 0; i < result.length; i++) {
            if (i === 0 || result[i][0] !== result[i - 1][0] || result[i][1] !== result[i - 1][1]) {
                finalResult.push(result[i]);
            }
        }

        return finalResult;
    }

    /**
     * Calculate simplification tolerance based on zoom level
     * Higher zoom = lower tolerance (more detail)
     * Lower zoom = higher tolerance (less detail)
     * @param zoom - Current zoom level
     * @param baseTolerance - Base tolerance for zoom level 10
     * @returns Calculated tolerance
     */
    private static calculateToleranceFromZoom(zoom: number, baseTolerance: number = 0.001): number {
        // Use exponential scaling for smooth transitions
        const zoomFactor = Math.pow(2, 10 - zoom);
        return baseTolerance * zoomFactor;
    }

    /**
     * Calculate adaptive tolerance based on geometry complexity
     * @param geometry - GeoJSON geometry
     * @param baseTolerance - Base tolerance
     * @returns Adaptive tolerance
     */
    private static calculateAdaptiveTolerance(geometry: any, baseTolerance: number): number {
        const coordCount = this.countCoordinates(geometry);

        // For very complex geometries (>1000 coordinates), increase tolerance
        if (coordCount > 1000) {
            return baseTolerance * Math.min(coordCount / 1000, 5); // Max 5x increase
        }

        // For simple geometries (<100 coordinates), decrease tolerance for better quality
        if (coordCount < 100) {
            return baseTolerance * 0.5;
        }

        return baseTolerance;
    }

    /**
     * Simplify a LineString geometry
     * @param coordinates - LineString coordinates
     * @param tolerance - Simplification tolerance
     * @returns Simplified coordinates
     */
    private static simplifyLineString(coordinates: [number, number][], tolerance: number): [number, number][] {
        if (!coordinates || coordinates.length < 2) {
            return coordinates;
        }

        return this.douglasPeucker(coordinates, tolerance);
    }

    /**
     * Simplify a Polygon geometry
     * @param coordinates - Polygon coordinates (array of rings)
     * @param tolerance - Simplification tolerance
     * @returns Simplified coordinates
     */
    private static simplifyPolygon(coordinates: [number, number][][], tolerance: number): [number, number][][] {
        if (!coordinates || !Array.isArray(coordinates)) {
            return coordinates;
        }

        return coordinates.map(ring => {
            if (!Array.isArray(ring) || ring.length < 3) {
                return ring;
            }

            // For polygons, we need to preserve the first and last points (which should be the same)
            const simplified = this.douglasPeucker(ring, tolerance);

            // Ensure the polygon is closed (first and last points are the same)
            if (simplified.length > 2) {
                const first = simplified[0];
                const last = simplified[simplified.length - 1];

                if (first[0] !== last[0] || first[1] !== last[1]) {
                    simplified.push([first[0], first[1]]);
                }
            }

            return simplified;
        });
    }

    /**
     * Simplify a MultiLineString geometry
     * @param coordinates - MultiLineString coordinates
     * @param tolerance - Simplification tolerance
     * @returns Simplified coordinates
     */
    private static simplifyMultiLineString(coordinates: [number, number][][], tolerance: number): [number, number][][] {
        if (!coordinates || !Array.isArray(coordinates)) {
            return coordinates;
        }

        return coordinates.map(lineString =>
            this.simplifyLineString(lineString, tolerance)
        );
    }

    /**
     * Simplify a MultiPolygon geometry
     * @param coordinates - MultiPolygon coordinates
     * @param tolerance - Simplification tolerance
     * @returns Simplified coordinates
     */
    private static simplifyMultiPolygon(coordinates: [number, number][][][], tolerance: number): [number, number][][][] {
        if (!coordinates || !Array.isArray(coordinates)) {
            return coordinates;
        }

        return coordinates.map(polygon =>
            this.simplifyPolygon(polygon, tolerance)
        );
    }

    /**
     * Simplify a geometry based on its type
     * @param geometry - GeoJSON geometry object
     * @param tolerance - Simplification tolerance
     * @returns Simplified geometry
     */
    private static simplifyGeometry(geometry: any, tolerance: number): any {
        if (!geometry || !geometry.coordinates) {
            return geometry;
        }

        const {type, coordinates} = geometry;

        let simplifiedCoordinates: any;

        switch (type) {
            case 'LineString':
                simplifiedCoordinates = this.simplifyLineString(coordinates, tolerance);
                break;
            case 'Polygon':
                simplifiedCoordinates = this.simplifyPolygon(coordinates, tolerance);
                break;
            case 'MultiLineString':
                simplifiedCoordinates = this.simplifyMultiLineString(coordinates, tolerance);
                break;
            case 'MultiPolygon':
                simplifiedCoordinates = this.simplifyMultiPolygon(coordinates, tolerance);
                break;
            case 'Point':
            case 'MultiPoint':
                // Points don't need simplification
                return geometry;
            default:
                // Unknown geometry type, return as-is
                return geometry;
        }

        return {
            ...geometry,
            coordinates: simplifiedCoordinates
        };
    }
}
