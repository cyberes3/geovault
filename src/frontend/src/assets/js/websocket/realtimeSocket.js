/**
 * Global WebSocket service for real-time updates.
 */

import { WebSocketHeartbeat } from './WebSocketHeartbeat.js';

class RealtimeSocket {
    constructor() {
        this.socket = null;
        this.isConnected = false;
        this.reconnectAttempts = 0;
        this.reconnectInterval = 1000; // Start with 1 second
        this.maxReconnectInterval = 30000; // Max 30 seconds
        this.moduleHandlers = new Map(); // Map of module -> event -> handlers
        this.durableSubscriptions = []; // { module, event, handler }[] restored on reconnect
        this.globalHandlers = new Map(); // Global event handlers
        this.modules = new Map(); // Registered modules
        this.heartbeat = new WebSocketHeartbeat({
            sendPing: () => { this.send('ping', 'ping'); },
            onTimeout: () => {
                console.warn('Ping timeout, triggering reconnection');
                if (this.socket) {
                    this.socket.close(1006, 'Ping timeout'); // This will trigger onclose and reconnection
                }
            }
        });
        this.shouldStayConnected = false; // Track if we should maintain connection
        this.visibilityListenerBound = false;
        this._onVisibilityChange = this._onVisibilityChange.bind(this);
    }

