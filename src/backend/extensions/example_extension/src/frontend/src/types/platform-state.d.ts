/**
 * Local shape of the `platformState` bridge the core app injects into extension route/settings
 * components (see the core frontend's `src/extensions/platformState.ts` for the real
 * implementation). Declared locally rather than imported, since this extension is a separate
 * TypeScript project.
 */
import type { ComputedRef } from 'vue';

export interface PlatformStateBridge {
    readonly userSettings: ComputedRef<Record<string, unknown> | null>;
    readonly currentUser: ComputedRef<Record<string, unknown> | null>;
    fetchUserSettings(): Promise<void>;
    saveUserSetting(update: Record<string, unknown>): Promise<Record<string, unknown>>;
}
