import {createStore} from 'vuex'
import {UserInfo} from './types/store-types'
import {ImportQueueItem} from "@/assets/js/types/import-types";


export default createStore({
    state: {
        userInfo: UserInfo,
        importQueue: [],
        importQueueRefreshTrigger: false,

    }, mutations: {
        userInfo(state, payload) {
            state.userInfo = payload
        },
        importQueue(state, payload) {
            state.importQueue = payload
        },
        setImportQueue(state, importQueue) {
            state.importQueue = importQueue;
        },
        triggerImportQueueRefresh(state) {
            state.importQueueRefreshTrigger = !state.importQueueRefreshTrigger;
        },
    }, getters: {
        // alertExists: (state) => (message) => {
        //     return state.siteAlerts.includes(message);
        // },
    },
    actions: {
        refreshImportQueue({commit}) {
            commit('triggerImportQueueRefresh');
        },
    },
})
