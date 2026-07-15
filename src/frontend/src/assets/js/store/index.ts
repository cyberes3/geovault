import { createStore } from 'vuex';
import { authModule } from './modules/auth';
import { userSettingsModule } from './modules/userSettings';
import { importQueueModule } from './modules/importQueue';
import { websocketModule } from './modules/websocket';
import { extensionsRuntimeModule } from './modules/extensionsRuntime';
import type { RootState } from './rootState';

export type { RootState };

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
