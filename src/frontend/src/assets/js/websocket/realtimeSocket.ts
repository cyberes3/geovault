/**
 * Global WebSocket service for real-time updates.
 */

import type { Store } from 'vuex';
import { WebSocketHeartbeat } from './WebSocketHeartbeat.js';
import type { BaseModule } from './modules/BaseModule';
import type { RootState } from '../store';

type MessageHandler = (data: any) => void;
type GlobalHandler = (data?: any) => void;

interface DurableSubscription {
    module: string;
    event: string;
    handler: MessageHandler;
}

interface RealtimeMessage {
    module?: string;
    type?: string;
    data?: Record<string, unknown>;
}

class RealtimeSocket {
    private socket: WebSocket | null = null;
    isConnected = false;
    private reconnectAttempts = 0;
    private readonly reconnectInterval = 1000; // Start with 1 second
    private readonly maxReconnectInterval = 30000; // Max 30 seconds
    private readonly moduleHandlers = new Map<string, Map<string, MessageHandler[]>>();
    private readonly durableSubscriptions: DurableSubscription[] = [];
    private readonly globalHandlers = new Map<string, GlobalHandler[]>();
    private readonly modules = new Map<string, BaseModule>();
    private readonly heartbeat: WebSocketHeartbeat;
    private shouldStayConnected = false;
    private visibilityListenerBound = false;

    constructor() {
        this.heartbeat = new WebSocketHeartbeat({
            sendPing: () => {
                this.send('ping', 'ping');
            },
            onTimeout: () => {
                console.warn('Ping timeout, triggering reconnection');
                if (this.socket) {
                    this.socket.close(1006, 'Ping timeout'); // Triggers onclose and reconnection
                }
            },
        });
    }

    /** Connect to the WebSocket server. */
    connect(): void {
        this.shouldStayConnected = true;

        // If already connected or connecting, don't create another connection.
        if (this.socket && (this.socket.readyState === WebSocket.OPEN || this.socket.readyState === WebSocket.CONNECTING)) {
            return;
        }

        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const host = window.location.host;
        const wsUrl = `${protocol}//${host}/ws/realtime/`;

        try {
            this.socket = new WebSocket(wsUrl);
            this.setupEventHandlers();
            this._bindVisibilityListener();
        } catch (error) {
            console.error('Failed to create Realtime WebSocket connection:', error);
            this.scheduleReconnect();
        }
    }

    /**
     * Bind Page Visibility listener once so we reconnect when user returns to the tab
     * (e.g. after putting the browser in background on mobile).
     */
    private _bindVisibilityListener(): void {
        if (this.visibilityListenerBound || typeof document === 'undefined') return;
        document.addEventListener('visibilitychange', this._onVisibilityChange);
        this.visibilityListenerBound = true;
    }

    // Arrow function class field (not a prototype method) so it can be passed directly to
    // addEventListener/removeEventListener without needing a separate `.bind(this)`.
    private _onVisibilityChange = (): void => {
        if (document.visibilityState !== 'visible' || !this.shouldStayConnected) return;
        // User returned to the tab; ensure we have a live connection (mobile often drops WS when backgrounded)
        this.reconnectWhenVisible();
    };

    /**
     * Reconnect when the page becomes visible again (e.g. after background).
     * Closes any existing socket and opens a fresh one, resetting reconnect count.
     */
    reconnectWhenVisible(): void {
        if (!this.shouldStayConnected) return;
        this.heartbeat.stop();
        if (this.socket) {
            this.socket.close(1000, 'Reconnecting after visibility');
            this.socket = null;
        }
        this.isConnected = false;
        this.reconnectAttempts = 0;
        this.connect();
    }

