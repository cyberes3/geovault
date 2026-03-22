export const PLACES_DEFAULT_MAP_SOURCE_KEY = 'extensions.places.defaultMapSourceId';
export const PLACES_FALLBACK_MAP_SOURCE_ID = 'osm';

function normalizeMapSourceId(value) {
  if (typeof value !== 'string') return PLACES_FALLBACK_MAP_SOURCE_ID;
  const normalized = value.trim();
  return normalized || PLACES_FALLBACK_MAP_SOURCE_ID;
}

function getStore() {
  return window.gv_core?.store || null;
}

export function getDefaultMapSourceIdFromStore() {
  const settings = getStore()?.state?.userSettings;
  const value = settings?.extensions?.places?.defaultMapSourceId;
  return normalizeMapSourceId(value);
}
