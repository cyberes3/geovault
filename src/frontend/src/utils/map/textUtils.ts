/**
 * Text/Label Utilities
 *
 * Functions for creating text styles and handling label placement logic.
 */

import {Fill, Stroke, Style, Text} from 'ol/style';
import {getLength} from 'ol/sphere';
import {getCenter} from 'ol/extent';
import {Point, LineString} from 'ol/geom';
import {getIconUrl} from './iconUtils';

/**
 * Calculate distance from a point to a line segment
 * @param point - Point [x, y]
 * @param lineStart - Line segment start [x, y]
 * @param lineEnd - Line segment end [x, y]
 * @returns Distance in map units (meters)
 */
function distanceToLineSegment(point: number[], lineStart: number[], lineEnd: number[]): number {
    const dx = lineEnd[0] - lineStart[0];
    const dy = lineEnd[1] - lineStart[1];
    const lengthSquared = dx * dx + dy * dy;

    if (lengthSquared === 0) {
        // Line segment is a point
        const dx2 = point[0] - lineStart[0];
        const dy2 = point[1] - lineStart[1];
        return Math.sqrt(dx2 * dx2 + dy2 * dy2);
    }

    // Calculate parameter t (position along line segment)
    const t = Math.max(0, Math.min(1,
        ((point[0] - lineStart[0]) * dx + (point[1] - lineStart[1]) * dy) / lengthSquared
    ));

    // Find closest point on line segment
    const closestX = lineStart[0] + t * dx;
    const closestY = lineStart[1] + t * dy;

    // Calculate distance
    const dx2 = point[0] - closestX;
    const dy2 = point[1] - closestY;
    return Math.sqrt(dx2 * dx2 + dy2 * dy2);
}

/**
 * Check if a polygon label would intersect with the polygon's border
 * @param geometry - Polygon or MultiPolygon geometry
 * @param text - Label text
 * @param resolution - Map resolution (meters per pixel)
 * @param strokeWidth - Stroke width in pixels (default: 2)
 * @returns true if label would intersect with border
 */
function checkLabelBorderIntersection(
    geometry: any,
    text: string,
    resolution: number,
    strokeWidth: number = 2
): boolean {
    if (!geometry || resolution <= 0) {
        return false;
    }

    const geometryType = geometry.getType();
    if (geometryType !== 'Polygon' && geometryType !== 'MultiPolygon') {
        return false;
    }

    // Estimate text dimensions
    // Font is 12px Arial, approximate character width is 7px, height is 12px
    const fontHeightPixels = 12;
    const avgCharWidthPixels = 7;
    const textWidthPixels = text.length * avgCharWidthPixels;
    const textHeightPixels = fontHeightPixels;

    // Convert to meters
    const textHeightMeters = textHeightPixels * resolution;
    const textWidthMeters = textWidthPixels * resolution;
    const strokeWidthMeters = strokeWidth * resolution;

    // Get polygon extent and centroid
    const extent = geometry.getExtent();
    const centroid = getCenter(extent);
    const widthMeters = extent[2] - extent[0];
    const heightMeters = extent[3] - extent[1];
    const minDimensionMeters = Math.min(widthMeters, heightMeters);

    // Check if polygon is too small to fit label without intersection
    // Label needs space: text height/2 on each side + stroke width
    const minRequiredDimension = textHeightMeters + (strokeWidthMeters * 2);

    if (minDimensionMeters < minRequiredDimension) {
        return true;
    }

    // For more accurate check, calculate distance from centroid to boundary
    // Get the exterior ring(s) of the polygon
    let exteriorRings: any[] = [];

    if (geometryType === 'Polygon') {
        const coordinates = geometry.getCoordinates();
        if (coordinates && coordinates.length > 0) {
            exteriorRings.push(new LineString(coordinates[0]));
        }
    } else if (geometryType === 'MultiPolygon') {
        const coordinates = geometry.getCoordinates();
        if (coordinates && Array.isArray(coordinates)) {
            for (const polygonCoords of coordinates) {
                if (polygonCoords && polygonCoords.length > 0) {
                    exteriorRings.push(new LineString(polygonCoords[0]));
                }
            }
        }
    }

    // Check distance from centroid to nearest point on boundary
    // Calculate distance to each line segment and take the minimum
    let minDistanceToBoundary = Infinity;
    for (const ring of exteriorRings) {
        const coords = ring.getCoordinates();
        for (let i = 0; i < coords.length - 1; i++) {
            const p1 = coords[i];
            const p2 = coords[i + 1];
            const distance = distanceToLineSegment(centroid, p1, p2);
            minDistanceToBoundary = Math.min(minDistanceToBoundary, distance);
        }
    }

    // If centroid is too close to boundary (less than text height/2 + stroke), label would intersect
    const requiredDistance = (textHeightMeters / 2) + strokeWidthMeters;
    return minDistanceToBoundary < requiredDistance;
}

