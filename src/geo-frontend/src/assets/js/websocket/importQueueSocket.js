/**
 * WebSocket service for real-time import queue updates.
 */

class ImportQueueSocket {
    constructor() {
        this.socket = null;
        this.isConnected = false;
        this.reconnectAttempts = 0;
        this.maxReconnectAttempts = 5;
        this.reconnectInterval = 1000; // Start with 1 second
        this.maxReconnectInterval = 30000; // Max 30 seconds
        this.eventHandlers = new Map();
        this.pingInterval = null;
        this.pingTimeout = null;
        this.connectionCount = 0; // Track how many components are using the connection
        this.shouldStayConnected = false; // Track if we should maintain connection
    }

    /**
     * Connect to the WebSocket server
     */
    connect() {
        this.connectionCount++;
        this.shouldStayConnected = true;
        
        // If already connected, just increment the counter
        if (this.socket && this.socket.readyState === WebSocket.OPEN) {
            console.log('WebSocket already connected, incrementing reference count');
            return;
        }

        // If already connecting, wait for it to complete
        if (this.socket && this.socket.readyState === WebSocket.CONNECTING) {
            console.log('WebSocket already connecting, waiting...');
            return;
        }

        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const host = window.location.host;
        const wsUrl = `${protocol}//${host}/ws/import-queue/`;
        
        console.log('Connecting to WebSocket:', wsUrl);
        
        try {
            this.socket = new WebSocket(wsUrl);
            this.setupEventHandlers();
        } catch (error) {
            console.error('Failed to create WebSocket connection:', error);
            this.scheduleReconnect();
        }
    }

    /**
     * Setup WebSocket event handlers
     */
    setupEventHandlers() {
        this.socket.onopen = (event) => {
            console.log('WebSocket connected');
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
                console.error('Failed to parse WebSocket message:', error);
            }
        };

        this.socket.onclose = (event) => {
            console.log('WebSocket disconnected:', event.code, event.reason);
            this.isConnected = false;
            this.stopPing();
            this.emit('disconnected', event);
            
            // Attempt to reconnect if we should stay connected and it's not a normal closure
            if (this.shouldStayConnected && event.code !== 1000) {
                this.scheduleReconnect();
            }
        };

        this.socket.onerror = (error) => {
            console.error('WebSocket error:', error);
            this.emit('error', error);
        };
    }

    /**
     * Handle incoming WebSocket messages
     */
    handleMessage(data) {
        const { type, data: messageData } = data;
        
        switch (type) {
            case 'initial_state':
                this.emit('initial_state', messageData);
                break;
            case 'item_added':
                this.emit('item_added', messageData);
                break;
            case 'item_deleted':
                this.emit('item_deleted', messageData);
                break;
            case 'items_deleted':
                this.emit('items_deleted', messageData);
                break;
            case 'status_updated':
                this.emit('status_updated', messageData);
                break;
            case 'item_imported':
                this.emit('item_imported', messageData);
                break;
            case 'pong':
                // Handle pong response
                break;
            case 'error':
                console.error('WebSocket server error:', messageData);
                this.emit('server_error', messageData);
                break;
            default:
                console.warn('Unknown WebSocket message type:', type);
        }
    }

    /**
     * Send a message to the server
     */
    send(data) {
        if (this.socket && this.socket.readyState === WebSocket.OPEN) {
            this.socket.send(JSON.stringify(data));
        } else {
            console.warn('WebSocket not connected, cannot send message:', data);
        }
    }

    /**
     * Send a ping to keep the connection alive
     */
    ping() {
        this.send({ type: 'ping' });
        
        // Set timeout for pong response
        this.pingTimeout = setTimeout(() => {
            console.warn('Ping timeout, closing connection');
            this.socket.close();
        }, 5000);
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
     * Disconnect from the WebSocket server
     */
    disconnect() {
        this.connectionCount = Math.max(0, this.connectionCount - 1);
        
        // Only actually disconnect if no components are using it
        if (this.connectionCount <= 0) {
            this.shouldStayConnected = false;
            this.stopPing();
            
            if (this.socket) {
                this.socket.close(1000, 'Client disconnect');
                this.socket = null;
            }
            
            this.isConnected = false;
            this.reconnectAttempts = 0;
        }
    }

    /**
     * Force disconnect (for cleanup)
     */
    forceDisconnect() {
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
     * Add event listener
     */
    on(event, handler) {
        if (!this.eventHandlers.has(event)) {
            this.eventHandlers.set(event, []);
        }
        this.eventHandlers.get(event).push(handler);
    }

    /**
     * Remove event listener
     */
    off(event, handler) {
        if (this.eventHandlers.has(event)) {
            const handlers = this.eventHandlers.get(event);
            const index = handlers.indexOf(handler);
            if (index > -1) {
                handlers.splice(index, 1);
            }
        }
    }

    /**
     * Emit event to all registered handlers
     */
    emit(event, data) {
        if (this.eventHandlers.has(event)) {
            this.eventHandlers.get(event).forEach(handler => {
                try {
                    handler(data);
                } catch (error) {
                    console.error(`Error in WebSocket event handler for ${event}:`, error);
                }
            });
        }
    }

    /**
     * Request a refresh of the import queue data
     */
    requestRefresh() {
        this.send({ type: 'refresh' });
    }

    /**
     * Get connection status
     */
    getConnectionStatus() {
        return {
            isConnected: this.isConnected,
            reconnectAttempts: this.reconnectAttempts,
            readyState: this.socket ? this.socket.readyState : null,
            connectionCount: this.connectionCount,
            shouldStayConnected: this.shouldStayConnected
        };
    }

}

// Create and export a singleton instance
export const importQueueSocket = new ImportQueueSocket();
export default importQueueSocket;
