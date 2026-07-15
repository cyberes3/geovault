/**
 * Feature management utilities for MapLibre
 */

import { markRaw } from 'vue';
import { bbox as turfBbox } from '@turf/bbox';
import { point } from '@turf/helpers';
import { distance } from '@turf/distance';
import { length } from '@turf/length';
import type { Map as MapLibreMap, GeoJSONSource } from 'maplibre-gl';
import type { Geometry as GeoJsonGeometry } from 'geojson';
import { filterPointsOnBorders } from './featureFiltering.js';
import { ensureLayersExist } from './layerManagement.js';
import { getFeatureIconUrl, getIconSourceUrl, shouldUseIcon, loadIconImage } from './featureStyling.js';
import { detectPrimaryColor } from '@/utils/map/iconUtils';
import { calculatePolygonCentroid, calculateLineCenter, calculatePolygonBottomCenter } from './labelPlacement.js';
import { checkLabelBorderIntersection, getResolutionFromZoom } from './labelMarkers.js';
import type { GeoJsonFeature, GeoJsonFeatureCollection } from '@/types/geospatial';
import type { MapFeature } from './mapFeatureTypes.js';

// Web Mercator constant (matches OpenLayers)
const WEB_MERCATOR_WORLD_SIZE = 156543.03392; // meters per pixel at zoom 0

/** Calculate the screen size of a polygon in pixels using Turf.js for accuracy. */
function calculatePolygonScreenSize(geometry: GeoJsonFeature['geometry'], zoom: number): { widthPixels: number; heightPixels: number } {
    if (!geometry.coordinates) {
        return { widthPixels: 0, heightPixels: 0 };
    }

    try {
        // Use Turf.js to get bbox (returns [minLon, minLat, maxLon, maxLat])
        const [minLon, minLat, maxLon, maxLat] = turfBbox(geometry as GeoJsonGeometry);

        // Create points at the bbox corners to measure distances
        const bottomLeft = point([minLon, minLat]);
        const bottomRight = point([maxLon, minLat]);
        const topLeft = point([minLon, maxLat]);

        // Calculate geodesic distances in meters
        const widthMeters = distance(bottomLeft, bottomRight, { units: 'meters' });
        const heightMeters = distance(bottomLeft, topLeft, { units: 'meters' });

        // Convert to pixels using Web Mercator resolution
        const resolution = WEB_MERCATOR_WORLD_SIZE / Math.pow(2, zoom);
        const widthPixels = widthMeters / resolution;
        const heightPixels = heightMeters / resolution;

        return { widthPixels, heightPixels };
    } catch (e) {
        console.warn('Error calculating polygon screen size:', e);
        return { widthPixels: 0, heightPixels: 0 };
    }
}

/** Calculate the screen size of a line in pixels using Turf.js for accuracy. */
function calculateLineScreenSize(geometry: GeoJsonFeature['geometry'], zoom: number): number {
    if (!geometry.coordinates) {
        return 0;
    }

    try {
        // Use Turf.js to calculate geodesic length in meters
        const lengthMeters = length({ type: 'Feature', properties: {}, geometry: geometry as GeoJsonGeometry }, { units: 'meters' });

        // Convert to pixels using Web Mercator resolution
        const resolution = WEB_MERCATOR_WORLD_SIZE / Math.pow(2, zoom);
        return lengthMeters / resolution;
    } catch (e) {
        console.warn('Error calculating line screen size:', e);
        return 0;
    }
}

function getGeoJsonSourceData(map: MapLibreMap): GeoJsonFeatureCollection {
    const source: GeoJSONSource | undefined = map.getSource('geojson-data');
    if (!source) {
        return { type: 'FeatureCollection', features: [] };
    }
    // Use serialize() method for MapLibre v5 compatibility
    const data = source.serialize().data;
    return typeof data === 'string' ? { type: 'FeatureCollection', features: [] } : (data as GeoJsonFeatureCollection);
}

/**
 * Update small feature flags for all features at the current zoom level.
 * Optimized to prioritize visible features for better performance.
 */
