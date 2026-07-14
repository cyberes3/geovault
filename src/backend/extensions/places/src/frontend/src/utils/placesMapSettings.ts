export const PLACES_DEFAULT_MAP_SOURCE_KEY = 'extensions.places.defaultMapSourceId';
export const PLACES_FALLBACK_MAP_SOURCE_ID = 'osm';

function normalizeMapSourceId(value: unknown): string {
  if (typeof value !== 'string') return PLACES_FALLBACK_MAP_SOURCE_ID;
  const normalized = value.trim();
  return normalized || PLACES_FALLBACK_MAP_SOURCE_ID;
}

/**
 * Read default Places basemap from the shared platformState bridge, using the same dot-key
 * rules as loadSettingsFromValues / SettingsInput (Live Track uses getNestedValue for
 * extensions.live_track.default_map).
 */
export function getDefaultMapSourceId(): string {
  const platformState = window.gv_core.platformState;
  const getNestedValue = window.gv_core.GeoVault.utils.getNestedValue;
  const settings = platformState.userSettings.value;
  if (!settings) {
    return PLACES_FALLBACK_MAP_SOURCE_ID;
  }
  const raw = getNestedValue(settings, PLACES_DEFAULT_MAP_SOURCE_KEY);
  return normalizeMapSourceId(raw);
}

interface EnsureUserSettingsLoadedOptions {
  waitMs?: number;
  pollMs?: number;
}

/** Wait for App.vue settings fetch (or fetch once) so the map starts on the user's basemap. */
export async function ensureUserSettingsLoaded({ waitMs = 3000, pollMs = 50 }: EnsureUserSettingsLoadedOptions = {}): Promise<void> {
  const platformState = window.gv_core.platformState;
  const isLoaded = (): boolean => platformState.userSettings.value != null;
  if (isLoaded()) {
    return;
  }
  const deadline = Date.now() + waitMs;
  while (!isLoaded() && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, pollMs));
  }
  if (isLoaded()) {
    return;
  }
  await platformState.fetchUserSettings();
}
