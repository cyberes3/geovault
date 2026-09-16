import type { TileSource } from '@/api/services/tilesApi';

export const GEOJSON_SOURCE_ID = 'geojson-data';

export const BASE_TILE_LAYER_IDS = ['raster-layer', 'osm-layer', 'tile-layer'] as const;

export const FEATURE_LAYER_STACK = [
    'polygons',
    'polygon-outlines',
    'lines',
    'points',
    'replacement-points',
    'point-icons',
] as const;

export type FeatureLayerId = (typeof FEATURE_LAYER_STACK)[number];

export const CLICK_HIT_RADIUS_PX = 15;
export const HOVER_HIT_RADIUS_PX = 5;
export const MAX_VISIBLE_LABELS = 200;

export const POINT_LAYER_FILTER = [
    'all',
    ['==', ['geometry-type'], 'Point'],
    ['!', ['has', '_on_border']],
    ['!', ['has', '_isLabelPoint']],
    ['!', ['has', '_isSmallFeatureReplacement']],
    ['!', ['has', '_icon-id']],
] as const;

export const REPLACEMENT_POINT_FILTER = [
    'all',
    ['==', ['geometry-type'], 'Point'],
    ['has', '_isSmallFeatureReplacement'],
] as const;

export const POINT_ICON_FILTER = [
    'all',
    ['==', ['geometry-type'], 'Point'],
    ['!', ['has', '_on_border']],
    ['!', ['has', '_isLabelPoint']],
    ['!', ['has', '_isSmallFeatureReplacement']],
    ['has', '_icon-id'],
] as const;

export const LINE_LAYER_FILTER = [
    'all',
    ['any', ['==', ['geometry-type'], 'LineString'], ['==', ['geometry-type'], 'MultiLineString']],
    ['!', ['has', '_isTooSmall']],
] as const;

export const POLYGON_LAYER_FILTER = [
    'all',
    ['any', ['==', ['geometry-type'], 'Polygon'], ['==', ['geometry-type'], 'MultiPolygon']],
    ['!', ['has', '_isTooSmall']],
] as const;

export function sourceWantsAtmosphere(source: TileSource | null | undefined): boolean {
    if (!source) return false;
    if (source.id === 'global-imagery' || source.id === 'google-satellite-hybrid') return true;
    const mapId = source.client_config?.map_id;
    return mapId === 'satellite' || mapId === 'hybrid';
}
