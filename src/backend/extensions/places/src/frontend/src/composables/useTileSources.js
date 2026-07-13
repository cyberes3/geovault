import { ref } from 'vue';

/** Reuse core's singleton catalog instance so places shares its cache/in-flight fetch with the rest of the app. */
const sharedCatalog = window.gv_core.tileSourceCatalog;
const OSM_TILE_SOURCE_ID = window.gv_core.OSM_TILE_SOURCE_ID;

/**
 * @returns {{ tileSources: import('vue').Ref<object[]>, selectedBaseSourceId: import('vue').Ref<string>, baseSourceOptions: import('vue').Ref<{ id: string, name: string }[]>, loadTileSources: () => Promise<void> }}
 */
export function useTileSources() {
  const tileSources = ref([]);
  const selectedBaseSourceId = ref(OSM_TILE_SOURCE_ID);
  const baseSourceOptions = ref([]);

  async function loadTileSources() {
    const sources = await sharedCatalog.load();
    tileSources.value = sources;
    baseSourceOptions.value = sources.map((source) => ({
      id: source.id,
      name: source.name || source.id || 'Unnamed source',
    }));
    if (!sources.some((source) => source.id === selectedBaseSourceId.value)) {
      selectedBaseSourceId.value = sources[0]?.id || OSM_TILE_SOURCE_ID;
    }
  }

  function resolveSource(preferredId = selectedBaseSourceId.value) {
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
