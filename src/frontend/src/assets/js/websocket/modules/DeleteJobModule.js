/**
 * Delete job WebSocket module for handling delete job events.
 */

import { BaseModule } from './BaseModule.js';

export class DeleteJobModule extends BaseModule {
    constructor(store) {
        super(store);
        this.moduleName = 'delete_job';
    }

    /**
     * Initialize the delete job module
     */
    initialize() {
        super.initialize();

        // Handle initial state
        this.subscribe('initial_state', (data) => {
            // Delete jobs don't have persistent state, so no action needed
        });

        // Handle delete job started - update item state
        this.subscribe('started', (data) => {
            // Mark item as deleting in the table
            this.store.dispatch('updateImportTableItem', {
                id: data.item_id,
                updates: { deleting: true }
            });
        });

        // Handle delete job status updated
        this.subscribe('status_updated', (data) => {
            // Could update progress here if needed
            this.store.dispatch('updateImportTableItem', {
                id: data.item_id,
                updates: { 
                    deleting: true,
                    deleteProgress: data.progress 
                }
            });
        });

        // Handle delete job completed - remove item
        this.subscribe('completed', (data) => {
            // Remove the deleted item from the table
            this.store.dispatch('removeImportTableItem', data.item_id);
        });

        // Handle delete job failed - clear deleting state
        this.subscribe('failed', (data) => {
            // Clear deleting state and optionally set error
            this.store.dispatch('updateImportTableItem', {
                id: data.item_id,
                updates: { 
                    deleting: false,
                    deleteError: data.error 
                }
            });
        });
    }
}