/**
 * Create text style for feature labels
 * @param name - Feature name text
 * @param geometryType - Geometry type (Point, Polygon, LineString, etc.)
 * @param offsetY - Vertical offset for text (default based on geometry type)
 * @param placement - Text placement option ('point' for polygon centroid)
 * @returns OpenLayers Text style
 */
export function createTextStyle(
    name: string,
    geometryType: string,
    offsetY?: number,
    placement?: string | null
): Text {
    // Default offsets based on geometry type
    let defaultOffsetY: number;
    let defaultPlacement: string | undefined;

    if (geometryType === 'Point') {
        defaultOffsetY = offsetY !== undefined ? offsetY : 8;
    } else if (geometryType === 'Polygon') {
        defaultOffsetY = offsetY !== undefined ? offsetY : 0;
        // If placement is null, don't set it (allows custom geometry placement)
        // If placement is undefined (not provided), default to 'point' for centroid placement
        // Otherwise, use the provided placement value
        if (placement === null) {
            defaultPlacement = undefined;
        } else {
            defaultPlacement = placement !== undefined ? placement : 'point';
        }
    } else {
        defaultOffsetY = offsetY !== undefined ? offsetY : 10;
    }

    const textStyleConfig: any = {
        text: name,
        font: '12px Arial',
        fill: new Fill({
            color: '#000000'
        }),
        stroke: new Stroke({
            color: '#ffffff',
            width: 3
        }),
        offsetY: defaultOffsetY
    };

    if (defaultPlacement) {
        textStyleConfig.placement = defaultPlacement;
    }

    return new Text(textStyleConfig);
}

/**
 * Calculate zoom level from resolution (Web Mercator)
 * Uses equator approximation for simplicity
 * @param resolution - Map resolution in meters per pixel
 * @returns Zoom level
 */
function getZoomFromResolution(resolution: number): number {
    if (resolution <= 0) return 10; // Default to base zoom if invalid
    // Web Mercator resolution formula: resolution = 156543.03392 / 2^zoom
    // Solving for zoom: zoom = log2(156543.03392 / resolution)
    const baseResolution = 156543.03392;
    return Math.log2(baseResolution / resolution);
}

/**
 * Get text-only style for a feature (no icon/image)
 * Used for rendering labels on a separate layer with decluttering
 * @param feature - OpenLayers feature
 * @param resolution - Map resolution (meters per pixel), optional
 * @returns OpenLayers Style object with only text, or null to hide text
 */
