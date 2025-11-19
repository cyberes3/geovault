import { createStore, Commit } from 'vuex'
import { UserInfo } from './types/store-types'

// Define import queue item interface
interface ImportQueueItem {
    id: number
    filename: string
    status: string
    [key: string]: any
}

// Define import history item interface
interface ImportHistoryItem {
    id: number
    original_filename: string
    timestamp: string
}

// Define the state interface
interface State {
    userInfo: UserInfo | null
    importQueue: ImportQueueItem[]
    importHistory: ImportHistoryItem[]
    importHistoryLoaded: boolean
    importQueueRefreshTrigger: boolean
    websocketConnected: boolean
    websocketReconnectAttempts: number
    realtimeData: {
        importQueue: ImportQueueItem[]
        importHistory: ImportHistoryItem[]
        [key: string]: any
    }
}

// Store type is inferred from createStore<State>

export default createStore<State>({
    state: {
        userInfo: null,
        importQueue: [],
        importHistory: [],
        importHistoryLoaded: false,
        importQueueRefreshTrigger: false,
        websocketConnected: false,
        websocketReconnectAttempts: 0,
        realtimeData: {
            importQueue: [],
            importHistory: []
        },
    }, 
    mutations: {
        userInfo(state: State, payload: UserInfo | null) {
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
        removeImportQueueItem(state: State, itemId: number) {
            const index = state.importQueue.findIndex(item => item.id === itemId);
            if (index > -1) {
                state.importQueue.splice(index, 1);
            }
        },
        removeImportQueueItems(state: State, itemIds: number[]) {
            state.importQueue = state.importQueue.filter(item => !itemIds.includes(item.id));
        },
        updateImportQueueItem(state: State, { id, updates }: { id: number, updates: Partial<ImportQueueItem> }) {
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
        setImportHistory(state: State, importHistory: ImportHistoryItem[]) {
            state.importHistory = importHistory;
        },
        addImportHistoryItem(state: State, item: ImportHistoryItem) {
            // Check if item already exists to avoid duplicates
            const existingIndex = state.importHistory.findIndex(existing => existing.id === item.id);
            if (existingIndex === -1) {
                state.importHistory.unshift(item); // Add to beginning
            }
        },
        setImportHistoryLoaded(state: State, loaded: boolean) {
            state.importHistoryLoaded = loaded;
        },
        updateRealtimeModuleData(state: State, { module, updates }: { module: string, updates: any }) {
            if (!state.realtimeData[module]) {
                state.realtimeData[module] = {};
            }
            Object.assign(state.realtimeData[module], updates);
        },
    }, 
    getters: {
    },
    actions: {
        refreshImportQueue({ commit }: { commit: Commit }) {
            commit('triggerImportQueueRefresh');
        },
        addImportQueueItem({ commit }: { commit: Commit }, item: ImportQueueItem) {
            commit('addImportQueueItem', item);
        },
        removeImportQueueItem({ commit }: { commit: Commit }, itemId: number) {
            commit('removeImportQueueItem', itemId);
        },
        removeImportQueueItems({ commit }: { commit: Commit }, itemIds: number[]) {
            commit('removeImportQueueItems', itemIds);
        },
        updateImportQueueItem({ commit }: { commit: Commit }, payload: { id: number, updates: Partial<ImportQueueItem> }) {
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
        setImportHistory({ commit }: { commit: Commit }, importHistory: ImportHistoryItem[]) {
            commit('setImportHistory', importHistory);
        },
        addImportHistoryItem({ commit }: { commit: Commit }, item: ImportHistoryItem) {
            commit('addImportHistoryItem', item);
        },
        setImportHistoryLoaded({ commit }: { commit: Commit }, loaded: boolean) {
            commit('setImportHistoryLoaded', loaded);
        },
        updateRealtimeModuleData({ commit }: { commit: Commit }, payload: { module: string, updates: any }) {
            commit('updateRealtimeModuleData', payload);
        },
    },
})
