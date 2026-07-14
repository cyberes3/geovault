/**
 * Composable for tile sources and selected layer state.
 * Used by LiveTrackView and WorldShareView. Loads from core's shared `tileSourceCatalog`
 * singleton (via `window.gv_core`) instead of a raw fetch, so live_track shares the same
 * cache/in-flight request and error handling as the rest of the app.
 */
import { ref, type Ref } from 'vue';
import { defaultOsmSource } from './mapTileUtils';
import type { TileSource } from './types/gv-core';

export interface UseTileSourcesOptions {
  defaultSource?: TileSource;
  afterFetch?: (tileSourcesRef: Ref<TileSource[]>, selectedLayerRef: Ref<string>) => void;
}

export function useTileSources({ defaultSource = defaultOsmSource, afterFetch }: UseTileSourcesOptions = {}) {
  const tileSources = ref<TileSource[]>([defaultSource]);
  const selectedLayer = ref<string>(defaultSource.id);

  async function fetchTileSources(): Promise<void> {
    try {
      const sources = await window.gv_core.tileSourceCatalog.load();
      tileSources.value = sources.length > 0 ? sources : [defaultSource];
      if (typeof afterFetch === 'function') {
        afterFetch(tileSources, selectedLayer);
      }
      if (!tileSources.value.some((s) => s.id === selectedLayer.value)) {
        selectedLayer.value = tileSources.value[0]?.id || defaultSource.id;
      }
    } catch (e) {
      console.warn('useTileSources: fetch failed', e);
      tileSources.value = [defaultSource];
      selectedLayer.value = defaultSource.id;
    }
  }

  return { tileSources, selectedLayer, fetchTileSources };
}
