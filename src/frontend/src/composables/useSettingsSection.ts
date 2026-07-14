import { computed, reactive, watch, onBeforeUnmount, type ComputedRef } from 'vue';
import { useStore } from 'vuex';
import { updateUserSetting } from '@/utils/userSettingsService';
import { toastApiError } from '@/utils/apiError';
import { keyValueToNested, getNestedValue } from '@/utils/settingsUtils';

/** A single setting's persisted value: whatever `SettingsInput` can render/emit. */
export type SettingValue = string | number | boolean;

/** One choice in a `radio`/`select` setting's `options` list. */
export interface SettingOption {
    value: string | number;
    label: string;
    description?: string;
}

/** Input widget `SettingsInput` renders for a given setting. */
export type SettingInputType = 'radio' | 'toggle' | 'checkbox' | 'select' | 'text' | 'number' | 'textarea';

export interface SettingDefinition {
    key: string;
    section: string;
    title: string;
    type: SettingInputType;
    defaultValue: SettingValue;
    label?: string;
    description?: string;
    placeholder?: string;
    options?: SettingOption[];
    min?: number;
    max?: number;
    step?: number;
    rows?: number;
    [extra: string]: unknown;
}

const SAVE_DEBOUNCE_MS = 500;
const SUCCESS_CHECKMARK_DURATION_MS = 3000;

interface RootGetters {
    'userSettings/userSettings': Record<string, unknown> | null;
}

export interface UseSettingsSectionResult {
    /** `settingsConfig` filtered down to this section, in declared order. */
    sectionSettings: ComputedRef<SettingDefinition[]>;
    /** Current value per setting key, seeded from the store and kept in sync with it. */
    settingsValues: Record<string, SettingValue | undefined>;
    /** Whether the "saved" checkmark should currently show for a given setting key. */
    successCheckmarks: Record<string, boolean>;
    /** Update a setting's value immediately and debounce-save it to the server. */
    handleSettingChange: (settingKey: string, value: SettingValue) => void;
}

/**
 * Reactive state + save pipeline for one settings tab. Filters `settingsConfig` to `section`,
 * mirrors the Vuex `userSettings` store module into a local `settingsValues` map (updating
 * whenever the store changes), and debounce-saves edits back to the server via
 * `updateUserSetting`, reverting to the last known-good value on failure. Any saves still
 * pending when the owning component unmounts are flushed immediately.
 */
export function useSettingsSection(settingsConfig: SettingDefinition[], section: string): UseSettingsSectionResult {
    const store = useStore();

    const settingsValues = reactive<Record<string, SettingValue | undefined>>({});
    const successCheckmarks = reactive<Record<string, boolean>>({});
    const saveTimers: Record<string, ReturnType<typeof setTimeout>> = {};
    const checkmarkTimers: Record<string, ReturnType<typeof setTimeout>> = {};

    const sectionSettings = computed(() => settingsConfig.filter((setting) => setting.section === section));

    function loadSettingsFromStore(): void {
        const getters = store.getters as RootGetters;
        const settings = getters['userSettings/userSettings'] ?? {};
        for (const setting of settingsConfig) {
            const value = getNestedValue(settings, setting.key) as SettingValue | undefined;
            const newValue = value ?? setting.defaultValue;
            if (settingsValues[setting.key] !== newValue) {
                settingsValues[setting.key] = newValue;
            }
        }
    }

    async function saveSetting(settingKey: string, value: SettingValue): Promise<void> {
        try {
            const nestedUpdate = keyValueToNested(settingKey, value) as Record<string, unknown>;
            const response = await updateUserSetting(nestedUpdate);

            const savedValue = getNestedValue(response.settings, settingKey) as SettingValue | undefined;
            if (savedValue !== undefined) {
                settingsValues[settingKey] = savedValue;
            }
            void store.dispatch('userSettings/setUserSettings', response.settings);

            successCheckmarks[settingKey] = true;
            clearTimeout(checkmarkTimers[settingKey]);
            checkmarkTimers[settingKey] = setTimeout(() => {
                successCheckmarks[settingKey] = false;
            }, SUCCESS_CHECKMARK_DURATION_MS);
        } catch (error) {
            console.error(`Error saving setting ${settingKey}:`, error);
            // Revert to the last known-good value from the store.
            loadSettingsFromStore();
            toastApiError(error, 'An error occurred while saving the setting.');
        }
    }

    function debouncedSave(settingKey: string, value: SettingValue): void {
        clearTimeout(saveTimers[settingKey]);
        saveTimers[settingKey] = setTimeout(() => {
            delete saveTimers[settingKey];
            void saveSetting(settingKey, value);
        }, SAVE_DEBOUNCE_MS);
    }

    function handleSettingChange(settingKey: string, value: SettingValue): void {
        settingsValues[settingKey] = value;
        debouncedSave(settingKey, value);
    }

    function flushPendingSaves(): void {
        for (const settingKey of Object.keys(saveTimers)) {
            clearTimeout(saveTimers[settingKey]);
            delete saveTimers[settingKey];
            const value = settingsValues[settingKey];
            if (value !== undefined) {
                saveSetting(settingKey, value).catch((error: unknown) => {
                    console.error(`Error flushing save for ${settingKey}:`, error);
                });
            }
        }
    }

    // Keep local values in sync with the store (e.g. another tab/tab-reload updating settings).
    watch(() => (store.getters as RootGetters)['userSettings/userSettings'], loadSettingsFromStore, { deep: true, immediate: true });

    onBeforeUnmount(() => {
        flushPendingSaves();
        Object.values(checkmarkTimers).forEach(clearTimeout);
    });

    return {
        sectionSettings,
        settingsValues,
        successCheckmarks,
        handleSettingChange,
    };
}
