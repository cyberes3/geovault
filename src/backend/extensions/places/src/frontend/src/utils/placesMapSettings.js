export const PLACES_DEFAULT_MAP_SOURCE_KEY = 'extensions.places.defaultMapSourceId';
export const PLACES_FALLBACK_MAP_SOURCE_ID = 'osm';

function normalizeMapSourceId(value) {
  if (typeof value !== 'string') return PLACES_FALLBACK_MAP_SOURCE_ID;
  const normalized = value.trim();
  return normalized || PLACES_FALLBACK_MAP_SOURCE_ID;
}

/**
 * Read default Places basemap from the Vuex store using the same dot-key rules as
 * loadSettingsFromStore / SettingsInput (Live Track uses getNestedValue for extensions.live_track.default_map).
 */
export function getDefaultMapSourceIdFromStore() {
  const store = window.gv_core?.store || null;
  const getNestedValue = window.gv_core?.GeoVault?.utils?.getNestedValue;
  const settings = store?.state?.userSettings;
  if (!settings) {
    return PLACES_FALLBACK_MAP_SOURCE_ID;
  }
  const raw = typeof getNestedValue === 'function'
    ? getNestedValue(settings, PLACES_DEFAULT_MAP_SOURCE_KEY)
    : settings?.extensions?.places?.defaultMapSourceId;
  return normalizeMapSourceId(raw);
}

/** Wait for App.vue settings fetch (or fetch once) so the map starts on the user's basemap. */
export async function ensureUserSettingsLoaded({ waitMs = 3000, pollMs = 50 } = {}) {
  const store = window.gv_core?.store;
  if (!store || store.state.userSettings != null) {
    return;
  }
  const deadline = Date.now() + waitMs;
  while (store.state.userSettings == null && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, pollMs));
  }
  if (store.state.userSettings != null) {
    return;
  }
  await store.dispatch('fetchUserSettings');
}
