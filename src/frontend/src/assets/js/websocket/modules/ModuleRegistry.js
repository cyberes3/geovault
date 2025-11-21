/**
 * Module Registry for WebSocket modules.
 * All modules should be registered here for automatic loading.
 */

import { ImportQueueModule } from './ImportQueueModule.js';
import { ImportHistoryModule } from './ImportHistoryModule.js';
import { UploadJobModule } from './UploadJobModule.js';
import { DeleteJobModule } from './DeleteJobModule.js';
// Import other modules here as they are created
// import { NotificationsModule } from './NotificationsModule.js';
// import { ChatModule } from './ChatModule.js';

/**
 * Registry of all available WebSocket modules
 * Add new modules to this array to have them automatically loaded
 */
export const MODULE_REGISTRY = [
    ImportQueueModule,
    ImportHistoryModule,
    UploadJobModule,
    DeleteJobModule,
    // Add other modules here:
    // NotificationsModule,
    // ChatModule,
];

/**
 * Load all registered modules
 * @param {Object} store - Vuex store instance
 * @returns {Array} Array of instantiated modules
 */
export function loadAllModules(store) {
    const modules = [];

    for (const ModuleClass of MODULE_REGISTRY) {
        try {
            const module = new ModuleClass(store);
            modules.push(module);
            console.log(`Loaded module: ${module.moduleName}`);
        } catch (error) {
            console.error(`Failed to load module ${ModuleClass.name}:`, error);
        }
    }

    return modules;
}
