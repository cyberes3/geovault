/**
 * Local shape of the read-mostly bridge onto core app state the core app injects as
 * `platformState` (see the core frontend's `src/extensions/platformState.ts` for the real
 * implementation). Declared locally rather than imported, since this extension is a separate
 * TypeScript project.
 */
import type { ComputedRef } from 'vue';

export interface PlatformUserInfo {
    id: number;
    email: string;
    [key: string]: unknown;
}

export interface PlatformStateBridge {
    readonly userSettings: ComputedRef<Record<string, unknown> | null>;
    readonly currentUser: ComputedRef<PlatformUserInfo | null>;
    fetchUserSettings(): Promise<void>;
    saveUserSetting(update: Record<string, unknown>): Promise<Record<string, unknown>>;
}
