import { createStore, Commit } from 'vuex'
import { UserInfo } from './types/store-types'
import { getUserInfo, getCookie } from './auth'

// Define import table item interface
interface ImportTableItem {
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

// Define hidden feature interface
interface HiddenFeature {
    id: string
    name: string | null
    geometry_type: string | null
}

// Define user settings interface
interface UserSettings {
    [key: string]: any
}

// Define the state interface
interface State {
    userInfo: UserInfo | null
    userSettings: UserSettings | null
    hiddenFeatures: HiddenFeature[]
    importTable: ImportTableItem[]
    importHistory: ImportHistoryItem[]
    importHistoryLoaded: boolean
    importTableRefreshTrigger: boolean
    websocketConnected: boolean
    websocketReconnectAttempts: number
    realtimeData: {
        importTable: ImportTableItem[]
        importHistory: ImportHistoryItem[]
        [key: string]: any
    }
}

// Store type is inferred from createStore<State>

export default createStore<State>({
    state: {
        userInfo: null,
        userSettings: null,
        hiddenFeatures: [],
        importTable: [],
        importHistory: [],
        importHistoryLoaded: false,
        importTableRefreshTrigger: false,
        websocketConnected: false,
        websocketReconnectAttempts: 0,
        realtimeData: {
            importTable: [],
            importHistory: []
        },
    }, 
    mutations: {
        userInfo(state: State, payload: UserInfo | null) {
            state.userInfo = payload
        },
        userSettings(state: State, payload: UserSettings | null) {
            state.userSettings = payload
        },
        setHiddenFeatures(state: State, payload: HiddenFeature[] | any[]) {
            // Normalize payload to HiddenFeature[] format
            if (!Array.isArray(payload)) {
                state.hiddenFeatures = []
                return
            }
            
            // Handle both old format (string[]) and new format ({id, name, geometry_type}[])
            state.hiddenFeatures = payload.map(item => {
                if (typeof item === 'string') {
                    return { id: item, name: null, geometry_type: null }
                } else if (item && typeof item === 'object' && 'id' in item) {
                    return {
                        id: String(item.id),
                        name: item.name || null,
                        geometry_type: item.geometry_type || null
                    }
                }
                return { id: String(item), name: null, geometry_type: null }
            })
        },
        addHiddenFeature(state: State, payload: { featureId: string; featureName?: string | null; geometryType?: string | null }) {
            const id = String(payload.featureId)
            const existing = state.hiddenFeatures.find(f => f.id === id)
            if (!existing) {
                state.hiddenFeatures.push({
                    id,
                    name: payload.featureName || null,
                    geometry_type: payload.geometryType || null
                })
            }
        },
        removeHiddenFeature(state: State, featureId: string) {
            const id = String(featureId)
            state.hiddenFeatures = state.hiddenFeatures.filter(f => f.id !== id)
        },
        importTable(state: State, payload: ImportTableItem[]) {
            state.importTable = payload
        },
        setImportTable(state: State, importTable: ImportTableItem[]) {
            state.importTable = importTable;
        },
        triggerImportTableRefresh(state: State) {
            state.importTableRefreshTrigger = !state.importTableRefreshTrigger;
        },
        addImportTableItem(state: State, item: ImportTableItem) {
            // Check if item already exists to avoid duplicates
            const existingIndex = state.importTable.findIndex(existing => existing.id === item.id);
            if (existingIndex === -1) {
                state.importTable.unshift(item); // Add to beginning
            }
        },
        removeImportTableItem(state: State, itemId: number) {
            const index = state.importTable.findIndex(item => item.id === itemId);
            if (index > -1) {
                state.importTable.splice(index, 1);
            }
        },
        removeImportTableItems(state: State, itemIds: number[]) {
            state.importTable = state.importTable.filter(item => !itemIds.includes(item.id));
        },
        updateImportTableItem(state: State, { id, updates }: { id: number, updates: Partial<ImportTableItem> }) {
            const index = state.importTable.findIndex(item => item.id === id);
            if (index > -1) {
                state.importTable[index] = { ...state.importTable[index], ...updates };
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
        refreshImportTable({ commit }: { commit: Commit }) {
            commit('triggerImportTableRefresh');
        },
        addImportTableItem({ commit }: { commit: Commit }, item: ImportTableItem) {
            commit('addImportTableItem', item);
        },
        removeImportTableItem({ commit }: { commit: Commit }, itemId: number) {
            commit('removeImportTableItem', itemId);
        },
        removeImportTableItems({ commit }: { commit: Commit }, itemIds: number[]) {
            commit('removeImportTableItems', itemIds);
        },
        updateImportTableItem({ commit }: { commit: Commit }, payload: { id: number, updates: Partial<ImportTableItem> }) {
            commit('updateImportTableItem', payload);
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
        async fetchUserInfo({ commit }: { commit: Commit }) {
            try {
                const userStatus = await getUserInfo();
                if (userStatus && userStatus.authorized) {
                    const userInfo = new UserInfo(
                        userStatus.email, 
                        userStatus.id, 
                        userStatus.featureCount, 
                        userStatus.tags || [], 
                        userStatus.isSuperuser
                    );
                    commit('userInfo', userInfo);
                    return userStatus;
                } else {
                    // If not authorized, clear user info and settings
                    commit('userInfo', null);
                    commit('userSettings', null);
                    return userStatus;
                }
            } catch (error) {
                console.error('Error fetching user info:', error);
                commit('userInfo', null);
                commit('userSettings', null);
                throw error;
            }
        },
        async fetchUserSettings({ commit, state }: { commit: Commit, state: State }) {
            // Only fetch settings if user is authenticated
            if (!state.userInfo) {
                commit('userSettings', null);
                return null;
            }

            try {
                const csrfToken = getCookie('csrftoken');
                
                const response = await fetch('/api/user/settings/', {
                    method: 'GET',
                    headers: {
                        'X-CSRFToken': csrfToken || '',
                    },
                    credentials: 'include'
                });

                if (!response.ok) {
                    if (response.status === 401) {
                        // User not authenticated, clear settings
                        commit('userSettings', null);
                        commit('setHiddenFeatures', []);
                        return null;
                    }
                    throw new Error(`HTTP error! status: ${response.status}`);
                }

                const data = await response.json();
                
                if (response.ok && data.settings) {
                    commit('userSettings', data.settings);
                    commit('setHiddenFeatures', data.hidden_features || []);
                    return data.settings;
                } else {
                    // No settings found, initialize with empty object
                    commit('userSettings', {});
                    commit('setHiddenFeatures', data?.hidden_features || []);
                    return {};
                }
            } catch (error) {
                console.error('Error fetching user settings:', error);
                // On error, initialize with empty object rather than null
                commit('userSettings', {});
                commit('setHiddenFeatures', []);
                return {};
            }
        },
    },
})
