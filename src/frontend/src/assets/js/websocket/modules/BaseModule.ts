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
    private readonly boundSubscriptions: Array<{ event: string; handler: (data: any) => void }> = [];
    private initialized = false;

    constructor(store: Store<RootState>) {
        this.store = store;
    }

    /**
     * Subscribe to events. Safe to call more than once: the first call runs `onInitialize()`,
     * later calls are ignored. Handlers stay on the socket `Map` across reconnects.
     */
    initialize(): void {
        if (this.initialized) {
            return;
        }
        this.initialized = true;
        this.onInitialize();
    }

    /** Override to `subscribe()` to this module's events. Invoked once by `initialize()`. */
    protected onInitialize(): void {
    }

    /** Unsubscribe this module's handlers. Called on logout / unregister, not on transient disconnect. */
    cleanup(): void {
        if (this.socket) {
            for (const { event, handler } of this.boundSubscriptions) {
                this.socket.unsubscribe(this.moduleName, event, handler);
            }
        }
        this.boundSubscriptions.length = 0;
        this.initialized = false;
    }

    /** Subscribe to a WebSocket event scoped to this module. */
    protected subscribe(event: string, handler: (data: any) => void): void {
        if (!this.socket) {
            throw new Error('Socket not available - module not properly initialized');
        }
        this.socket.subscribe(this.moduleName, event, handler);
        this.boundSubscriptions.push({ event, handler });
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
