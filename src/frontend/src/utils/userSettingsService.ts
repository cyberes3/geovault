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
 * Load settings from Vuex store with defaults from configuration. This is a pure, standalone
 * helper exposed to extensions via `window.gv_core.utils.loadSettingsFromStore` (see `main.js`);
 * core settings tabs get equivalent reactive behavior from the `useSettingsSection` composable
 * instead.
 * @param config - Settings configuration array
 * @param store - Vuex store instance (or store state)
 */
export function loadSettingsFromStore(config: Array<{ key: string; defaultValue: unknown }>, store: any): Record<string, unknown> {
    if (!Array.isArray(config)) {
        console.warn('loadSettingsFromStore: config must be an array');
        return {};
    }

    const settings = store?.getters?.['userSettings/userSettings'] || store?.userSettings || store || {};
    const settingsValues: Record<string, unknown> = {};

    config.forEach((setting) => {
        const value = getNestedValue(settings, setting.key);
        settingsValues[setting.key] = value !== undefined ? value : setting.defaultValue;
    });

    return settingsValues;
}
