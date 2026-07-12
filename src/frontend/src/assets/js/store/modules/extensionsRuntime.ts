import type { Module } from 'vuex';

export interface ExtensionsRuntimeState {
    /** Path prefixes for extension routes that use full-height map layout (from API). */
    mapRoutePrefixes: string[];
    /** Path prefixes for extension public share routes (no auth required; from API). */
    publicShareRoutePrefixes: string[];
    /** Captured `beforeinstallprompt` event, deferred until the user asks to install the PWA. */
    deferredPrompt: any | null;
}

/** Runtime metadata about installed extensions, plus PWA install-prompt state. */
export const extensionsRuntimeModule: Module<ExtensionsRuntimeState, any> = {
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
        SET_DEFERRED_PROMPT(state, payload: any) {
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
        setDeferredPrompt({ commit }, payload: any) {
            commit('SET_DEFERRED_PROMPT', payload);
        },
    },
};
