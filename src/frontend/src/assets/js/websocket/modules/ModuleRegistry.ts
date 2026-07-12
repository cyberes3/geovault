/**
 * Module Registry for WebSocket modules.
 * All modules should be registered here for automatic loading.
 */

import type { Store } from 'vuex';
import type { RootState } from '../../store';
import { BaseModule } from './BaseModule';
import { ImportQueueModule } from './ImportQueueModule';
import { ImportHistoryModule } from './ImportHistoryModule';
import { ProcessJobModule } from './ProcessJobModule';
import { DeleteJobModule } from './DeleteJobModule';
import { BulkImportJobModule } from './BulkImportJobModule';
import { BulkDeleteJobModule } from './BulkDeleteJobModule';

type ModuleConstructor = new (store: Store<RootState>) => BaseModule;

/** Registry of all available WebSocket modules. Add new modules here to have them auto-loaded. */
export const MODULE_REGISTRY: ModuleConstructor[] = [
    ImportQueueModule,
    ImportHistoryModule,
    ProcessJobModule,
    DeleteJobModule,
    BulkImportJobModule,
    BulkDeleteJobModule,
];

/** Instantiate every registered module against the given store. */
export function loadAllModules(store: Store<RootState>): BaseModule[] {
    const modules: BaseModule[] = [];

    for (const ModuleClass of MODULE_REGISTRY) {
        try {
            modules.push(new ModuleClass(store));
        } catch (error) {
            console.error(`Failed to load module ${ModuleClass.name}:`, error);
        }
    }

    return modules;
}
