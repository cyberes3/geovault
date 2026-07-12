import type { Module } from 'vuex';

export interface WebSocketState {
    connected: boolean;
    reconnectAttempts: number;
    realtimeData: Record<string, any>;
}

/** Connection status + last-known-good payload per realtime module (see `assets/js/websocket/modules`). */
export const websocketModule: Module<WebSocketState, any> = {
    namespaced: true,
    state: (): WebSocketState => ({
        connected: false,
        reconnectAttempts: 0,
        realtimeData: {},
    }),
    getters: {
        connected: (state) => state.connected,
        reconnectAttempts: (state) => state.reconnectAttempts,
        moduleData: (state) => (moduleName: string) => state.realtimeData[moduleName],
    },
    mutations: {
        SET_CONNECTED(state, connected: boolean) {
            state.connected = connected;
        },
        SET_RECONNECT_ATTEMPTS(state, attempts: number) {
            state.reconnectAttempts = attempts;
        },
        SET_MODULE_DATA(state, { module, data }: { module: string; data: any }) {
            state.realtimeData[module] = data;
        },
        UPDATE_MODULE_DATA(state, { module, updates }: { module: string; updates: any }) {
            if (!state.realtimeData[module]) {
                state.realtimeData[module] = {};
            }
            Object.assign(state.realtimeData[module], updates);
        },
    },
    actions: {
        setConnected({ commit }, connected: boolean) {
            commit('SET_CONNECTED', connected);
        },
        setReconnectAttempts({ commit }, attempts: number) {
            commit('SET_RECONNECT_ATTEMPTS', attempts);
        },
        setModuleData({ commit }, payload: { module: string; data: any }) {
            commit('SET_MODULE_DATA', payload);
        },
        updateModuleData({ commit }, payload: { module: string; updates: any }) {
            commit('UPDATE_MODULE_DATA', payload);
        },
    },
};
