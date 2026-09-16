import type { Geometry, Position } from 'geojson';

/** Flatten a GeoJSON geometry to [lon, lat, ...] positions. */
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
            return geometry.geometries.flatMap((part) => getCoordinatesFromGeometry(part));
        default:
            return [];
    }
}

export function extractLineCoordinates(geometry: Geometry | null | undefined): Position[] {
    if (!geometry) return [];
    if (geometry.type === 'LineString') return geometry.coordinates;
    if (geometry.type === 'MultiLineString') return geometry.coordinates.flat();
    return [];
}

export function extractPolygonCoordinates(geometry: Geometry | null | undefined): Position[] {
    if (!geometry) return [];
    if (geometry.type === 'Polygon') return geometry.coordinates.flat();
    if (geometry.type === 'MultiPolygon') return geometry.coordinates.flat(2);
    return [];
}
