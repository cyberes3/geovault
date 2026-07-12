/**
 * Base module class for WebSocket realtime functionality.
 * All WebSocket modules should extend this class.
 */

import type { Store } from 'vuex';
import type { RootState } from '../../store';

/** The minimal surface a module needs from `RealtimeSocket`; kept as an interface (rather than
 * importing the concrete class) to avoid a circular import between the socket and its modules. */
export interface RealtimeSocketLike {
    subscribe(module: string, event: string, handler: (data: any) => void): void;
    unsubscribe(module: string, event: string, handler: (data: any) => void): void;
    send(module: string, type: string, data?: Record<string, any>): void;
    requestRefresh(module: string): void;
}

export abstract class BaseModule {
    readonly store: Store<RootState>;
    abstract readonly moduleName: string;
    socket: RealtimeSocketLike | null = null;

    constructor(store: Store<RootState>) {
        this.store = store;
    }

    /**
     * Initialize module (called when socket connects).
     * Override this method in subclasses to set up event handlers.
     */
    initialize(): void {
        // Override in subclasses
    }

    /**
     * Cleanup module (called when socket disconnects).
     * Override this method in subclasses for custom cleanup.
     */
    cleanup(): void {
        // Override in subclasses if needed
    }

    /** Subscribe to a WebSocket event scoped to this module. */
    protected subscribe(event: string, handler: (data: any) => void): void {
        if (!this.socket) {
            throw new Error('Socket not available - module not properly initialized');
        }
        this.socket.subscribe(this.moduleName, event, handler);
    }

    /** Send a message to the server, scoped to this module. */
    protected send(type: string, data: Record<string, any> = {}): void {
        if (!this.socket) {
            throw new Error('Socket not available - module not properly initialized');
        }
        this.socket.send(this.moduleName, type, data);
    }

    /** Request a refresh of this module's data. */
    protected requestRefresh(): void {
        if (!this.socket) {
            throw new Error('Socket not available - module not properly initialized');
        }
        this.socket.requestRefresh(this.moduleName);
    }
}
