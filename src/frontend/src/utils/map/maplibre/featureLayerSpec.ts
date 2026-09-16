import type { Map as MapLibreMap } from 'maplibre-gl';
import { getIconUrl, resolveIconUrl, isSystemIcon } from '@/utils/map/iconUtils';
import { APIHOST } from '@/config';
import {
    GEOJSON_SOURCE_ID,
    LINE_LAYER_FILTER,
    POINT_ICON_FILTER,
    POINT_LAYER_FILTER,
    POLYGON_LAYER_FILTER,
    REPLACEMENT_POINT_FILTER,
} from '@/utils/map/mapLayers';

export type MapLibreExpression = unknown[];

export interface FeatureLayerOverrides {
    layout?: Record<string, unknown>;
    paint?: Record<string, unknown>;
}

export interface LayerConfig {
    id: string;
    type: string;
    source: string;
    filter?: MapLibreExpression;
    layout?: Record<string, unknown>;
    paint?: Record<string, unknown>;
}

const HIGHLIGHT_ACTIVE: MapLibreExpression = [
    'any',
    ['boolean', ['feature-state', 'hovered'], false],
    ['boolean', ['feature-state', 'selected'], false],
];

const HIGHLIGHT_SCALE: MapLibreExpression = [
    'case',
    HIGHLIGHT_ACTIVE,
    1.5,
    1,
];

function scaleForHighlight(length: number): MapLibreExpression {
    return ['*', length, HIGHLIGHT_SCALE];
}

export function createZoomBasedRadiusExpression(baseRadius: number, minRadius: number, baseZoom = 10, scaleFactor = 0.6): MapLibreExpression {
    const exponentialBase = Math.pow(2, scaleFactor);
    const zoomAtMin = baseZoom + Math.log2(minRadius / baseRadius) / scaleFactor;
    return [
        'interpolate',
        ['exponential', exponentialBase],
        ['zoom'],
        zoomAtMin, scaleForHighlight(minRadius),
        baseZoom, scaleForHighlight(baseRadius),
        22, scaleForHighlight(baseRadius),
    ];
}

function getColorExpression(propertyName: string, defaultColor = '#ff0000'): MapLibreExpression {
    return ['coalesce', ['get', propertyName], defaultColor];
}

export function getPointColorExpression(): MapLibreExpression {
    return ['coalesce', ['get', '_detectedIconColor'], ['get', 'marker-color'], '#ff0000'];
}

export function getLineColorExpression(): MapLibreExpression {
    return getColorExpression('stroke', '#ff0000');
}

export function getPolygonFillColorExpression(): MapLibreExpression {
    return getColorExpression('fill', '#ff0000');
}

export function getPolygonStrokeColorExpression(): MapLibreExpression {
    return getColorExpression('stroke', '#ff0000');
}

export function getStrokeWidthExpression(defaultWidth = 2): MapLibreExpression {
    return ['coalesce', ['get', 'stroke-width'], defaultWidth];
}

export function getFillOpacityExpression(): MapLibreExpression {
    return ['coalesce', ['get', 'fill-opacity'], 0.3];
}

export const defaultFeatureStyles = {
    points: {
        paint: {
            'circle-radius': createZoomBasedRadiusExpression(4, 2),
            'circle-color': getPointColorExpression(),
            'circle-stroke-width': 1,
            'circle-stroke-color': '#000000',
            'circle-stroke-opacity': 1,
        },
    },
    lines: {
        layout: {
            'line-cap': 'round',
            'line-join': 'round',
        },
        paint: {
            'line-color': getLineColorExpression(),
            'line-width': ['*', getStrokeWidthExpression(2), HIGHLIGHT_SCALE],
            'line-opacity': 1,
        },
    },
    polygons: {
        paint: {
            'fill-color': getPolygonFillColorExpression(),
            'fill-opacity': getFillOpacityExpression(),
        },
    },
    polygonOutlines: {
        layout: {
            'line-cap': 'round',
            'line-join': 'round',
        },
        paint: {
            'line-color': getPolygonStrokeColorExpression(),
            'line-width': ['*', getStrokeWidthExpression(2), HIGHLIGHT_SCALE],
            'line-opacity': 1,
        },
    },
};

export function getPointLayerConfig(overrides: FeatureLayerOverrides = {}): LayerConfig {
    return {
        id: 'points',
        type: 'circle',
        source: GEOJSON_SOURCE_ID,
        filter: [...POINT_LAYER_FILTER],
        paint: {
            ...defaultFeatureStyles.points.paint,
            ...overrides.paint,
        },
    };
}

export function getReplacementPointLayerConfig(overrides: FeatureLayerOverrides = {}): LayerConfig {
    return {
        id: 'replacement-points',
        type: 'circle',
        source: GEOJSON_SOURCE_ID,
        filter: [...REPLACEMENT_POINT_FILTER],
        paint: {
            'circle-radius': createZoomBasedRadiusExpression(3, 1.5),
            'circle-color': getPointColorExpression(),
            'circle-stroke-width': 1,
            'circle-stroke-color': '#000000',
            'circle-stroke-opacity': 1,
            ...overrides.paint,
        },
    };
}