export function updateSmallFeatureFlags(map: MapLibreMap, zoom: number | null): void {
    if (zoom === null) return;
    const source: GeoJSONSource | undefined = map.getSource('geojson-data');
    if (!source) return;

    const currentData = getGeoJsonSourceData(map);
    const features = currentData.features as MapFeature[];

    if (features.length === 0) return;

    // Get visible features first to prioritize processing them
    let visibleFeatureIds = new Set<string>();
    try {
        const visibleFeatures = map.queryRenderedFeatures(undefined, {
            layers: ['lines', 'polygons', 'polygon-outlines'],
        });
        visibleFeatureIds = new Set(
            visibleFeatures
                .map((f) => f.properties.database_id as string | number | undefined)
                .filter((id): id is string | number => id !== undefined)
                .map((id) => String(id)),
        );
    } catch (e) {
        // If query fails, process all features (fallback)
        console.warn('Failed to query visible features, processing all:', e);
    }

    const MIN_PIXEL_SIZE = 2;
    let needsUpdate = false;
    const replacementPointsToAdd: MapFeature[] = [];
    const featuresToKeep: MapFeature[] = [];

    // Separate visible and non-visible features for prioritized processing
    const visibleFeaturesToProcess: MapFeature[] = [];
    const nonVisibleFeatures: MapFeature[] = [];

    // First pass: separate features by visibility
    for (const feature of features) {
        // Skip label points - always keep them
        if (feature.properties._isLabelPoint) {
            featuresToKeep.push(feature);
            continue;
        }

        // Remove old replacement points (they'll be regenerated if needed)
        if (feature.properties._isSmallFeatureReplacement) {
            needsUpdate = true;
            continue;
        }

        const featureId = feature.properties.database_id;
        if (featureId && visibleFeatureIds.has(String(featureId))) {
            visibleFeaturesToProcess.push(feature);
        } else {
            nonVisibleFeatures.push(feature);
        }
    }

    // Process visible features first (higher priority), then non-visible.
    // This ensures visible features get updated immediately while non-visible
    // features are processed when they come into view.
    const processFeature = (feature: MapFeature): void => {
        const geometry = feature.geometry;
        const geometryType = geometry.type;

        // Only process polygons and lines (points don't need this check)
        if (
            geometryType !== 'Polygon' &&
            geometryType !== 'MultiPolygon' &&
            geometryType !== 'LineString' &&
            geometryType !== 'MultiLineString'
        ) {
            featuresToKeep.push(feature);
            return;
        }

        // Check if polygon or line is too small
        let isSmallFeature = false;
        let replacementCenter: number[] | null = null;
        let replacementColor: string | undefined;

        if (geometryType === 'Polygon' || geometryType === 'MultiPolygon') {
            const { widthPixels, heightPixels } = calculatePolygonScreenSize(geometry, zoom);
            if (widthPixels < MIN_PIXEL_SIZE || heightPixels < MIN_PIXEL_SIZE) {
                isSmallFeature = true;
                replacementCenter = calculatePolygonCentroid(geometry);
                replacementColor = feature.properties.stroke || '#ff0000';
            }
        } else {
            const lengthPixels = calculateLineScreenSize(geometry, zoom);
            if (lengthPixels < MIN_PIXEL_SIZE) {
                isSmallFeature = true;
                replacementCenter = calculateLineCenter(geometry);
                replacementColor = feature.properties.stroke || '#ff0000';
            }
        }

        // Update the flag
        const wasSmall = feature.properties._isTooSmall === true;
        if (isSmallFeature && !wasSmall) {
            feature.properties._isTooSmall = true;
            needsUpdate = true;
        } else if (!isSmallFeature && wasSmall) {
            delete feature.properties._isTooSmall;
            needsUpdate = true;
        }

        featuresToKeep.push(feature);

        // Create replacement point if needed
        if (isSmallFeature && replacementCenter) {
            replacementPointsToAdd.push({
                type: 'Feature',
                properties: {
                    database_id: `${feature.properties.database_id}_small_replacement`,
                    name: feature.properties.name,
                    _isSmallFeatureReplacement: true,
                    _originalFeatureId: feature.properties.database_id,
                    _originalGeometryType: geometryType,
                    'marker-color': replacementColor,
                },
                geometry: {
                    type: 'Point',
                    coordinates: replacementCenter,
                },
            });
        }
    };

    // Process visible features first (priority)
    for (const feature of visibleFeaturesToProcess) {
        processFeature(feature);
    }

    // Process non-visible features (they'll be updated when they come into view).
    // For performance, limit processing of off-screen features per call.
    const MAX_NON_VISIBLE_TO_PROCESS = 1000;
    const nonVisibleToProcess = nonVisibleFeatures.slice(0, MAX_NON_VISIBLE_TO_PROCESS);
    for (const feature of nonVisibleToProcess) {
        processFeature(feature);
    }

    // Keep remaining non-visible features as-is (they'll be processed when visible)
    for (let i = MAX_NON_VISIBLE_TO_PROCESS; i < nonVisibleFeatures.length; i++) {
        featuresToKeep.push(nonVisibleFeatures[i]);
    }

    // Only update if something changed
    if (needsUpdate || replacementPointsToAdd.length > 0) {
        const allFeatures = [...featuresToKeep, ...replacementPointsToAdd];
        const updatedCollection: GeoJsonFeatureCollection = {
            type: 'FeatureCollection',
            features: allFeatures.map((f) => markRaw(f)),
        };
        source.setData(markRaw(updatedCollection));
    }
}

