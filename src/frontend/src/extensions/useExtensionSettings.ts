import { getNestedValue, keyValueToNested } from '@/utils/settingsUtils';
import type { PlatformStateBridge } from './platformState';

export function assertExtensionSettingsUpdate(extensionName: string, update: Record<string, unknown>): void {
    const prefix = `extensions.${extensionName}`;
    const allowed = update.extensions;
    if (typeof allowed !== 'object' || allowed === null || Array.isArray(allowed)) {
        throw new Error(`Settings writes must be namespaced to ${prefix}.*`);
    }
    const keys = Object.keys(allowed);
    if (keys.length !== 1 || keys[0] !== extensionName) {
        throw new Error(`Settings writes must be namespaced to ${prefix}.*`);
    }
}

export function useExtensionSettings(extensionName: string, platformState: PlatformStateBridge) {
    return {
        get(key: string): unknown {
            const fullKey = key.startsWith(`extensions.${extensionName}.`)
                ? key
                : `extensions.${extensionName}.${key}`;
            return getNestedValue(platformState.userSettings.value, fullKey);
        },
        async save(key: string, value: unknown): Promise<Record<string, unknown>> {
            const fullKey = key.startsWith(`extensions.${extensionName}.`)
                ? key
                : `extensions.${extensionName}.${key}`;
            if (!fullKey.startsWith(`extensions.${extensionName}.`)) {
                throw new Error(`Settings writes must be namespaced to extensions.${extensionName}.*`);
            }
            const update = keyValueToNested(fullKey, value) as Record<string, unknown>;
            assertExtensionSettingsUpdate(extensionName, update);
            return platformState.saveUserSetting(update);
        },
    };
}

export function scopePlatformStateToExtension(
    platformState: PlatformStateBridge,
    extensionName: string
): PlatformStateBridge {
    return {
        userSettings: platformState.userSettings,
        currentUser: platformState.currentUser,
        fetchUserSettings: () => platformState.fetchUserSettings(),
        async saveUserSetting(update: Record<string, unknown>) {
            assertExtensionSettingsUpdate(extensionName, update);
            return platformState.saveUserSetting(update);
        },
    };
}