export function getFeatureTextStyle(feature: any, resolution?: number): Style | null {
    const properties = feature.get('properties') || {};
    const geometry = feature.getGeometry();
    const geometryType = geometry.getType();
    let name = properties.name || 'Unnamed Feature';

    // Truncate long feature names for map labels
    const maxNameLength = 30;
    if (name.length > maxNameLength) {
        name = name.substring(0, maxNameLength) + '...';
    }

    // Hide point labels when zoomed out to county level or lower (zoom <= 12)
    // This prevents label clutter when viewing large areas (multi-state, country level)
    if (geometryType === 'Point' && resolution !== undefined && resolution > 0) {
        const zoom = getZoomFromResolution(resolution);
        const hidePointTextThreshhold = 8;
        if (zoom <= hidePointTextThreshhold) {
            return null; // Hide point labels when zoomed out
        }
    }

    // For lines, check if they're too small when zoomed out
    // Note: This includes lines < 2 pixels which are rendered as points (dots)
    if ((geometryType === 'LineString' || geometryType === 'MultiLineString') && resolution !== undefined && resolution > 0) {
        // Calculate line length in meters
        const lengthMeters = getLength(geometry);

        // Convert to pixels
        const lengthPixels = lengthMeters / resolution;

        // Hide text for lines < 50 pixels when zoomed out
        // Threshold is approx Zoom 13 (19.1 m/px)
        const minLineLengthPixels = 50;
        const maxResolutionForSmallLines = 19.1; // meters per pixel (approx Zoom 13)

        if (lengthPixels < minLineLengthPixels && resolution > maxResolutionForSmallLines) {
            return null; // Hide text for small lines (including dots) when zoomed out
        }
    }

    // For polygons, check if they're too small when zoomed out
    // Note: This includes polygons < 2 pixels which are rendered as points (dots)
    if ((geometryType === 'Polygon' || geometryType === 'MultiPolygon') && resolution !== undefined && resolution > 0) {
        const extent = geometry.getExtent();
        const widthMeters = extent[2] - extent[0];  // maxX - minX
        const heightMeters = extent[3] - extent[1]; // maxY - minY

        // Convert to screen pixels (meters / meters per pixel = pixels)
        const widthPixels = widthMeters / resolution;
        const heightPixels = heightMeters / resolution;

        // Hide text for polygons < 50 pixels when zoomed out
        // Threshold is approx Zoom 13 (19.1 m/px)
        const minPolygonSizePixels = 50;
        const maxResolutionForSmallPolygons = 19.1; // meters per pixel (approx Zoom 13)

        const minDimensionPixels = Math.min(widthPixels, heightPixels);
        if (minDimensionPixels < minPolygonSizePixels && resolution > maxResolutionForSmallPolygons) {
            return null; // Hide text for small polygons (including dots) when zoomed out
        }
    }

    // Create text style for labels (different positioning for each geometry type)
    let textStyle: Text;
    let styleGeometry: any = undefined;

    if (geometryType === 'Point') {
        // Points: text below, closer to the point
        // Use offsetY: 8 for PNG icons, offsetY: 15 for default circle icons (non-PNG)
        const iconUrl = getIconUrl(properties);
        const iconFailed = feature.get('_iconFailed');
        const hasWorkingIcon = iconUrl && !iconFailed;
        const offsetY = hasWorkingIcon ? 8 : 15;
        textStyle = createTextStyle(name, geometryType, offsetY);
    } else if (geometryType === 'Polygon' || geometryType === 'MultiPolygon') {
        // For polygons, check if label would intersect with borders
        // If so, place label below polygon (like points) instead of at centroid
        let shouldPlaceBelow = false;
        let offsetY = 0;
        let placement: string | undefined = 'point';

        if (resolution !== undefined && resolution > 0) {
            const strokeWidth = properties['stroke-width'] || 2;
            shouldPlaceBelow = checkLabelBorderIntersection(geometry, name, resolution, strokeWidth);

            if (shouldPlaceBelow) {
                // Place label below polygon, similar to points
                offsetY = 15; // Same offset as default circle icons for points
                placement = null; // Remove 'point' placement to use extent-based positioning

                // Use the bottom of the polygon extent for label placement
                const extent = geometry.getExtent();
                const bottomCenter = [getCenter(extent)[0], extent[1]]; // [centerX, minY]
                styleGeometry = new Point(bottomCenter);
            }
        }

        textStyle = createTextStyle(name, geometryType, offsetY, placement);
    } else {
        textStyle = createTextStyle(name, geometryType);
    }

    const styleConfig: any = {
        text: textStyle
    };

    // Use custom geometry for text placement when label is placed below polygon
    if (styleGeometry) {
        styleConfig.geometry = styleGeometry;
    }

    return new Style(styleConfig);
}