    /**
     * Connect to the WebSocket server
     */
    connect() {
        this.shouldStayConnected = true;
        
        // If already connected, don't create another connection
        if (this.socket && this.socket.readyState === WebSocket.OPEN) {
            return;
        }

        // If already connecting, wait for it to complete
        if (this.socket && this.socket.readyState === WebSocket.CONNECTING) {
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
    _bindVisibilityListener() {
        if (this.visibilityListenerBound || typeof document === 'undefined') return;
        document.addEventListener('visibilitychange', this._onVisibilityChange);
        this.visibilityListenerBound = true;
    }

    _onVisibilityChange() {
        if (document.visibilityState !== 'visible' || !this.shouldStayConnected) return;
        // User returned to the tab; ensure we have a live connection (mobile often drops WS when backgrounded)
        this.reconnectWhenVisible();
    }

    /**
     * Reconnect when the page becomes visible again (e.g. after background).
     * Closes any existing socket and opens a fresh one, resetting reconnect count.
     */
    reconnectWhenVisible() {
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

    /**
     * Setup WebSocket event handlers
     */
    setupEventHandlers() {
        this.socket.onopen = (event) => {
            this.isConnected = true;
            this.reconnectAttempts = 0;
            this.reconnectInterval = 1000;
            this.heartbeat.start();
            this.initializeModules(); // Initialize all registered modules
            this._restoreSubscriptions(); // Re-subscribe all module handlers after reconnect
            this.emit('connected', event);
        };

        this.socket.onmessage = (event) => {
            try {
                const data = JSON.parse(event.data);
                this.handleMessage(data);
            } catch (error) {
                console.error('Failed to parse Realtime WebSocket message:', error);
            }
        };

        this.socket.onclose = (event) => {
            // Ignore close from a socket we've already replaced (e.g. after reconnectWhenVisible)
            if (event.target !== this.socket) return;

            this.isConnected = false;
            this.heartbeat.stop();
            this.cleanupModules(); // Cleanup all registered modules
            this.emit('disconnected', event);

            // Attempt to reconnect if we should stay connected
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

    /**
     * Handle incoming WebSocket messages
     */
    handleMessage(data) {
        // Handle malformed messages gracefully
        if (!data || typeof data !== 'object') {
            console.error('Invalid message format received:', data);
            return;
        }
        
        const { module, type, data: messageData = {} } = data;

        // Handle ping/pong
        if (type === 'pong') {
            this.heartbeat.onPong();
            return;
        }
        
        // Route to module handlers
        if (module && this.moduleHandlers.has(module)) {
            const moduleHandlers = this.moduleHandlers.get(module);
            if (moduleHandlers.has(type)) {
                moduleHandlers.get(type).forEach(handler => {
                    try {
                        handler(messageData);
                    } catch (error) {
                        console.error(`Error in Realtime WebSocket handler for ${module}.${type}:`, error);
                    }
                });
            }
        }
        
        // Emit global event (only if we have both module and type)
        if (module && type) {
            this.emit(`${module}_${type}`, messageData);
        }
    }

    /**
     * Send a message to the server
     */
    send(module, type, data = {}) {
        if (this.socket && this.socket.readyState === WebSocket.OPEN) {
            this.socket.send(JSON.stringify({
                module,
                type,
                data
            }));
        } else {
            console.warn('Realtime WebSocket not connected, cannot send message:', { module, type, data });
        }
    }

    /**
     * Schedule reconnection attempt
     */
    scheduleReconnect() {
        if (!this.shouldStayConnected) {
            return;
        }

        this.reconnectAttempts++;
        const delay = Math.min(this.reconnectInterval * Math.pow(2, this.reconnectAttempts - 1), this.maxReconnectInterval);
        
        setTimeout(() => {
            if (this.shouldStayConnected) {
                this.connect();
            }
        }, delay);
    }

    /**
     * Disconnect from the WebSocket server (only for component cleanup)
     */
    disconnect() {
        // Don't actually disconnect - this is just for component cleanup
        // The connection should stay alive across the entire app lifecycle
    }

    /**
     * Force disconnect (for cleanup)
     */
    forceDisconnect() {
        this.shouldStayConnected = false;
        this.heartbeat.stop();
        this.cleanupModules(); // Cleanup all registered modules
        this.durableSubscriptions = []; // Clear so next login subscribes fresh

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

    /**
     * Subscribe to module events. Stored durably so handlers are re-applied on reconnect.
     */
    subscribe(module, event, handler) {
        if (!this.moduleHandlers.has(module)) {
            this.moduleHandlers.set(module, new Map());
        }

        const moduleHandlers = this.moduleHandlers.get(module);
        if (!moduleHandlers.has(event)) {
            moduleHandlers.set(event, []);
        }

        moduleHandlers.get(event).push(handler);

        const hasDurable = this.durableSubscriptions.some(
            (s) => s.module === module && s.event === event && s.handler === handler
        );
        if (!hasDurable) {
            this.durableSubscriptions.push({ module, event, handler });
        }
    }

    /**
     * Unsubscribe from module events
     */
    unsubscribe(module, event, handler) {
        if (this.moduleHandlers.has(module)) {
            const moduleHandlers = this.moduleHandlers.get(module);
            if (moduleHandlers.has(event)) {
                const handlers = moduleHandlers.get(event);
                const index = handlers.indexOf(handler);
                if (index > -1) {
                    handlers.splice(index, 1);
                }
            }
        }
        const durableIdx = this.durableSubscriptions.findIndex(
            (s) => s.module === module && s.event === event && s.handler === handler
        );
        if (durableIdx > -1) {
            this.durableSubscriptions.splice(durableIdx, 1);
        }
    }

    /**
     * Re-apply all durable subscriptions into moduleHandlers (called on connect/reconnect).
     */
    _restoreSubscriptions() {
        for (const { module, event, handler } of this.durableSubscriptions) {
            if (!this.moduleHandlers.has(module)) {
                this.moduleHandlers.set(module, new Map());
            }
            const moduleHandlers = this.moduleHandlers.get(module);
            if (!moduleHandlers.has(event)) {
                moduleHandlers.set(event, []);
            }
            moduleHandlers.get(event).push(handler);
        }
    }

    /**
     * Add global event listener
     */
    on(event, handler) {
        if (!this.globalHandlers.has(event)) {
            this.globalHandlers.set(event, []);
        }
        this.globalHandlers.get(event).push(handler);
    }

    /**
     * Remove global event listener
     */
    off(event, handler) {
        if (this.globalHandlers.has(event)) {
            const handlers = this.globalHandlers.get(event);
            const index = handlers.indexOf(handler);
            if (index > -1) {
                handlers.splice(index, 1);
            }
        }
    }

    /**
     * Emit global event to all registered handlers
     */
    emit(event, data) {
        if (this.globalHandlers.has(event)) {
            this.globalHandlers.get(event).forEach(handler => {
                try {
                    handler(data);
                } catch (error) {
                    console.error(`Error in Realtime WebSocket global event handler for ${event}:`, error);
                }
            });
        }
    }

    /**
     * Request a refresh of module data
     */
    requestRefresh(module) {
        this.send(module, 'refresh');
    }

    /**
     * Load all modules from the registry
     * @param {Object} store - Vuex store instance
     */
    async loadAllModules(store) {
        try {
            const { loadAllModules } = await import('./modules/ModuleRegistry.js');
            const modules = loadAllModules(store);
            
            // Register all loaded modules
            for (const module of modules) {
                this.registerModule(module);
            }
            
        } catch (error) {
            console.error('Failed to load modules from registry:', error);
        }
    }

    /**
     * Register a module with the WebSocket service
     * @param {BaseModule} module - The module to register
     */
    registerModule(module) {
        if (!module.moduleName) {
            throw new Error('Module must have a moduleName property');
        }
        
        // Set socket reference for the module
        module.socket = this;
        
        this.modules.set(module.moduleName, module);
        
        // If already connected, initialize the module immediately
        if (this.isConnected) {
            module.initialize();
        }
    }

    /**
     * Unregister a module from the WebSocket service
     * @param {string} moduleName - The name of the module to unregister
     */
    unregisterModule(moduleName) {
        const module = this.modules.get(moduleName);
        if (module) {
            module.cleanup();
            this.modules.delete(moduleName);
        }
    }

    /**
     * Initialize all registered modules (called on connection)
     */
    initializeModules() {
        this.modules.forEach(module => {
            module.initialize();
        });
    }

    /**
     * Cleanup all registered modules (called on disconnection)
     */
    cleanupModules() {
        this.modules.forEach(module => {
            // Clear all subscriptions for this module
            if (this.moduleHandlers.has(module.moduleName)) {
                this.moduleHandlers.delete(module.moduleName);
            }
            module.cleanup();
        });
    }

    /**
     * Get connection status
     */
    getConnectionStatus() {
        return {
            isConnected: this.isConnected,
            reconnectAttempts: this.reconnectAttempts,
            readyState: this.socket ? this.socket.readyState : null,
            shouldStayConnected: this.shouldStayConnected
        };
    }
}

// Create and export a singleton instance
export const realtimeSocket = new RealtimeSocket();
export default realtimeSocket;
