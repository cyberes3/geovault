/**
 * Global WebSocket service for real-time updates.
 */

class RealtimeSocket {
    constructor() {
        this.socket = null;
        this.isConnected = false;
        this.reconnectAttempts = 0;
        this.maxReconnectAttempts = 5;
        this.reconnectInterval = 1000; // Start with 1 second
        this.maxReconnectInterval = 30000; // Max 30 seconds
        this.moduleHandlers = new Map(); // Map of module -> event -> handlers
        this.pingInterval = null;
        this.pingTimeout = null;
        this.shouldStayConnected = false; // Track if we should maintain connection
    }

    /**
     * Connect to the WebSocket server
     */
    connect() {
        this.shouldStayConnected = true;
        
        // If already connected, don't create another connection
        if (this.socket && this.socket.readyState === WebSocket.OPEN) {
            console.log('Realtime WebSocket already connected');
            return;
        }

        // If already connecting, wait for it to complete
        if (this.socket && this.socket.readyState === WebSocket.CONNECTING) {
            console.log('Realtime WebSocket already connecting, waiting...');
            return;
        }

        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const host = window.location.host;
        const wsUrl = `${protocol}//${host}/ws/realtime/`;
        
        console.log('Connecting to Realtime WebSocket:', wsUrl);
        
        try {
            this.socket = new WebSocket(wsUrl);
            this.setupEventHandlers();
        } catch (error) {
            console.error('Failed to create Realtime WebSocket connection:', error);
            this.scheduleReconnect();
        }
    }

    /**
     * Setup WebSocket event handlers
     */
    setupEventHandlers() {
        this.socket.onopen = (event) => {
            console.log('Realtime WebSocket connected');
            this.isConnected = true;
            this.reconnectAttempts = 0;
            this.reconnectInterval = 1000;
            this.startPing();
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
            console.log('Realtime WebSocket disconnected:', event.code, event.reason);
            console.log('Should stay connected:', this.shouldStayConnected);
            console.log('Event code:', event.code, '(1000 = normal closure)');
            console.log('Connection was clean:', event.wasClean);
            console.trace('WebSocket close call stack:');
            this.isConnected = false;
            this.stopPing();
            this.emit('disconnected', event);
            
            // Attempt to reconnect if we should stay connected
            if (this.shouldStayConnected) {
                if (event.code === 1000) {
                    console.log('Normal closure detected - this should not happen unless user logged out');
                    console.log('This indicates something is calling socket.close(1000) or socket.close()');
                    console.log('But we should stay connected, so attempting reconnection...');
                } else if (event.code === 1006) {
                    console.log('Ping timeout detected - triggering reconnection');
                } else {
                    console.log('Connection lost - triggering reconnection');
                }
                console.log('Scheduling reconnect...');
                this.scheduleReconnect();
            } else {
                console.log('Not reconnecting - shouldStayConnected is false');
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
        const { module, type, data: messageData } = data;
        
        // Handle ping/pong
        if (type === 'pong') {
            console.log('Received pong, clearing ping timeout');
            if (this.pingTimeout) {
                clearTimeout(this.pingTimeout);
                this.pingTimeout = null;
            }
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
        
        // Emit global event
        this.emit(`${module}_${type}`, messageData);
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
     * Send a ping to keep the connection alive
     */
    ping() {
        console.log('Sending ping...');
        this.send('ping', 'ping');
        
        // Set timeout for pong response
        this.pingTimeout = setTimeout(() => {
            console.warn('Ping timeout, triggering reconnection');
            // Trigger reconnection instead of closing
            if (this.socket) {
                this.socket.close(1006, 'Ping timeout'); // This will trigger onclose and reconnection
            }
        }, 10000); // Increased timeout to 10 seconds
    }

    /**
     * Start periodic ping
     */
    startPing() {
        this.pingInterval = setInterval(() => {
            this.ping();
        }, 30000); // Ping every 30 seconds
    }

    /**
     * Stop periodic ping
     */
    stopPing() {
        if (this.pingInterval) {
            clearInterval(this.pingInterval);
            this.pingInterval = null;
        }
        if (this.pingTimeout) {
            clearTimeout(this.pingTimeout);
            this.pingTimeout = null;
        }
    }

    /**
     * Schedule reconnection attempt
     */
    scheduleReconnect() {
        if (!this.shouldStayConnected) {
            console.log('Not scheduling reconnect - should not stay connected');
            return;
        }
        
        if (this.reconnectAttempts >= this.maxReconnectAttempts) {
            console.error('Max reconnection attempts reached');
            this.emit('max_reconnect_attempts_reached');
            return;
        }

        this.reconnectAttempts++;
        const delay = Math.min(this.reconnectInterval * Math.pow(2, this.reconnectAttempts - 1), this.maxReconnectInterval);
        
        console.log(`Attempting to reconnect in ${delay}ms (attempt ${this.reconnectAttempts}/${this.maxReconnectAttempts})`);
        
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
        console.log('Disconnect called but connection will remain active');
        console.trace('Disconnect call stack:');
    }

    /**
     * Force disconnect (for cleanup)
     */
    forceDisconnect() {
        console.log('forceDisconnect() called - this should only happen on logout');
        console.trace('forceDisconnect call stack:');
        this.connectionCount = 0;
        this.shouldStayConnected = false;
        this.stopPing();
        
        if (this.socket) {
            this.socket.close(1000, 'Force disconnect');
            this.socket = null;
        }
        
        this.isConnected = false;
        this.reconnectAttempts = 0;
    }

    /**
     * Subscribe to module events
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
    }

    /**
     * Add global event listener
     */
    on(event, handler) {
        if (!this.globalHandlers) {
            this.globalHandlers = new Map();
        }
        
        if (!this.globalHandlers.has(event)) {
            this.globalHandlers.set(event, []);
        }
        this.globalHandlers.get(event).push(handler);
    }

    /**
     * Remove global event listener
     */
    off(event, handler) {
        if (this.globalHandlers && this.globalHandlers.has(event)) {
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
        if (this.globalHandlers && this.globalHandlers.has(event)) {
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
