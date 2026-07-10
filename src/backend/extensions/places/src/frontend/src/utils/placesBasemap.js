import { RasterTileUrls } from 'platform/utils/map/tileSources/RasterTileUrls.js';

export function isStyleBasedSource(tileSource) {
  const clientConfig = tileSource?.client_config || {};
  return !!(clientConfig.style_url || tileSource?.type === 'maptiler');
}

export function buildRasterSourceSpec(tileSource) {
  const clientConfig = tileSource?.client_config || {};
  return {
    type: 'raster',
    tiles: RasterTileUrls.fromTileSource(tileSource),
    tileSize: clientConfig.tileSize || 256,
    attribution: clientConfig.attribution || '',
  };
}

export function buildRasterStyle(tileSource, { sourceId = 'base-raster', layerId = 'base-raster-layer' } = {}) {
  const clientConfig = tileSource?.client_config || {};
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

export function getTileSourceSelectOptions(tileSources) {
  if (!Array.isArray(tileSources) || tileSources.length === 0) {
    return [];
  }
  return tileSources.map((source) => ({
    id: source.id,
    name: source.name || source.id || 'Unnamed source',
    type: source.type || 'unknown',
  }));
}
