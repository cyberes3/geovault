/**
 * Shared app-level WebSocket ping/pong heartbeat.
 *
 * App-level ping/pong (a JSON message round-tripped through the socket) is distinct from the
 * browser's own WebSocket protocol-level ping/pong, which is handled automatically and is
 * invisible to JS. It exists to detect a "zombie" connection: the socket is technically open and
 * no error/close event has fired, but the server has silently stopped delivering messages (e.g.
 * a backend process restart that drops channel-layer group membership without a clean close).
 * Without an app-level round trip, a client has no way to notice that and would otherwise wait
 * forever for updates that will never arrive.
 *
 * This class is deliberately agnostic of any particular wire shape, transport, or reconnect
 * strategy -- the caller supplies `sendPing` (build + send a ping message in its own format) and
 * `onTimeout` (react to a missed pong, typically by force-closing the socket so the caller's
 * existing close/reconnect handling takes over). The caller is responsible for calling `onPong()`
 * whenever it receives a message it recognizes as a pong reply.
 */

export interface WebSocketHeartbeatOptions {
    /** Sends a ping message over the caller's socket. */
    sendPing: () => void;
    /** Called when no pong arrives within `timeoutMs`. */
    onTimeout: () => void;
    /** Milliseconds between pings. Defaults to 30s. */
    intervalMs?: number;
    /** Milliseconds to wait for a pong before `onTimeout`. Defaults to 10s. */
    timeoutMs?: number;
}

export class WebSocketHeartbeat {
    private readonly sendPing: () => void;
    private readonly onTimeoutCallback: () => void;
    private readonly intervalMs: number;
    private readonly timeoutMs: number;
    private intervalId: ReturnType<typeof setInterval> | null = null;
    private timeoutId: ReturnType<typeof setTimeout> | null = null;

    constructor({ sendPing, onTimeout, intervalMs = 30000, timeoutMs = 10000 }: WebSocketHeartbeatOptions) {
        this.sendPing = sendPing;
        this.onTimeoutCallback = onTimeout;
        this.intervalMs = intervalMs;
        this.timeoutMs = timeoutMs;
    }

    /** Begin sending periodic pings. Safe to call repeatedly; restarts the interval. */
    start(): void {
        this.stop();
        this.intervalId = setInterval(() => {
            this.sendPingAndArmTimeout();
        }, this.intervalMs);
    }

    /** Stop sending pings and cancel any pending timeout. Safe to call when already stopped. */
    stop(): void {
        if (this.intervalId) {
            clearInterval(this.intervalId);
            this.intervalId = null;
        }
        this.clearTimeout();
    }

    /** Call when a pong reply is received; cancels the pending timeout for the in-flight ping. */
    onPong(): void {
        this.clearTimeout();
    }

    private sendPingAndArmTimeout(): void {
        this.sendPing();
        this.clearTimeout();
        this.timeoutId = setTimeout(() => {
            this.timeoutId = null;
            this.onTimeoutCallback();
        }, this.timeoutMs);
    }

    private clearTimeout(): void {
        if (this.timeoutId) {
            clearTimeout(this.timeoutId);
            this.timeoutId = null;
        }
    }
}
