/**
 * Import History WebSocket module.
 * Handles all import history table related realtime events.
 */

import {BaseModule} from './BaseModule.js';

export class ImportHistoryModule extends BaseModule {
    constructor(store) {
        super(store);
        this.moduleName = 'import_history';
    }

    /**
     * Initialize the import history module
     */
    initialize() {
        super.initialize();

        // Handle initial state
        this.subscribe('initial_state', (data) => {
            console.log('Received import history initial state:', data);
            this.store.dispatch('setRealtimeModuleData', {module: 'importHistory', data});
            // Also update the legacy importHistory state for backward compatibility
            this.store.commit('setImportHistory', data);
            // Mark as initially loaded
            this.store.commit('setImportHistoryLoaded', true);
        });

        // Handle new item added to history
        this.subscribe('item_added', (data) => {
            console.log('Import history item added:', data);
            this.store.dispatch('addImportHistoryItem', data);
        });
    }

    /**
     * Cleanup the import history module
     */
    cleanup() {
        super.cleanup();
        console.log('Import history module cleaned up');
    }
}
