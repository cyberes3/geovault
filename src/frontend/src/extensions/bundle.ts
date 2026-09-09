import type { ExtensionSetup } from './extensionContractTypes';
import type { MapEngine } from '../../../packages/extension-sdk/src/manifest';

export function resolveSetupFunction(
    module: unknown,
    umdGlobal: string | null | undefined
): ExtensionSetup | null {
    if (module && typeof module === 'object') {
        const defaultExport = (module as { default?: unknown }).default;
        if (typeof defaultExport === 'function') {
            return defaultExport as ExtensionSetup;
        }
    }
    if (umdGlobal) {
        const setup = (window as unknown as Record<string, unknown>)[umdGlobal];
        if (typeof setup === 'function') {
            return setup as ExtensionSetup;
        }
    }
    return null;
}

export async function loadMapEngine(engine: MapEngine | undefined): Promise<void> {
    if (!engine || engine === 'none') {
        return;
    }
    await window.gv_core.map.loadEngine(engine);
}
