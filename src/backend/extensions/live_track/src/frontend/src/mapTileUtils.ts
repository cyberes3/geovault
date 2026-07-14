/**
 * Shared tile source and layer helpers for MapLibre raster layers.
 * Used by LiveTrackView and WorldShareView. Delegates tile-URL building to core's shared
 * `RasterTileUrls` class (via `window.gv_core`) instead of reimplementing it, so live_track can't
 * drift from how the main map builds the same raster spec.
 */
import type { Map as MapLibreMap } from 'maplibre-gl';
import type { TileSource, TileSourceClientConfig } from './types/gv-core';

const DEFAULT_MAX_ZOOM = 18;
const DEFAULT_LAYER_MAX_ZOOM = 19;

export const defaultOsmSource: TileSource = {
  id: window.gv_core.OSM_TILE_SOURCE_ID,
  name: 'OpenStreetMap',
  type: 'osm',
  client_config: {
    url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
    tileSize: 256,
    attribution: '© OpenStreetMap'
  }
};

export function getRasterSourceSpec(layerValue: string, tileSource: TileSource | null | undefined): Record<string, unknown> {
  const clientConfig = tileSource?.client_config ?? {};
  return {
    type: 'raster',
    tiles: window.gv_core.RasterTileUrls.fromTileSource(tileSource ?? { id: layerValue }),
    tileSize: clientConfig.tileSize ?? 256,
    attribution: clientConfig.attribution ?? ''
  };
}

export function getRasterLayerMaxZoom(clientConfig: TileSourceClientConfig | null | undefined, maxZoomDefault = DEFAULT_MAX_ZOOM, layerMaxZoomDefault = DEFAULT_LAYER_MAX_ZOOM): number {
  return Math.max(clientConfig?.maxzoom ?? maxZoomDefault, layerMaxZoomDefault);
}

export interface ReplaceRasterBaseLayerOptions {
  sourceId: string;
  layerId: string;
  sourceSpec: Record<string, unknown>;
  layerSpec: Record<string, unknown>;
  insertBeforeLayerId?: string;
}

/**
 * Replace the raster base layer and source on an existing map (raster mode).
 * Removes existing layer and source if present, then adds the new source and layer.
 */
export function replaceRasterBaseLayer(map: MapLibreMap | null | undefined, { sourceId, layerId, sourceSpec, layerSpec, insertBeforeLayerId }: ReplaceRasterBaseLayerOptions): void {
  if (!map) return;
  if (map.getLayer(layerId)) map.removeLayer(layerId);
  if (map.getSource(sourceId)) map.removeSource(sourceId);
  map.addSource(sourceId, sourceSpec);
  map.addLayer(
    layerSpec,
    insertBeforeLayerId ?? undefined
  );
}
