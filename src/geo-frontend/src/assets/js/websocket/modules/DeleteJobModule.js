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
            console.log('Received delete job initial state:', data);
            // Delete jobs don't have persistent state, so no action needed
        });

        // Handle delete job started - update item state
        this.subscribe('started', (data) => {
            console.log('Delete job started:', data);
            // Mark item as deleting in the queue
            this.store.dispatch('updateImportQueueItem', {
                id: data.import_queue_id.toString(),
                updates: { deleting: true }
            });
        });

        // Handle delete job status updated
        this.subscribe('status_updated', (data) => {
            console.log('Delete job status updated:', data);
            // Could update progress here if needed
            this.store.dispatch('updateImportQueueItem', {
                id: data.import_queue_id.toString(),
                updates: { 
                    deleting: true,
                    deleteProgress: data.progress 
                }
            });
        });

        // Handle delete job completed - remove item
        this.subscribe('completed', (data) => {
            console.log('Delete job completed:', data);
            // Remove the deleted item(s) from the queue
            if (data.import_queue_ids && data.import_queue_ids.length > 0) {
                this.store.dispatch('removeImportQueueItems', 
                    data.import_queue_ids.map(id => id.toString())
                );
            } else if (data.import_queue_id) {
                this.store.dispatch('removeImportQueueItem', 
                    data.import_queue_id.toString()
                );
            }
        });

        // Handle delete job failed - clear deleting state
        this.subscribe('failed', (data) => {
            console.log('Delete job failed:', data);
            // Clear deleting state and optionally set error
            this.store.dispatch('updateImportQueueItem', {
                id: data.import_queue_id.toString(),
                updates: { 
                    deleting: false,
                    deleteError: data.error 
                }
            });
        });
    }
}
