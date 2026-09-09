/**
 * Shared WebSocket client for every GeoVault browser connection.
 *
 * Three instances exist, each with its own URL and lifetime:
 *   - `/ws/realtime/` — app lifetime (module multiplexer)
 *   - `/ws/upload/status/:id/` — process-page lifetime
 *   - `/ws/extensions/live-track/trackers-live/` — live-track view lifetime
 *
 * Heartbeat, cancelable reconnect, stale-socket `onclose`, and exponential
 * backoff + jitter live here so those policies cannot drift across sockets.
 * Visibility changes never reset the backoff counter: a backgrounded tab that
 * keeps failing would otherwise hammer the server every time it is shown.
 */

import { WebSocketHeartbeat } from './WebSocketHeartbeat';

export type SocketHandler = (data?: unknown) => void;

export interface GeoVaultSocketOpenInfo {
    reconnect: boolean;
}

export interface GeoVaultSocketCloseInfo {
    code: number;
    reason: string;
}

export interface GeoVaultSocketOptions {
    url: string | (() => string);
    /** JSON ping body. Defaults to `{ type: 'ping' }`. */
    pingPayload?: Record<string, unknown>;
    /** Maps an incoming JSON object to a handler key. Defaults to `type`. */
    messageKey?: (message: Record<string, unknown>) => string | null;
    /** Payload passed to handlers. Defaults to `message.data`. */
    messageData?: (message: Record<string, unknown>) => unknown;
    /** Close codes that must not trigger reconnect (e.g. 4004 item-not-found). */
    terminalCloseCodes?: readonly number[];
    /** `undefined` means retry indefinitely. */
    maxReconnectAttempts?: number;
    reconnectBaseDelayMs?: number;
    reconnectMaxDelayMs?: number;
    /**
     * Equal-jitter ratio in `[0, 1]`. `0.5` yields `delay * (0.5 + random * 0.5)`.
     * `0` disables jitter (deterministic tests).
     */
    reconnectJitterRatio?: number;
}

export function sameOriginWebSocketUrl(path: string): string {
    if (typeof window === 'undefined') {
        return '';
    }
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const normalized = path.startsWith('/') ? path : `/${path}`;
    return `${protocol}//${window.location.host}${normalized}`;
}

function defaultMessageKey(message: Record<string, unknown>): string | null {
    return typeof message.type === 'string' ? message.type : null;
}

function defaultMessageData(message: Record<string, unknown>): unknown {
    return message.data;
}

export class GeoVaultSocket {
    private socket: WebSocket | null = null;
    private readonly handlers = new Map<string, Set<SocketHandler>>();
    private readonly heartbeat: WebSocketHeartbeat;
    private readonly resolveUrl: () => string;
    private readonly pingPayload: Record<string, unknown>;
    private readonly messageKey: (message: Record<string, unknown>) => string | null;
    private readonly messageData: (message: Record<string, unknown>) => unknown;
    private readonly terminalCloseCodes: ReadonlySet<number>;
    private readonly maxReconnectAttempts: number | null;
    private readonly reconnectBaseDelayMs: number;
    private readonly reconnectMaxDelayMs: number;
    private readonly reconnectJitterRatio: number;

    private connected = false;
    private shouldStayConnected = false;
    private attemptCount = 0;
    private reconnectTimeoutId: ReturnType<typeof setTimeout> | null = null;
    private visibilityListenerBound = false;

    constructor(options: GeoVaultSocketOptions) {
        const url = options.url;
        this.resolveUrl = typeof url === 'function' ? url : () => url;
        this.pingPayload = options.pingPayload ?? { type: 'ping' };
        this.messageKey = options.messageKey ?? defaultMessageKey;
        this.messageData = options.messageData ?? defaultMessageData;
        this.terminalCloseCodes = new Set(options.terminalCloseCodes ?? []);
        this.maxReconnectAttempts = options.maxReconnectAttempts ?? null;
        this.reconnectBaseDelayMs = options.reconnectBaseDelayMs ?? 1000;
        this.reconnectMaxDelayMs = options.reconnectMaxDelayMs ?? 30000;
        this.reconnectJitterRatio = options.reconnectJitterRatio ?? 0.5;

        this.heartbeat = new WebSocketHeartbeat({
            sendPing: () => {
                if (this.socket?.readyState === WebSocket.OPEN) {
                    this.socket.send(JSON.stringify(this.pingPayload));
                }
            },
            onTimeout: () => {
                console.warn('WebSocket ping timeout, forcing reconnect');
                this.socket?.close(1006, 'Ping timeout');
            },
        });
    }

    get isConnected(): boolean {
        return this.connected;
    }

    get reconnectAttempts(): number {
        return this.attemptCount;
    }

    connect(): void {
        this.shouldStayConnected = true;
        this.bindVisibilityListener();

        if (this.socket && (this.socket.readyState === WebSocket.OPEN || this.socket.readyState === WebSocket.CONNECTING)) {
            return;
        }

        const url = this.resolveUrl();
        if (!url) {
            return;
        }

        try {
            const socket = new WebSocket(url);
            this.socket = socket;
            this.attachSocketHandlers(socket);
        } catch (error) {
            console.error('Failed to create WebSocket connection:', error);
            this.scheduleReconnect();
        }
    }

