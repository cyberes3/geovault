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
