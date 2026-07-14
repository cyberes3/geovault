import { ref, type Ref } from 'vue';
import type { TileSource, TileSourceCatalog } from '@/types/gv-core';

/** Reuse core's singleton catalog instance so places shares its cache/in-flight fetch with the rest of the app. */
const sharedCatalog: TileSourceCatalog = window.gv_core.tileSourceCatalog;
const OSM_TILE_SOURCE_ID = window.gv_core.OSM_TILE_SOURCE_ID;

export interface BaseSourceOption {
  id: string;
  name: string;
}

export interface UseTileSourcesReturn {
  tileSources: Ref<TileSource[]>;
  selectedBaseSourceId: Ref<string>;
  baseSourceOptions: Ref<BaseSourceOption[]>;
  loadTileSources: () => Promise<void>;
  resolveSource: (preferredId?: string) => TileSource;
  catalog: TileSourceCatalog;
}

export function useTileSources(): UseTileSourcesReturn {
  const tileSources: Ref<TileSource[]> = ref([]);
  const selectedBaseSourceId = ref(OSM_TILE_SOURCE_ID);
  const baseSourceOptions: Ref<BaseSourceOption[]> = ref([]);

  async function loadTileSources(): Promise<void> {
    const sources = await sharedCatalog.load();
    tileSources.value = sources;
    baseSourceOptions.value = sources.map((source) => ({
      id: source.id,
      name: source.name || source.id || 'Unnamed source',
    }));
    if (!sources.some((source) => source.id === selectedBaseSourceId.value)) {
      selectedBaseSourceId.value = sources[0]?.id ?? OSM_TILE_SOURCE_ID;
    }
  }

  function resolveSource(preferredId: string = selectedBaseSourceId.value): TileSource {
    return sharedCatalog.resolveSource(tileSources.value, preferredId);
  }

  return {
    tileSources,
    selectedBaseSourceId,
    baseSourceOptions,
    loadTileSources,
    resolveSource,
    catalog: sharedCatalog,
  };
}
