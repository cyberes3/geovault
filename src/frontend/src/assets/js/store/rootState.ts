import type { AuthState } from './modules/auth';
import type { UserSettingsState } from './modules/userSettings';
import type { ImportQueueState } from './modules/importQueue';
import type { WebSocketState } from './modules/websocket';
import type { ExtensionsRuntimeState } from './modules/extensionsRuntime';

/**
 * Split out of `index.ts` so modules can import `RootState` (for their `Module<State, RootState>`
 * typing) without a runtime circular import: this file only ever imports `type`s from the module
 * files, never their `Module` instances, so there's nothing to actually cycle at runtime.
 */
export interface RootState {
    auth: AuthState;
    userSettings: UserSettingsState;
    importQueue: ImportQueueState;
    websocket: WebSocketState;
    extensionsRuntime: ExtensionsRuntimeState;
}
