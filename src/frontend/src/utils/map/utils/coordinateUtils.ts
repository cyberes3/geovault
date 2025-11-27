/**
 * Coordinate Utilities
 *
 * Functions for coordinate transformations and bounding box operations.
 */

/**
 * Create a grid-based key for bounding box tracking
 * @param extent - Map extent [minX, minY, maxX, maxY]
 * @param zoom - Current zoom level
 * @returns Grid-based key string
 */
export function getBoundingBoxKey(extent: number[], zoom: number): string {
    const [minX, minY, maxX, maxY] = extent;
    const roundedZoom = Math.round(zoom);

    // For very low zoom levels (world view), use a more precise grid system
    // to avoid overly large grid cells that prevent proper data loading
    let gridSize;
    if (roundedZoom <= 3) {
        // For world view (zoom 0-3), use a fixed smaller grid size
        gridSize = 1000000; // 1 million meters (roughly 1 degree at equator)
    } else if (roundedZoom <= 6) {
        // For continental view (zoom 4-6), use moderate grid size
        gridSize = Math.pow(2, 18 - roundedZoom);
    } else {
        // For local view (zoom 7+), use the original calculation
        gridSize = Math.pow(2, 15 - roundedZoom);
    }

    const gridMinX = Math.floor(minX / gridSize) * gridSize;
    const gridMinY = Math.floor(minY / gridSize) * gridSize;
    const gridMaxX = Math.ceil(maxX / gridSize) * gridSize;
    const gridMaxY = Math.ceil(maxY / gridSize) * gridSize;

    return `${gridMinX},${gridMinY},${gridMaxX},${gridMaxY}_${roundedZoom}`;
}

/**
 * Convert Web Mercator extent to geographic coordinates
 * @param extent - Map extent in Web Mercator
 * @param toLonLat - OpenLayers toLonLat function
 * @returns Bounding box string in geographic coordinates
 */
export function getBoundingBoxString(extent: number[], toLonLat: (coords: number[]) => number[]): string {
    const [minX, minY, maxX, maxY] = extent;

    // Use OpenLayers' built-in coordinate transformation
    const minLonLat = toLonLat([minX, minY]);
    const maxLonLat = toLonLat([maxX, maxY]);

    return `${minLonLat[0]},${minLonLat[1]},${maxLonLat[0]},${maxLonLat[1]}`;
}

