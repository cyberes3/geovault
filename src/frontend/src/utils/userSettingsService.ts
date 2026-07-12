import { updateUserSettings as updateUserSettingsApi, clearHiddenFeatures as clearHiddenFeaturesApi } from '@/api/services/userApi';
import { getNestedValue } from '@/utils/settingsUtils';
import { ApiError } from '@/utils/apiError';

/**
 * Update user settings on the server with a partial nested JSON object.
 * @param settingsUpdate - Partial nested settings object (e.g. `{map: {elevation_profile_source: 'api'}}`)
 */
export async function updateUserSetting(settingsUpdate: Record<string, unknown>): Promise<{ success: true; settings: Record<string, unknown> }> {
    const data = await updateUserSettingsApi(settingsUpdate);
    if (!data.settings) {
        throw new ApiError('Failed to save setting.');
    }
    return { success: true, settings: data.settings };
}

/**
 * Clear all hidden feature IDs for the current account.
 * Frontend keeps a local cache, so the backend only returns a status code.
 */
export async function clearHiddenFeatures(): Promise<void> {
    await clearHiddenFeaturesApi();
}

/**
 * Pick out a flat `{ key: value }` map for a settings-tab schema from the current nested user
 * settings object, falling back to each entry's `defaultValue`. Exposed to extensions via
 * `window.gv_core.GeoVault.utils.loadSettingsFromValues` (see `@/extensions/extensionLoader`);
 * core settings tabs get equivalent reactive behavior from the `useSettingsSection` composable
 * instead.
 * @param config - Settings configuration array
 * @param settings - The current nested user settings object (e.g. `platformState.userSettings.value`)
 */
export function loadSettingsFromValues(
    config: Array<{ key: string; defaultValue: unknown }>,
    settings: Record<string, unknown> | null
): Record<string, unknown> {
    if (!Array.isArray(config)) {
        console.warn('loadSettingsFromValues: config must be an array');
        return {};
    }

    const settingsValues: Record<string, unknown> = {};
    config.forEach((setting) => {
        const value = getNestedValue(settings ?? {}, setting.key);
        settingsValues[setting.key] = value !== undefined ? value : setting.defaultValue;
    });

    return settingsValues;
}
