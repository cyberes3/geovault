/**
 * Shared tile source and layer helpers for MapLibre raster layers.
 * Used by LiveTrackView and WorldShareView.
 */

const DEFAULT_MAX_ZOOM = 18;
const DEFAULT_LAYER_MAX_ZOOM = 19;

export const defaultOsmSource = {
  id: 'osm',
  name: 'OpenStreetMap',
  type: 'osm',
  client_config: {
    url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
    tileSize: 256,
    attribution: '© OpenStreetMap'
  }
};

export function getRasterSourceSpec(layerValue, tileSource) {
  const clientConfig = tileSource?.client_config || {};
  const url = clientConfig.url || `/api/tiles/${layerValue}/{z}/{x}/{y}`;
  let tiles;
  if (clientConfig.tileSubdomains && Array.isArray(clientConfig.tileSubdomains)) {
    tiles = clientConfig.tileSubdomains.map((sub) => url.replace('{s}', sub));
  } else {
    tiles = [url.replace('{s}', clientConfig.tileSubdomains?.[0] || 'a')];
  }
  return {
    type: 'raster',
    tiles,
    tileSize: clientConfig.tileSize || 256,
    attribution: clientConfig.attribution || ''
  };
}

export function getRasterLayerMaxZoom(clientConfig, maxZoomDefault = DEFAULT_MAX_ZOOM, layerMaxZoomDefault = DEFAULT_LAYER_MAX_ZOOM) {
  return Math.max(clientConfig?.maxzoom ?? maxZoomDefault, layerMaxZoomDefault);
}

/**
 * Replace the raster base layer and source on an existing map (raster mode).
 * Removes existing layer and source if present, then adds the new source and layer.
 * @param {import('maplibre-gl').Map} map - MapLibre map instance
 * @param {{ sourceId: string, layerId: string, sourceSpec: Object, layerSpec: Object, insertBeforeLayerId?: string }} options
 */
export function replaceRasterBaseLayer(map, { sourceId, layerId, sourceSpec, layerSpec, insertBeforeLayerId }) {
  if (!map) return;
  if (map.getLayer(layerId)) map.removeLayer(layerId);
  if (map.getSource(sourceId)) map.removeSource(sourceId);
  map.addSource(sourceId, sourceSpec);
  map.addLayer(
    layerSpec,
    insertBeforeLayerId || undefined
  );
}
