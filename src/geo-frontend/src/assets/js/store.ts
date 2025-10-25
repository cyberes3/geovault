import { createStore, Commit } from 'vuex'
import { UserInfo } from './types/store-types'

// Define import queue item interface
interface ImportQueueItem {
    id: string
    filename: string
    status: string
    [key: string]: any
}

// Define the state interface
interface State {
    userInfo: typeof UserInfo
    importQueue: ImportQueueItem[]
    importQueueRefreshTrigger: boolean
    websocketConnected: boolean
    websocketReconnectAttempts: number
    realtimeData: {
        importQueue: ImportQueueItem[]
        [key: string]: any
    }
}

// Store type is inferred from createStore<State>

export default createStore<State>({
    state: {
        userInfo: UserInfo,
        importQueue: [],
        importQueueRefreshTrigger: false,
        websocketConnected: false,
        websocketReconnectAttempts: 0,
        realtimeData: {
            importQueue: []
        },
    }, 
    mutations: {
        userInfo(state: State, payload: typeof UserInfo) {
            state.userInfo = payload
        },
        importQueue(state: State, payload: ImportQueueItem[]) {
            state.importQueue = payload
        },
        setImportQueue(state: State, importQueue: ImportQueueItem[]) {
            state.importQueue = importQueue;
        },
        triggerImportQueueRefresh(state: State) {
            state.importQueueRefreshTrigger = !state.importQueueRefreshTrigger;
        },
        addImportQueueItem(state: State, item: ImportQueueItem) {
            // Check if item already exists to avoid duplicates
            const existingIndex = state.importQueue.findIndex(existing => existing.id === item.id);
            if (existingIndex === -1) {
                state.importQueue.unshift(item); // Add to beginning
            }
        },
        removeImportQueueItem(state: State, itemId: string) {
            const index = state.importQueue.findIndex(item => item.id === itemId);
            if (index > -1) {
                state.importQueue.splice(index, 1);
            }
        },
        removeImportQueueItems(state: State, itemIds: string[]) {
            state.importQueue = state.importQueue.filter(item => !itemIds.includes(item.id));
        },
        updateImportQueueItem(state: State, { id, updates }: { id: string, updates: Partial<ImportQueueItem> }) {
            const index = state.importQueue.findIndex(item => item.id === id);
            if (index > -1) {
                state.importQueue[index] = { ...state.importQueue[index], ...updates };
            }
        },
        setWebSocketConnected(state: State, connected: boolean) {
            state.websocketConnected = connected;
        },
        setWebSocketReconnectAttempts(state: State, attempts: number) {
            state.websocketReconnectAttempts = attempts;
        },
        setRealtimeModuleData(state: State, { module, data }: { module: string, data: any }) {
            state.realtimeData[module] = data;
        },
        updateRealtimeModuleData(state: State, { module, updates }: { module: string, updates: any }) {
            if (!state.realtimeData[module]) {
                state.realtimeData[module] = {};
            }
            Object.assign(state.realtimeData[module], updates);
        },
        // WebSocket delete job mutations
        'websocket/delete_job_started'(state: State, payload: any) {
            // This mutation is handled by the component subscription
        },
        'websocket/delete_job_status_updated'(state: State, payload: any) {
            // This mutation is handled by the component subscription
        },
        'websocket/delete_job_completed'(state: State, payload: any) {
            // This mutation is handled by the component subscription
        },
        'websocket/delete_job_failed'(state: State, payload: any) {
            // This mutation is handled by the component subscription
        },
    }, 
    getters: {
        // alertExists: (state) => (message) => {
        //     return state.siteAlerts.includes(message);
        // },
    },
    actions: {
        refreshImportQueue({ commit }: { commit: Commit }) {
            commit('triggerImportQueueRefresh');
        },
        addImportQueueItem({ commit }: { commit: Commit }, item: ImportQueueItem) {
            commit('addImportQueueItem', item);
        },
        removeImportQueueItem({ commit }: { commit: Commit }, itemId: string) {
            commit('removeImportQueueItem', itemId);
        },
        removeImportQueueItems({ commit }: { commit: Commit }, itemIds: string[]) {
            commit('removeImportQueueItems', itemIds);
        },
        updateImportQueueItem({ commit }: { commit: Commit }, payload: { id: string, updates: Partial<ImportQueueItem> }) {
            commit('updateImportQueueItem', payload);
        },
        setWebSocketConnected({ commit }: { commit: Commit }, connected: boolean) {
            commit('setWebSocketConnected', connected);
        },
        setWebSocketReconnectAttempts({ commit }: { commit: Commit }, attempts: number) {
            commit('setWebSocketReconnectAttempts', attempts);
        },
        setRealtimeModuleData({ commit }: { commit: Commit }, payload: { module: string, data: any }) {
            commit('setRealtimeModuleData', payload);
        },
        updateRealtimeModuleData({ commit }: { commit: Commit }, payload: { module: string, updates: any }) {
            commit('updateRealtimeModuleData', payload);
        },
        // WebSocket delete job actions
        'websocket/delete_job_started'({ commit }: { commit: Commit }, payload: any) {
            commit('websocket/delete_job_started', payload);
        },
        'websocket/delete_job_status_updated'({ commit }: { commit: Commit }, payload: any) {
            commit('websocket/delete_job_status_updated', payload);
        },
        'websocket/delete_job_completed'({ commit }: { commit: Commit }, payload: any) {
            commit('websocket/delete_job_completed', payload);
        },
        'websocket/delete_job_failed'({ commit }: { commit: Commit }, payload: any) {
            commit('websocket/delete_job_failed', payload);
        },
    },
})
