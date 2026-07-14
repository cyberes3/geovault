export const PLACES_DEFAULT_MAP_SOURCE_KEY = 'extensions.places.defaultMapSourceId';
export const PLACES_FALLBACK_MAP_SOURCE_ID = 'osm';

function normalizeMapSourceId(value) {
  if (typeof value !== 'string') return PLACES_FALLBACK_MAP_SOURCE_ID;
  const normalized = value.trim();
  return normalized || PLACES_FALLBACK_MAP_SOURCE_ID;
}

/**
 * Read default Places basemap from the shared platformState bridge, using the same dot-key
 * rules as loadSettingsFromValues / SettingsInput (Live Track uses getNestedValue for
 * extensions.live_track.default_map).
 */
export function getDefaultMapSourceId() {
  const platformState = window.gv_core?.platformState;
  const getNestedValue = window.gv_core?.GeoVault?.utils?.getNestedValue;
  const settings = platformState?.userSettings?.value;
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
  const platformState = window.gv_core?.platformState;
  if (!platformState || platformState.userSettings.value != null) {
    return;
  }
  const deadline = Date.now() + waitMs;
  while (platformState.userSettings.value == null && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, pollMs));
  }
  if (platformState.userSettings.value != null) {
    return;
  }
  await platformState.fetchUserSettings();
}