export function getPointIconLayerConfig(overrides: Partial<LayerConfig> = {}): LayerConfig {
    return {
        ...overrides,
        id: 'point-icons',
        type: 'symbol',
        source: GEOJSON_SOURCE_ID,
        filter: [...POINT_ICON_FILTER],
        layout: {
            'icon-image': ['coalesce', ['get', '_icon-id'], ''],
            'icon-size': 1,
            'icon-anchor': 'bottom',
            'icon-allow-overlap': true,
            'icon-ignore-placement': true,
            ...overrides.layout,
        },
        paint: {
            'icon-halo-color': '#ffffff',
            'icon-halo-width': ['case', HIGHLIGHT_ACTIVE, 2, 0],
            ...overrides.paint,
        },
    };
}

export function getLineLayerConfig(overrides: FeatureLayerOverrides = {}): LayerConfig {
    return {
        id: 'lines',
        type: 'line',
        source: GEOJSON_SOURCE_ID,
        filter: [...LINE_LAYER_FILTER],
        layout: {
            ...defaultFeatureStyles.lines.layout,
            ...overrides.layout,
        },
        paint: {
            ...defaultFeatureStyles.lines.paint,
            ...overrides.paint,
        },
    };
}

export function getPolygonLayerConfig(overrides: FeatureLayerOverrides = {}): LayerConfig {
    return {
        id: 'polygons',
        type: 'fill',
        source: GEOJSON_SOURCE_ID,
        filter: [...POLYGON_LAYER_FILTER],
        paint: {
            ...defaultFeatureStyles.polygons.paint,
            ...overrides.paint,
        },
    };
}

export function getPolygonOutlineLayerConfig(overrides: FeatureLayerOverrides = {}): LayerConfig {
    return {
        id: 'polygon-outlines',
        type: 'line',
        source: GEOJSON_SOURCE_ID,
        filter: [...POLYGON_LAYER_FILTER],
        layout: {
            ...defaultFeatureStyles.polygonOutlines.layout,
            ...overrides.layout,
        },
        paint: {
            ...defaultFeatureStyles.polygonOutlines.paint,
            ...overrides.paint,
        },
    };
}

export const FEATURE_LAYER_CONFIGS: Record<string, () => LayerConfig> = {
    polygons: getPolygonLayerConfig,
    'polygon-outlines': getPolygonOutlineLayerConfig,
    lines: getLineLayerConfig,
    points: getPointLayerConfig,
    'replacement-points': getReplacementPointLayerConfig,
    'point-icons': getPointIconLayerConfig,
};

export function getFeatureIconUrl(properties: Record<string, unknown> | null | undefined): string | null {
    if (!properties) return null;
    return getIconUrl(properties);
}

export function getIconSourceUrl(iconUrl: string, properties: Record<string, unknown> | null | undefined): string {
    const builtInIcon = isSystemIcon(iconUrl);
    const markerColor = properties?.['marker-color'];
    if (builtInIcon && typeof markerColor === 'string' && markerColor) {
        const iconPathForRecolor = iconUrl.replace('/api/icons/system/', '');
        const encodedColor = encodeURIComponent(markerColor);
        const encodedIcon = encodeURIComponent(iconPathForRecolor);
        return `${APIHOST}/api/icons/recolor/?icon=${encodedIcon}&color=${encodedColor}`;
    }
    return resolveIconUrl(iconUrl);
}

export function shouldUseIcon(zoom: number, iconUrl: string | null | undefined, replaceIconsLowZoom = true): boolean {
    if (!iconUrl) return false;
    if (!replaceIconsLowZoom || zoom > 8) return true;
    return false;
}

export async function loadIconImage(map: MapLibreMap, iconId: string, iconUrl: string): Promise<void> {
    return new Promise((resolve, reject) => {
        if (map.hasImage(iconId)) {
            resolve();
            return;
        }

        const img = new Image();
        img.crossOrigin = 'anonymous';

        img.onload = () => {
            try {
                if (!map.hasImage(iconId)) {
                    const canvas = document.createElement('canvas');
                    canvas.width = 20;
                    canvas.height = 20;
                    const ctx = canvas.getContext('2d');
                    if (!ctx) {
                        reject(new Error('Could not get canvas context'));
                        return;
                    }
                    ctx.drawImage(img, 0, 0, 20, 20);
                    map.addImage(iconId, ctx.getImageData(0, 0, 20, 20));
                }
                resolve();
            } catch (error) {
                if (error instanceof Error && error.message.includes('already exists')) {
                    resolve();
                } else {
                    reject(error);
                }
            }
        };

        img.onerror = () => {
            reject(new Error(`Failed to load icon: ${iconUrl}`));
        };

        img.src = iconUrl;
    });
}

export function iconRuntimeId(resolvedUrl: string): string {
    return `icon-${resolvedUrl.replace(/[^a-zA-Z0-9]/g, '_')}`;
}