    private setupEventHandlers(): void {
        if (!this.socket) return;

        this.socket.onopen = (event) => {
            this.isConnected = true;
            this.reconnectAttempts = 0;
            this.heartbeat.start();
            this.initializeModules();
            this._restoreSubscriptions();
            this.emit('connected', event);
        };

        this.socket.onmessage = (event) => {
            try {
                this.handleMessage(JSON.parse(event.data) as unknown);
            } catch (error) {
                console.error('Failed to parse Realtime WebSocket message:', error);
            }
        };

        this.socket.onclose = (event) => {
            // Ignore close from a socket we've already replaced (e.g. after reconnectWhenVisible)
            if (event.target !== this.socket) return;

            this.isConnected = false;
            this.heartbeat.stop();
            this.cleanupModules();
            this.emit('disconnected', event);

            if (this.shouldStayConnected) {
                if (event.code === 1000) {
                    console.warn('Normal closure detected - reconnecting');
                } else if (event.code === 1006) {
                    console.warn('Connection lost - reconnecting');
                }
                this.scheduleReconnect();
            }
        };

        this.socket.onerror = (error) => {
            console.error('Realtime WebSocket error:', error);
            this.emit('error', error);
        };
    }

    private handleMessage(raw: unknown): void {
        if (!raw || typeof raw !== 'object') {
            console.error('Invalid message format received:', raw);
            return;
        }

        const { module, type, data: messageData = {} } = raw as RealtimeMessage;

        if (type === 'pong') {
            this.heartbeat.onPong();
            return;
        }

        const handlers = module ? this.moduleHandlers.get(module)?.get(type ?? '') : undefined;
        handlers?.forEach((handler) => {
            try {
                handler(messageData);
            } catch (error) {
                console.error(`Error in Realtime WebSocket handler for ${module}.${type}:`, error);
            }
        });

        if (module && type) {
            this.emit(`${module}_${type}`, messageData);
        }
    }

    /** Send a message to the server. */
    send(module: string, type: string, data: Record<string, any> = {}): void {
        if (this.socket?.readyState === WebSocket.OPEN) {
            this.socket.send(JSON.stringify({ module, type, data }));
        } else {
            console.warn('Realtime WebSocket not connected, cannot send message:', { module, type, data });
        }
    }

    private scheduleReconnect(): void {
        if (!this.shouldStayConnected) {
            return;
        }

        this.reconnectAttempts++;
        const delay = Math.min(
            this.reconnectInterval * Math.pow(2, this.reconnectAttempts - 1),
            this.maxReconnectInterval,
        );

        setTimeout(() => {
            if (this.shouldStayConnected) {
                this.connect();
            }
        }, delay);
    }

    /** No-op: component cleanup should not disconnect the app-lifetime realtime connection. */
    disconnect(): void {
        // The connection should stay alive across the entire app lifecycle.
    }

    /** Force disconnect (for logout/auth revocation cleanup). */
    forceDisconnect(): void {
        this.shouldStayConnected = false;
        this.heartbeat.stop();
        this.cleanupModules();
        this.durableSubscriptions.length = 0; // Clear so next login subscribes fresh

        if (typeof document !== 'undefined' && this.visibilityListenerBound) {
            document.removeEventListener('visibilitychange', this._onVisibilityChange);
            this.visibilityListenerBound = false;
        }

        if (this.socket) {
            this.socket.close(1000, 'Force disconnect');
            this.socket = null;
        }

        this.isConnected = false;
        this.reconnectAttempts = 0;
    }

    /** Subscribe to module events. Stored durably so handlers are re-applied on reconnect. */
    subscribe(module: string, event: string, handler: MessageHandler): void {
        let moduleHandlers = this.moduleHandlers.get(module);
        if (!moduleHandlers) {
            moduleHandlers = new Map();
            this.moduleHandlers.set(module, moduleHandlers);
        }

        let eventHandlers = moduleHandlers.get(event);
        if (!eventHandlers) {
            eventHandlers = [];
            moduleHandlers.set(event, eventHandlers);
        }
        eventHandlers.push(handler);

        const hasDurable = this.durableSubscriptions.some(
            (s) => s.module === module && s.event === event && s.handler === handler,
        );
        if (!hasDurable) {
            this.durableSubscriptions.push({ module, event, handler });
        }
    }

