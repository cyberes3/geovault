import type { Module } from 'vuex';
import { getUserInfo } from '../../auth';
import { UserInfo } from '../../types/store-types';
import type { RootState } from '../rootState';

export interface AuthState {
    userInfo: UserInfo | null;
}

/**
 * Session/identity state. The only module allowed to know about `getUserInfo()`;
 * everything else reads `auth/userInfo` via a getter.
 */
export const authModule: Module<AuthState, RootState> = {
    namespaced: true,
    state: (): AuthState => ({
        userInfo: null,
    }),
    getters: {
        userInfo: (state) => state.userInfo,
    },
    mutations: {
        SET_USER_INFO(state, payload: UserInfo | null) {
            state.userInfo = payload;
        },
    },
    actions: {
        async fetchUserInfo({ commit, dispatch }) {
            try {
                const userStatus = await getUserInfo();
                if (userStatus?.authorized) {
                    const userInfo = new UserInfo(
                        userStatus.email,
                        userStatus.id,
                        userStatus.featureCount,
                        userStatus.tags.map((t) => t.tag),
                        userStatus.isSuperuser,
                    );
                    commit('SET_USER_INFO', userInfo);
                } else {
                    // Not authorized: clear identity and any settings that depend on it.
                    commit('SET_USER_INFO', null);
                    await dispatch('userSettings/clearUserSettings', null, { root: true });
                }
                return userStatus;
            } catch (error) {
                console.error('Error fetching user info:', error);
                commit('SET_USER_INFO', null);
                await dispatch('userSettings/clearUserSettings', null, { root: true });
                throw error;
            }
        },
        clearUserInfo({ commit }) {
            commit('SET_USER_INFO', null);
        },
    },
};
