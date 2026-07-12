import { createStore } from 'vuex';
import { authModule, type AuthState } from './modules/auth';
import { userSettingsModule, type UserSettingsState } from './modules/userSettings';
import { importQueueModule, type ImportQueueState } from './modules/importQueue';
import { websocketModule, type WebSocketState } from './modules/websocket';
import { extensionsRuntimeModule, type ExtensionsRuntimeState } from './modules/extensionsRuntime';

export interface RootState {
    auth: AuthState;
    userSettings: UserSettingsState;
    importQueue: ImportQueueState;
    websocket: WebSocketState;
    extensionsRuntime: ExtensionsRuntimeState;
}

/**
 * Root store: a thin composition of domain modules. Components must only interact with
 * modules through their namespaced getters/actions (e.g. `auth/userInfo`,
 * `userSettings/fetchUserSettings`) -- never `state.<module>.<field>` or bare `commit()`.
 */
export default createStore<RootState>({
    modules: {
        auth: authModule,
        userSettings: userSettingsModule,
        importQueue: importQueueModule,
        websocket: websocketModule,
        extensionsRuntime: extensionsRuntimeModule,
    },
});
