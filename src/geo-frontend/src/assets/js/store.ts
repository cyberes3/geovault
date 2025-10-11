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
}

// Store type is inferred from createStore<State>

export default createStore<State>({
    state: {
        userInfo: UserInfo,
        importQueue: [],
        importQueueRefreshTrigger: false,
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
    },
})
