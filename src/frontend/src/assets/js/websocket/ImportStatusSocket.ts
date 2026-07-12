/**
 * Per-item WebSocket connection to `/ws/upload/status/:id/`, used by `ImportProcessPage` to
 * receive live processing/page/log updates for a single import queue item while it's open.
 *
 * This is deliberately a *separate* connection from `realtimeSocket` (the app-lifetime,
 * multiplexed `/ws/realtime/` connection used by the WebSocket module registry): the backend
 * scopes this socket to one authorized item via the URL itself (`ProcessStatusConsumer`), rather
 * than multiplexing a `module` field over a shared per-user connection, and it only needs to
 * exist while this one page is open. It reuses the same `WebSocketHeartbeat` app-level ping/pong
 * and mirrors `realtimeSocket`'s reconnect/backoff behavior so there's still exactly one
 * *implementation* of that logic, even though the transport itself can't be shared.
 */

import { WebSocketHeartbeat } from './WebSocketHeartbeat.js';

export type ImportStatusMessageType =
    | 'initial_state'
    | 'status'
    | 'status_updated'
    | 'log_added'
    | 'item_completed'
    | 'item_failed'
    | 'page'
    | 'logs'
    | 'item_deleted'
    | 'error';

type MessageHandler = (data: any) => void;

export interface ImportStatusSocketOptions {
    maxReconnectAttempts?: number;
    reconnectDelayMs?: number;
}

export class ImportStatusSocket {
    private ws: WebSocket | null = null;
    private itemId: string | number | null = null;
    private connected = false;
    private reconnectAttempts = 0;
    private closedByCaller = false;
    private readonly maxReconnectAttempts: number;
    private readonly reconnectDelayMs: number;
    private readonly heartbeat: WebSocketHeartbeat;
    private readonly handlers = new Map<ImportStatusMessageType, MessageHandler[]>();

    constructor(options: ImportStatusSocketOptions = {}) {
        this.maxReconnectAttempts = options.maxReconnectAttempts ?? 5;
        this.reconnectDelayMs = options.reconnectDelayMs ?? 2000;
        this.heartbeat = new WebSocketHeartbeat({
            sendPing: () => {
                this.sendRaw('ping', {});
            },
            onTimeout: () => {
                console.warn('Import status WebSocket ping timeout, forcing reconnect');
                this.ws?.close(1006, 'Ping timeout'); // Triggers onclose and reconnection
            },
        });
    }

    get isConnected(): boolean {
        return this.connected;
    }

    /** Register a handler for a message type. Returns an unsubscribe function. */
    on(type: ImportStatusMessageType, handler: MessageHandler): () => void {
        let list = this.handlers.get(type);
        if (!list) {
            list = [];
            this.handlers.set(type, list);
        }
        list.push(handler);
        return () => {
            this.off(type, handler);
        };
    }

    off(type: ImportStatusMessageType, handler: MessageHandler): void {
        const list = this.handlers.get(type);
        if (!list) return;
        const index = list.indexOf(handler);
        if (index > -1) list.splice(index, 1);
    }

    /** Open the connection for a given import queue item id. */
    connect(itemId: string | number): void {
        this.itemId = itemId;
        this.closedByCaller = false;
        this.reconnectAttempts = 0;
        this.openSocket();
    }

    /** Permanently close the connection (e.g. component unmount or navigating to a new item). */
    close(): void {
        this.closedByCaller = true;
        this.itemId = null;
        this.heartbeat.stop();
        if (this.ws) {
            this.ws.close(1000, 'Normal closure');
            this.ws = null;
        }
        this.connected = false;
    }

    send(type: string, data: Record<string, any> = {}): void {
        this.sendRaw(type, data);
    }

    private sendRaw(type: string, data: Record<string, any>): void {
        if (this.ws && this.connected) {
            this.ws.send(JSON.stringify({ type, data }));
        }
    }

    private openSocket(): void {
        if (!this.itemId) return;

        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const url = `${protocol}//${window.location.host}/ws/upload/status/${this.itemId}/`;

        this.ws = new WebSocket(url);

        this.ws.onopen = () => {
            this.connected = true;
            this.reconnectAttempts = 0;
            this.heartbeat.start();
        };

        this.ws.onmessage = (event) => {
            let message: { type?: string; data?: any };
            try {
                message = JSON.parse(event.data) as { type?: string; data?: any };
            } catch (error) {
                console.error('Failed to parse import status WebSocket message:', error);
                return;
            }

            if (message.type === 'pong') {
                this.heartbeat.onPong();
                return;
            }

            if (message.type) {
                this.emit(message.type as ImportStatusMessageType, message.data);
            }
        };

        this.ws.onclose = (event) => {
            this.connected = false;
            this.heartbeat.stop();

            if (this.shouldReconnect(event)) {
                this.reconnectAttempts++;
                setTimeout(() => {
                    if (!this.closedByCaller) this.openSocket();
                }, this.reconnectDelayMs);
            }

            // 4004 = item not found. Emit as an `error` so the page can redirect, matching the
            // shape the server itself uses for other rejected-item errors.
            if (event.code === 4004) {
                this.emit('error', { code: 404, message: 'Item not found' });
            }
        };

        this.ws.onerror = (error) => {
            console.error('Import status WebSocket error:', error);
        };
    }

    private shouldReconnect(event: CloseEvent): boolean {
        if (this.closedByCaller || !this.itemId) return false;
        if (event.code === 4004) return false; // Item not found; retrying won't help.
        return event.code !== 1000 && this.reconnectAttempts < this.maxReconnectAttempts;
    }

    private emit(type: ImportStatusMessageType, data: any): void {
        this.handlers.get(type)?.forEach((handler) => {
            try {
                handler(data);
            } catch (error) {
                console.error(`Error in import status WebSocket handler for ${type}:`, error);
            }
        });
    }
}
