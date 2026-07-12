import type { Module } from 'vuex';

export interface WebSocketState {
    connected: boolean;
    reconnectAttempts: number;
}

/** Connection status for the app-lifetime realtime socket (see `assets/js/websocket`). */
export const websocketModule: Module<WebSocketState, any> = {
    namespaced: true,
    state: (): WebSocketState => ({
        connected: false,
        reconnectAttempts: 0,
    }),
    getters: {
        connected: (state) => state.connected,
        reconnectAttempts: (state) => state.reconnectAttempts,
    },
    mutations: {
        SET_CONNECTED(state, connected: boolean) {
            state.connected = connected;
        },
        SET_RECONNECT_ATTEMPTS(state, attempts: number) {
            state.reconnectAttempts = attempts;
        },
    },
    actions: {
        setConnected({ commit }, connected: boolean) {
            commit('SET_CONNECTED', connected);
        },
        setReconnectAttempts({ commit }, attempts: number) {
            commit('SET_RECONNECT_ATTEMPTS', attempts);
        },
    },
};