/**
 * Extract elevation and timestamps from geometry coordinates and store in properties.
 * MapLibre strips the 3rd coordinate (elevation) when storing internally, and may not
 * preserve coordinateProperties, so we need to preserve them ourselves.
 */
function preserveElevationInProperties(feature: MapFeature): MapFeature {
    const geometry = feature.geometry;
    const coords: unknown = geometry.coordinates;

    const asArray = (value: unknown): unknown[] | null => (Array.isArray(value) ? (value as unknown[]) : null);
    const thirdCoord = (coord: unknown): unknown => {
        const arr = asArray(coord);
        return arr && arr.length >= 3 && arr[2] != null ? arr[2] : undefined;
    };

    const preserveTimes = (): void => {
        if (feature.properties.coordinateProperties?.times) {
            feature.properties._coordinateProperties ??= {};
            feature.properties._coordinateProperties.times = feature.properties.coordinateProperties.times;
        }
    };

    if (geometry.type === 'Point') {
        const elevation = thirdCoord(coords);
        if (elevation !== undefined) {
            feature.properties._elevation = elevation;
        }
    } else if (geometry.type === 'MultiPoint') {
        const points = asArray(coords);
        if (points && points.length > 0) {
            const elevation = thirdCoord(points[0]);
            if (elevation !== undefined) {
                feature.properties._elevation = elevation;
            }
        }
    } else if (geometry.type === 'LineString') {
        const points = asArray(coords);
        if (points && points.length > 0) {
            const elevations = points.map(thirdCoord).filter((elevation) => elevation !== undefined);
            if (elevations.length > 0) {
                feature.properties._elevations = elevations;
            }
        }

        preserveTimes();
    } else if (geometry.type === 'MultiLineString') {
        const lines = asArray(coords);
        if (lines && lines.length > 0) {
            const elevations: unknown[] = [];
            lines.forEach((lineCoords) => {
                asArray(lineCoords)?.forEach((coord) => {
                    const elevation = thirdCoord(coord);
                    if (elevation !== undefined) {
                        elevations.push(elevation);
                    }
                });
            });

            if (elevations.length > 0) {
                feature.properties._elevations = elevations;
            }
        }

        preserveTimes();
    }

    return feature;
}

/**
 * Process features to add icon metadata and prepare for rendering.
 * Also handles small polygon/line replacement with colored dots.
 */
