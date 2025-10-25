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

        // Handle delete job started
        this.subscribe('started', (data) => {
            console.log('Delete job started:', data);
            this.store.dispatch('websocket/delete_job_started', { data });
        });

        // Handle delete job status updated
        this.subscribe('status_updated', (data) => {
            console.log('Delete job status updated:', data);
            this.store.dispatch('websocket/delete_job_status_updated', { data });
        });

        // Handle delete job completed
        this.subscribe('completed', (data) => {
            console.log('Delete job completed:', data);
            this.store.dispatch('websocket/delete_job_completed', { data });
        });

        // Handle delete job failed
        this.subscribe('failed', (data) => {
            console.log('Delete job failed:', data);
            this.store.dispatch('websocket/delete_job_failed', { data });
        });
    }
}
