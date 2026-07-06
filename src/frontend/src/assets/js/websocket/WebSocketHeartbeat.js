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
export class WebSocketHeartbeat {
  /**
   * @param {Object} options
   * @param {() => void} options.sendPing - Sends a ping message over the caller's socket.
   * @param {() => void} options.onTimeout - Called when no pong arrives within `timeoutMs`.
   * @param {number} [options.intervalMs] - Milliseconds between pings. Defaults to 30s.
   * @param {number} [options.timeoutMs] - Milliseconds to wait for a pong before `onTimeout`. Defaults to 10s.
   */
  constructor({ sendPing, onTimeout, intervalMs = 30000, timeoutMs = 10000 }) {
    this.sendPing = sendPing;
    this.onTimeout = onTimeout;
    this.intervalMs = intervalMs;
    this.timeoutMs = timeoutMs;
    this.intervalId = null;
    this.timeoutId = null;
  }

  /** Begin sending periodic pings. Safe to call repeatedly; restarts the interval. */
  start() {
    this.stop();
    this.intervalId = setInterval(() => {
      this._sendPingAndArmTimeout();
    }, this.intervalMs);
  }

  /** Stop sending pings and cancel any pending timeout. Safe to call when already stopped. */
  stop() {
    if (this.intervalId) {
      clearInterval(this.intervalId);
      this.intervalId = null;
    }
    this._clearTimeout();
  }

  /** Call when a pong reply is received; cancels the pending timeout for the in-flight ping. */
  onPong() {
    this._clearTimeout();
  }

  _sendPingAndArmTimeout() {
    this.sendPing();
    this._clearTimeout();
    this.timeoutId = setTimeout(() => {
      this.timeoutId = null;
      this.onTimeout();
    }, this.timeoutMs);
  }

  _clearTimeout() {
    if (this.timeoutId) {
      clearTimeout(this.timeoutId);
      this.timeoutId = null;
    }
  }
}