async function processFeaturesForIcons(
    features: MapFeature[],
    map: MapLibreMap,
    zoom: number,
    replaceIconsLowZoom = true,
): Promise<MapFeature[]> {
    const processedFeatures: MapFeature[] = [];
    const iconLoadPromises: Promise<unknown>[] = [];
    const colorDetectionPromises: Promise<unknown>[] = [];
    const featuresNeedingColorDetection: MapFeature[] = [];
    const MIN_PIXEL_SIZE = 2; // Minimum size threshold in pixels

    for (const feature of features) {
        // Skip label points - they don't need icon processing
        if (feature.properties._isLabelPoint) {
            processedFeatures.push(feature);
            continue;
        }

        const geometry = feature.geometry;
        const geometryType = geometry.type;

        // Check if polygon or line is too small to render at current zoom.
        // If so, mark it as small and add a replacement point alongside it.
        let isSmallFeature = false;
        let replacementCenter: number[] | null = null;
        let replacementColor: string | undefined;

        if (geometryType === 'Polygon' || geometryType === 'MultiPolygon') {
            const { widthPixels, heightPixels } = calculatePolygonScreenSize(geometry, zoom);
            if (widthPixels < MIN_PIXEL_SIZE || heightPixels < MIN_PIXEL_SIZE) {
                isSmallFeature = true;
                replacementCenter = calculatePolygonCentroid(geometry);
                replacementColor = feature.properties.stroke || '#ff0000';
            }
        } else if (geometryType === 'LineString' || geometryType === 'MultiLineString') {
            const lengthPixels = calculateLineScreenSize(geometry, zoom);
            if (lengthPixels < MIN_PIXEL_SIZE) {
                isSmallFeature = true;
                replacementCenter = calculateLineCenter(geometry);
                replacementColor = feature.properties.stroke || '#ff0000';
            }
        }

        // Mark the feature if it's too small (will be filtered by layer filters)
        if (isSmallFeature) {
            feature.properties._isTooSmall = true;
        } else {
            delete feature.properties._isTooSmall;
        }

        // If we need a replacement point, add both the original feature and replacement
        if (isSmallFeature && replacementCenter) {
            processedFeatures.push(feature);

            processedFeatures.push({
                type: 'Feature',
                properties: {
                    database_id: `${feature.properties.database_id}_small_replacement`,
                    name: feature.properties.name,
                    _isSmallFeatureReplacement: true,
                    _originalFeatureId: feature.properties.database_id,
                    _originalGeometryType: geometryType,
                    'marker-color': replacementColor,
                },
                geometry: {
                    type: 'Point',
                    coordinates: replacementCenter,
                },
            });
            continue;
        }

        // For non-small polygons/lines, just add the feature
        if (geometryType !== 'Point') {
            processedFeatures.push(feature);
            continue;
        }

        // Normal icon processing for point features only
        const iconUrl = getFeatureIconUrl(feature.properties);
        const shouldUseIconImage = iconUrl && shouldUseIcon(zoom, iconUrl, replaceIconsLowZoom);

        if (shouldUseIconImage) {
            const resolvedUrl = getIconSourceUrl(iconUrl, feature.properties);
            const iconId = `icon-${resolvedUrl.replace(/[^a-zA-Z0-9]/g, '_')}`;

            feature.properties['_icon-id'] = iconId;

            iconLoadPromises.push(
                loadIconImage(map, iconId, resolvedUrl).catch((err: unknown) => {
                    console.warn(`Failed to load icon ${iconId}:`, err);
                    delete feature.properties['_icon-id'];
                }),
            );

            processedFeatures.push(feature);
        } else if (iconUrl) {
            // Icon exists but should be replaced with circle at low zoom - detect its primary color.
            const resolvedUrl = getIconSourceUrl(iconUrl, feature.properties);

            if (feature.properties['_detectedIconColor']) {
                delete feature.properties['_icon-id'];
                processedFeatures.push(feature);
            } else {
                if (!feature.properties['_colorDetectionInProgress']) {
                    feature.properties['_colorDetectionInProgress'] = true;

                    const colorDetectionPromise = detectPrimaryColor(resolvedUrl)
                        .then((color: string) => {
                            feature.properties['_detectedIconColor'] = color;
                            feature.properties['_colorDetectionInProgress'] = false;
                            return feature;
                        })
                        .catch(() => {
                            feature.properties['_detectedIconColor'] = feature.properties['marker-color'] || '#ff0000';
                            feature.properties['_colorDetectionInProgress'] = false;
                            return feature;
                        });

                    colorDetectionPromises.push(colorDetectionPromise);
                } else {
                    // Color detection already in progress - wait for it by polling the feature properties.
                    const colorDetectionPromise = new Promise((resolve) => {
                        const checkInterval = setInterval(() => {
                            if (feature.properties['_detectedIconColor'] && !feature.properties['_colorDetectionInProgress']) {
                                clearInterval(checkInterval);
                                resolve(feature);
                            }
                        }, 50);

                        setTimeout(() => {
                            clearInterval(checkInterval);
                            if (!feature.properties['_detectedIconColor']) {
                                feature.properties['_detectedIconColor'] = feature.properties['marker-color'] || '#ff0000';
                                feature.properties['_colorDetectionInProgress'] = false;
                            }
                            resolve(feature);
                        }, 5000);
                    });

                    colorDetectionPromises.push(colorDetectionPromise);
                }

                featuresNeedingColorDetection.push(feature);
                delete feature.properties['_icon-id'];
            }
        } else {
            delete feature.properties['_icon-id'];
            processedFeatures.push(feature);
        }
    }

    // Wait for all icons to load and color detection to complete
    await Promise.all([...iconLoadPromises, ...colorDetectionPromises]);

    // Add features that were waiting for color detection
    for (const feature of featuresNeedingColorDetection) {
        if (feature.properties['_detectedIconColor'] && !feature.properties['_colorDetectionInProgress']) {
            processedFeatures.push(feature);
        }
    }

    return processedFeatures;
}

