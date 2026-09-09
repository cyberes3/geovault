import type { Module } from 'vuex';
import { getUserSettings } from '@/api/services/userApi';
import { settingsReady, type SettingsReadyStatus } from '@/utils/settings/SettingsReady';
import type { RootState } from '../rootState';

export interface HiddenFeature {
    id: string;
    name: string | null;
    geometry_type: string | null;
}

export interface UserSettingsState {
    userSettings: Record<string, any> | null;
    hiddenFeatures: HiddenFeature[];
    status: SettingsReadyStatus;
}

function normalizeHiddenFeatures(payload: unknown): HiddenFeature[] {
    if (!Array.isArray(payload)) {
        return [];
    }
    return payload.map((item) => {
        if (typeof item === 'string') {
            return { id: item, name: null, geometry_type: null };
        }
        if (item && typeof item === 'object' && 'id' in item) {
            const record = item as Record<string, unknown>;
            return {
                id: String(record.id),
                name: (record.name as string) || null,
                geometry_type: (record.geometry_type as string) || null,
            };
        }
        return { id: String(item), name: null, geometry_type: null };
    });
}

/**
 * User settings + the locally-cached hidden-feature list. Depends on `auth/userInfo`
 * being present before fetching (settings are per-account).
 */
export const userSettingsModule: Module<UserSettingsState, RootState> = {
    namespaced: true,
    state: (): UserSettingsState => ({
        userSettings: null,
        hiddenFeatures: [],
        status: 'idle',
    }),
    getters: {
        userSettings: (state) => state.userSettings,
        hiddenFeatures: (state) => state.hiddenFeatures,
        settingsStatus: (state) => state.status,
    },
    mutations: {
        SET_USER_SETTINGS(state, payload: Record<string, any> | null) {
            state.userSettings = payload;
        },
        SET_SETTINGS_STATUS(state, payload: SettingsReadyStatus) {
            state.status = payload;
        },
        SET_HIDDEN_FEATURES(state, payload: unknown) {
            state.hiddenFeatures = normalizeHiddenFeatures(payload);
        },
        ADD_HIDDEN_FEATURE(state, payload: { featureId: string; featureName?: string | null; geometryType?: string | null }) {
            const id = String(payload.featureId);
            if (!state.hiddenFeatures.some((f) => f.id === id)) {
                state.hiddenFeatures.push({
                    id,
                    name: payload.featureName || null,
                    geometry_type: payload.geometryType || null,
                });
            }
        },
        REMOVE_HIDDEN_FEATURE(state, featureId: string) {
            const id = String(featureId);
            state.hiddenFeatures = state.hiddenFeatures.filter((f) => f.id !== id);
        },
    },
    actions: {
        async fetchUserSettings({ commit, state, rootGetters }) {
            const getters = rootGetters as Record<string, unknown>;
            if (!getters['auth/userInfo']) {
                settingsReady.reset();
                commit('SET_SETTINGS_STATUS', 'idle');
                commit('SET_USER_SETTINGS', null);
                return null;
            }
            if (settingsReady.status === 'ready') {
                return state.userSettings;
            }

            let loaded: Record<string, unknown> | null = null;
            try {
                await settingsReady.run(async () => {
                    const data = await getUserSettings();
                    loaded = data.settings ?? {};
                    commit('SET_USER_SETTINGS', loaded);
                    commit('SET_HIDDEN_FEATURES', data.hidden_features ?? []);
                });
                commit('SET_SETTINGS_STATUS', settingsReady.status);
                return loaded;
            } catch (error) {
                console.error('Error fetching user settings:', error);
                commit('SET_SETTINGS_STATUS', 'error');
                return null;
            }
        },
        setUserSettings({ commit }, settings: Record<string, any> | null) {
            commit('SET_USER_SETTINGS', settings);
            if (settings != null) {
                settingsReady.status = 'ready';
                commit('SET_SETTINGS_STATUS', 'ready');
            }
        },
        clearUserSettings({ commit }) {
            settingsReady.reset();
            commit('SET_SETTINGS_STATUS', 'idle');
            commit('SET_USER_SETTINGS', null);
            commit('SET_HIDDEN_FEATURES', []);
        },
        setHiddenFeatures({ commit }, payload: unknown) {
            commit('SET_HIDDEN_FEATURES', payload);
        },
        addHiddenFeature({ commit }, payload: { featureId: string; featureName?: string | null; geometryType?: string | null }) {
            commit('ADD_HIDDEN_FEATURE', payload);
        },
        removeHiddenFeature({ commit }, featureId: string) {
            commit('REMOVE_HIDDEN_FEATURE', featureId);
        },
    },
};