    /** Stop wanting a connection: cancel backoff and close. The socket's own `onclose` is current. */
    disconnect(): void {
        this.shouldStayConnected = false;
        this.cancelReconnect();
        this.heartbeat.stop();
        this.unbindVisibilityListener();
        this.attemptCount = 0;

        const socket = this.socket;
        if (socket && socket.readyState !== WebSocket.CLOSED && socket.readyState !== WebSocket.CLOSING) {
            socket.close(1000, 'Client disconnect');
            return;
        }

        this.socket = null;
        if (this.connected) {
            this.connected = false;
            this.emit('disconnected', { code: 1000, reason: 'Client disconnect' });
        }
    }

    send(payload: unknown): void {
        if (this.socket?.readyState === WebSocket.OPEN) {
            this.socket.send(typeof payload === 'string' ? payload : JSON.stringify(payload));
            return;
        }
        console.warn('WebSocket not connected, cannot send message:', payload);
    }

    on<T = unknown>(key: string, handler: (data: T) => void): () => void {
        const wrapped = handler as SocketHandler;
        let set = this.handlers.get(key);
        if (!set) {
            set = new Set();
            this.handlers.set(key, set);
        }
        set.add(wrapped);
        return () => {
            this.off(key, wrapped);
        };
    }

    off(key: string, handler: SocketHandler): void {
        this.handlers.get(key)?.delete(handler);
    }

    private attachSocketHandlers(socket: WebSocket): void {
        socket.onopen = () => {
            if (socket !== this.socket) {
                return;
            }
            const reconnect = this.attemptCount > 0;
            this.attemptCount = 0;
            this.connected = true;
            this.heartbeat.start();
            this.emit('connected', { reconnect });
        };

        socket.onmessage = (event: MessageEvent<string>) => {
            if (socket !== this.socket) {
                return;
            }
            this.handleMessage(event.data);
        };

        socket.onclose = (event: CloseEvent) => {
            if (event.target !== this.socket) {
                return;
            }

            this.socket = null;
            this.connected = false;
            this.heartbeat.stop();
            this.emit('disconnected', { code: event.code, reason: event.reason });
            this.emit('close', { code: event.code, reason: event.reason });

            if (this.shouldStayConnected && !this.terminalCloseCodes.has(event.code)) {
                this.scheduleReconnect();
            }
        };

        socket.onerror = (error) => {
            if (socket !== this.socket) {
                return;
            }
            console.error('WebSocket error:', error);
            this.emit('error', error);
        };
    }

    private handleMessage(raw: string): void {
        let parsed: unknown;
        try {
            parsed = JSON.parse(raw);
        } catch (error) {
            console.error('Failed to parse WebSocket message:', error);
            return;
        }

        if (!parsed || typeof parsed !== 'object') {
            console.error('Invalid WebSocket message format received:', parsed);
            return;
        }

        const message = parsed as Record<string, unknown>;
        if (message.type === 'pong') {
            this.heartbeat.onPong();
            return;
        }

        const key = this.messageKey(message);
        if (!key) {
            return;
        }
        this.emit(key, this.messageData(message));
    }

    private emit(key: string, data?: unknown): void {
        this.handlers.get(key)?.forEach((handler) => {
            try {
                handler(data);
            } catch (error) {
                console.error(`Error in WebSocket handler for ${key}:`, error);
            }
        });
    }

    private scheduleReconnect(): void {
        if (!this.shouldStayConnected || this.reconnectTimeoutId != null) {
            return;
        }
        if (this.maxReconnectAttempts != null && this.attemptCount >= this.maxReconnectAttempts) {
            return;
        }

        const delay = this.computeBackoffDelay(this.attemptCount);
        this.attemptCount += 1;
        this.reconnectTimeoutId = setTimeout(() => {
            this.reconnectTimeoutId = null;
            if (this.shouldStayConnected) {
                this.connect();
            }
        }, delay);
    }

    private computeBackoffDelay(attempts: number): number {
        const exponential = Math.min(
            this.reconnectBaseDelayMs * 2 ** attempts,
            this.reconnectMaxDelayMs,
        );
        if (this.reconnectJitterRatio <= 0) {
            return exponential;
        }
        const jitter = this.reconnectJitterRatio;
        return exponential * ((1 - jitter) + Math.random() * jitter);
    }

    private cancelReconnect(): void {
        if (this.reconnectTimeoutId != null) {
            clearTimeout(this.reconnectTimeoutId);
            this.reconnectTimeoutId = null;
        }
    }

    private bindVisibilityListener(): void {
        if (this.visibilityListenerBound || typeof document === 'undefined') {
            return;
        }
        document.addEventListener('visibilitychange', this.onVisibilityChange);
        this.visibilityListenerBound = true;
    }

    private unbindVisibilityListener(): void {
        if (!this.visibilityListenerBound || typeof document === 'undefined') {
            return;
        }
        document.removeEventListener('visibilitychange', this.onVisibilityChange);
        this.visibilityListenerBound = false;
    }

    private onVisibilityChange = (): void => {
        if (document.visibilityState !== 'visible' || !this.shouldStayConnected) {
            return;
        }
        if (this.socket && (this.socket.readyState === WebSocket.OPEN || this.socket.readyState === WebSocket.CONNECTING)) {
            return;
        }
        this.cancelReconnect();
        this.connect();
    };
}