/**
 * Add features to the map, merging with existing features and filtering points on borders.
 *
 * Perf: merges are common (every viewport bbox load re-merges against what's already on the
 * map), so this only calls the expensive `source.setData()` (and the icon/label reprocessing
 * that precedes it) when the merge actually changed something - a new feature, a new label
 * point, or a border-point filtering change. Returns the resulting merged FeatureCollection so
 * callers can update their own caches without a redundant follow-up `source.serialize()` call.
 */
export async function addFeaturesToMap(
    map: MapLibreMap,
    geojsonData: GeoJsonFeatureCollection,
    showAllLabels = true,
    zoom: number | null = null,
    replaceIconsLowZoom = true,
): Promise<GeoJsonFeatureCollection | null> {
    const source: GeoJSONSource | undefined = map.getSource('geojson-data');
    if (!source) return null;

    const currentData = getGeoJsonSourceData(map);
    const currentFeatures = currentData.features as MapFeature[];

    const existingFeatures = new Map<string, MapFeature>();
    const existingLabelPoints = new Map<string, MapFeature>(); // Track existing label points

    // Separate existing features from label points and replacement points
    currentFeatures.forEach((f) => {
        if (f.properties._isLabelPoint) {
            const originalId = f.properties._originalFeatureId;
            if (originalId) {
                existingLabelPoints.set(String(originalId), f);
            }
        } else if (f.properties._isSmallFeatureReplacement) {
            // Skip replacement points - they will be regenerated if needed
        } else {
            const id = f.properties.database_id;
            existingFeatures.set(String(id), f);
        }
    });

    // Merge new features, avoiding duplicates
    const newFeatures = geojsonData.features as MapFeature[];
    const newFeatureIds = new Set<string>();
    const existingFeatureCountBeforeMerge = existingFeatures.size;
    newFeatures.forEach((f) => {
        // Preserve elevation in properties before processing
        preserveElevationInProperties(f);

        const id = f.properties.database_id;
        const idStr = String(id);
        newFeatureIds.add(idStr);
        if (!existingFeatures.has(idStr)) {
            existingFeatures.set(idStr, f);
        }
    });
    const addedNewFeature = existingFeatures.size > existingFeatureCountBeforeMerge;

    // Filter out points that are on polygon/line borders
    const allFeatures = Array.from(existingFeatures.values());
    const filteredFeatures = filterPointsOnBorders(allFeatures);
    const bordersChangedCount = filteredFeatures.length !== allFeatures.length;

    // Add label points only if labels are visible. This significantly improves performance
    // when labels are disabled, as we skip creating extra Point features / centroid math.
    const featuresWithLabels: MapFeature[] = [];
    const labelPointsToAdd = new Map<string, MapFeature>();
    const labelPointsAdded = new Set<string>();

    filteredFeatures.forEach((feature) => {
        featuresWithLabels.push(feature);

        if (showAllLabels) {
            const name = feature.properties.name;
            const featureId = feature.properties.database_id;
            const geometry = feature.geometry;

            if (name && String(name).trim() !== '' && featureId) {
                const featureIdStr = String(featureId);
                const hasExistingLabelPoint = existingLabelPoints.has(featureIdStr);
                const isNewFeature = newFeatureIds.has(featureIdStr);
                const needsLabelPoint =
                    geometry.type === 'Polygon' ||
                    geometry.type === 'MultiPolygon' ||
                    geometry.type === 'LineString' ||
                    geometry.type === 'MultiLineString';

                if (needsLabelPoint && !labelPointsAdded.has(featureIdStr)) {
                    if (hasExistingLabelPoint) {
                        // ALWAYS preserve existing label point - never recalculate, so labels
                        // stay at the exact same position. Deep copy avoids reference issues.
                        const existingLabelPoint = existingLabelPoints.get(featureIdStr) as MapFeature;
                        featuresWithLabels.push(JSON.parse(JSON.stringify(existingLabelPoint)));
                        labelPointsAdded.add(featureIdStr);
                    } else if (isNewFeature) {
                        let labelPoint: number[] | null = null;
                        let placeLabelBelow = false;

                        if (geometry.type === 'Polygon' || geometry.type === 'MultiPolygon') {
                            const centroid = calculatePolygonCentroid(geometry);

                            // Use a fixed zoom level (10) for consistent label placement across zoom levels.
                            if (centroid) {
                                const fixedZoom = 10;
                                const resolution = getResolutionFromZoom(fixedZoom);
                                const strokeWidth = feature.properties['stroke-width'] ?? 2;
                                const shouldPlaceBelow = checkLabelBorderIntersection(
                                    geometry,
                                    centroid,
                                    feature.properties.name,
                                    resolution,
                                    strokeWidth,
                                );

                                if (shouldPlaceBelow) {
                                    labelPoint = calculatePolygonBottomCenter(geometry);
                                    placeLabelBelow = true;
                                } else {
                                    labelPoint = centroid;
                                }
                            } else {
                                labelPoint = centroid;
                            }
                        } else if (geometry.type === 'LineString' || geometry.type === 'MultiLineString') {
                            labelPoint = calculateLineCenter(geometry);
                        }

                        if (labelPoint) {
                            labelPointsToAdd.set(featureIdStr, {
                                type: 'Feature',
                                id: `label-point-${featureId}`, // Stable ID for MapLibre to track
                                properties: {
                                    ...feature.properties,
                                    _isLabelPoint: true,
                                    _originalFeatureId: featureId,
                                    _placeLabelBelow: placeLabelBelow,
                                },
                                geometry: {
                                    type: 'Point',
                                    coordinates: labelPoint,
                                },
                            });
                            labelPointsAdded.add(featureIdStr);
                        }
                    }
                }
            }
        }
    });

    if (showAllLabels) {
        labelPointsToAdd.forEach((labelPoint) => {
            featuresWithLabels.push(labelPoint);
        });
    }
    const addedNewLabelPoint = labelPointsToAdd.size > 0;

    // Nothing actually changed (every "new" feature was already on the map, no label points were
    // added, and border filtering removed nothing) - skip icon reprocessing and the setData() call
    // entirely. The existing source data (and this same merged collection) is already correct.
    if (!addedNewFeature && !addedNewLabelPoint && !bordersChangedCount) {
        return { type: 'FeatureCollection', features: featuresWithLabels };
    }

    // Process features for icons if zoom is provided.
    // Note: label points don't need icon processing, but processFeaturesForIcons will skip them.
    let processedFeatures = featuresWithLabels;
    if (zoom !== null) {
        processedFeatures = await processFeaturesForIcons(featuresWithLabels, map, zoom, replaceIconsLowZoom);
    }

    // Wrap each feature in markRaw to prevent Vue reactivity - critical for performance with
    // complex geometries (many coordinates). Also wrap the entire data object.
    const rawFeatures = processedFeatures.map((f) => markRaw(f));
    const mergedCollection: GeoJsonFeatureCollection = { type: 'FeatureCollection', features: rawFeatures };

    source.setData(markRaw(mergedCollection));

    // Add layers if they don't exist
    ensureLayersExist(map, showAllLabels);

    return mergedCollection;
}
