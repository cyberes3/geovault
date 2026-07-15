import type { Module } from 'vuex';
import type { RootState } from '../rootState';

/** Chrome's `beforeinstallprompt` event, captured here and replayed on demand. */
export interface BeforeInstallPromptEvent extends Event {
    prompt: () => Promise<void>;
    userChoice: Promise<{ outcome: 'accepted' | 'dismissed'; platform: string }>;
}

export interface ExtensionsRuntimeState {
    /** Path prefixes for extension routes that use full-height map layout (from API). */
    mapRoutePrefixes: string[];
    /** Path prefixes for extension public share routes (no auth required; from API). */
    publicShareRoutePrefixes: string[];
    /** Captured `beforeinstallprompt` event, deferred until the user asks to install the PWA. */
    deferredPrompt: BeforeInstallPromptEvent | null;
}

/** Runtime metadata about installed extensions, plus PWA install-prompt state. */
export const extensionsRuntimeModule: Module<ExtensionsRuntimeState, RootState> = {
    namespaced: true,
    state: (): ExtensionsRuntimeState => ({
        mapRoutePrefixes: [],
        publicShareRoutePrefixes: [],
        deferredPrompt: null,
    }),
    getters: {
        mapRoutePrefixes: (state) => state.mapRoutePrefixes,
        publicShareRoutePrefixes: (state) => state.publicShareRoutePrefixes,
        deferredPrompt: (state) => state.deferredPrompt,
    },
    mutations: {
        SET_MAP_ROUTE_PREFIXES(state, prefixes: string[]) {
            state.mapRoutePrefixes = Array.isArray(prefixes) ? prefixes : [];
        },
        SET_PUBLIC_SHARE_ROUTE_PREFIXES(state, prefixes: string[]) {
            state.publicShareRoutePrefixes = Array.isArray(prefixes) ? prefixes : [];
        },
        SET_DEFERRED_PROMPT(state, payload: BeforeInstallPromptEvent | null) {
            state.deferredPrompt = payload;
        },
    },
    actions: {
        setMapRoutePrefixes({ commit }, prefixes: string[]) {
            commit('SET_MAP_ROUTE_PREFIXES', prefixes);
        },
        setPublicShareRoutePrefixes({ commit }, prefixes: string[]) {
            commit('SET_PUBLIC_SHARE_ROUTE_PREFIXES', prefixes);
        },
        setDeferredPrompt({ commit }, payload: BeforeInstallPromptEvent | null) {
            commit('SET_DEFERRED_PROMPT', payload);
        },
    },
};