    /** Unsubscribe from module events. */
    unsubscribe(module: string, event: string, handler: MessageHandler): void {
        const handlers = this.moduleHandlers.get(module)?.get(event);
        if (handlers) {
            const index = handlers.indexOf(handler);
            if (index > -1) {
                handlers.splice(index, 1);
            }
        }

        const durableIdx = this.durableSubscriptions.findIndex(
            (s) => s.module === module && s.event === event && s.handler === handler,
        );
        if (durableIdx > -1) {
            this.durableSubscriptions.splice(durableIdx, 1);
        }
    }

    /** Re-apply all durable subscriptions into moduleHandlers (called on connect/reconnect). */
    private _restoreSubscriptions(): void {
        for (const { module, event, handler } of this.durableSubscriptions) {
            let moduleHandlers = this.moduleHandlers.get(module);
            if (!moduleHandlers) {
                moduleHandlers = new Map();
                this.moduleHandlers.set(module, moduleHandlers);
            }
            let eventHandlers = moduleHandlers.get(event);
            if (!eventHandlers) {
                eventHandlers = [];
                moduleHandlers.set(event, eventHandlers);
            }
            eventHandlers.push(handler);
        }
    }

    /** Add global event listener. */
    on(event: string, handler: GlobalHandler): void {
        let handlers = this.globalHandlers.get(event);
        if (!handlers) {
            handlers = [];
            this.globalHandlers.set(event, handlers);
        }
        handlers.push(handler);
    }

    /** Remove global event listener. */
    off(event: string, handler: GlobalHandler): void {
        const handlers = this.globalHandlers.get(event);
        if (handlers) {
            const index = handlers.indexOf(handler);
            if (index > -1) {
                handlers.splice(index, 1);
            }
        }
    }

    private emit(event: string, data?: any): void {
        this.globalHandlers.get(event)?.forEach((handler) => {
            try {
                handler(data);
            } catch (error) {
                console.error(`Error in Realtime WebSocket global event handler for ${event}:`, error);
            }
        });
    }

    /** Request a refresh of a module's data. */
    requestRefresh(module: string): void {
        this.send(module, 'refresh');
    }

    /** Load and register all modules from the registry. */
    async loadAllModules(store: Store<RootState>): Promise<void> {
        try {
            const { loadAllModules } = await import('./modules/ModuleRegistry');
            for (const module of loadAllModules(store)) {
                this.registerModule(module);
            }
        } catch (error) {
            console.error('Failed to load modules from registry:', error);
        }
    }

    /** Register a module with the WebSocket service. */
    registerModule(module: BaseModule): void {
        if (!module.moduleName) {
            throw new Error('Module must have a moduleName property');
        }

        module.socket = this;
        this.modules.set(module.moduleName, module);

        if (this.isConnected) {
            module.initialize();
        }
    }

    /** Unregister a module from the WebSocket service. */
    unregisterModule(moduleName: string): void {
        const module = this.modules.get(moduleName);
        if (module) {
            module.cleanup();
            this.modules.delete(moduleName);
        }
    }

    private initializeModules(): void {
        this.modules.forEach((module) => {
            module.initialize();
        });
    }

    private cleanupModules(): void {
        this.modules.forEach((module) => {
            this.moduleHandlers.delete(module.moduleName);
            module.cleanup();
        });
    }

    getConnectionStatus() {
        return {
            isConnected: this.isConnected,
            reconnectAttempts: this.reconnectAttempts,
            readyState: this.socket ? this.socket.readyState : null,
            shouldStayConnected: this.shouldStayConnected,
        };
    }
}

// Create and export a singleton instance
export const realtimeSocket = new RealtimeSocket();
export default realtimeSocket;
