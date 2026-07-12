/**
 * Narrow, read-mostly bridge onto core app state for extensions.
 *
 * Extensions used to receive the raw Vuex `store` instance, which gave them commit/dispatch
 * access to every module (auth, map, importQueue, websocket, ...) even though in practice every
 * extension only ever touched `userSettings`. This bridge exposes exactly that surface and
 * nothing else, so extensions can't reach into unrelated core state.
 */
import { computed, type ComputedRef } from 'vue';
import type { Store } from 'vuex';
import { updateUserSetting } from '@/utils/userSettingsService';

interface UserSettingsRootGetters {
    'userSettings/userSettings': Record<string, unknown> | null;
}

export interface PlatformStateBridge {
    /** Reactive snapshot of the current user's settings (nested object), or null before first load. */
    readonly userSettings: ComputedRef<Record<string, unknown> | null>;
    /** Force a refetch of user settings from the server into the shared cache. */
    fetchUserSettings(): Promise<void>;
    /**
     * Persist a partial nested settings update to the server and sync the shared cache.
     * Returns the full, merged settings object.
     */
    saveUserSetting(update: Record<string, unknown>): Promise<Record<string, unknown>>;
}

export function createPlatformStateBridge(store: Store<unknown>): PlatformStateBridge {
    return {
        userSettings: computed(() => (store.getters as UserSettingsRootGetters)['userSettings/userSettings']),
        async fetchUserSettings() {
            await store.dispatch('userSettings/fetchUserSettings');
        },
        async saveUserSetting(update) {
            const { settings } = await updateUserSetting(update);
            await store.dispatch('userSettings/setUserSettings', settings);
            return settings;
        }
    };
}
