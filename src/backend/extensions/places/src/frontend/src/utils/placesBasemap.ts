import type { TileSource } from '../types/gv-core';

const RasterTileUrls = window.gv_core.RasterTileUrls;

export function isStyleBasedSource(tileSource: TileSource): boolean {
  return !!(tileSource.client_config.style_url ?? tileSource.type === 'maptiler');
}

interface RasterSourceSpec {
  type: 'raster';
  tiles: string[];
  tileSize: number;
  attribution: string;
  [key: string]: unknown;
}

export function buildRasterSourceSpec(tileSource: TileSource): RasterSourceSpec {
  const clientConfig = tileSource.client_config;
  return {
    type: 'raster',
    tiles: RasterTileUrls.fromTileSource(tileSource),
    tileSize: clientConfig.tileSize ?? 256,
    attribution: clientConfig.attribution ?? '',
  };
}

interface RasterStyleOptions {
  sourceId?: string;
  layerId?: string;
}

export function buildRasterStyle(tileSource: TileSource, { sourceId = 'base-raster', layerId = 'base-raster-layer' }: RasterStyleOptions = {}): Record<string, unknown> {
  const clientConfig = tileSource.client_config;
  const sourceSpec = buildRasterSourceSpec(tileSource);
  const minzoom = clientConfig.minzoom ?? 0;
  const maxzoom = Math.max(clientConfig.maxzoom ?? 18, 19);
  return {
    version: 8,
    sources: {
      [sourceId]: sourceSpec,
    },
    layers: [
      {
        id: layerId,
        type: 'raster',
        source: sourceId,
        minzoom,
        maxzoom,
      },
    ],
  };
}

export interface TileSourceSelectOption {
  id: string;
  name: string;
  type: string;
}

export function getTileSourceSelectOptions(tileSources: TileSource[]): TileSourceSelectOption[] {
  if (tileSources.length === 0) {
    return [];
  }
  return tileSources.map((source) => ({
    id: source.id,
    name: source.name || source.id || 'Unnamed source',
    type: source.type || 'unknown',
  }));
}
