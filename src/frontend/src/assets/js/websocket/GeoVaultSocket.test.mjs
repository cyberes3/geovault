import test from 'node:test';
import assert from 'node:assert/strict';
import { mock } from 'node:test';
import { GeoVaultSocket } from './GeoVaultSocket.ts';

const OPEN = 1;
const CONNECTING = 0;
const CLOSING = 2;
const CLOSED = 3;

class FakeWebSocket {
    static instances = [];
    static OPEN = OPEN;
    static CONNECTING = CONNECTING;
    static CLOSING = CLOSING;
    static CLOSED = CLOSED;

    constructor(url) {
        this.url = url;
        this.readyState = CONNECTING;
        this.sent = [];
        this.onopen = null;
        this.onclose = null;
        this.onmessage = null;
        this.onerror = null;
        FakeWebSocket.instances.push(this);
    }

    send(payload) {
        this.sent.push(payload);
    }

    close(code = 1000, reason = '') {
        this.readyState = CLOSED;
        this.onclose?.({ target: this, code, reason });
    }

    open() {
        this.readyState = OPEN;
        this.onopen?.({});
    }

    deliver(payload) {
        this.onmessage?.({ data: typeof payload === 'string' ? payload : JSON.stringify(payload) });
    }
}

function installFakeWebSocket() {
    FakeWebSocket.instances = [];
    globalThis.WebSocket = FakeWebSocket;
}

function createSocket(overrides = {}) {
    return new GeoVaultSocket({
        url: 'ws://example.test/ws/test/',
        reconnectJitterRatio: 0,
        reconnectBaseDelayMs: 1000,
        reconnectMaxDelayMs: 8000,
        ...overrides,
    });
}

test('connects, dispatches handlers from a Set, and ignores pong as an event', (t) => {
    installFakeWebSocket();
    const socket = createSocket();
    const handler = mock.fn();
    socket.on('status_updated', handler);

    socket.connect();
    const ws = FakeWebSocket.instances[0];
    ws.open();

    assert.equal(socket.isConnected, true);
    ws.deliver({ type: 'pong', data: {} });
    ws.deliver({ type: 'status_updated', data: { progress: 40 } });

    assert.equal(handler.mock.callCount(), 1);
    assert.deepEqual(handler.mock.calls[0].arguments[0], { progress: 40 });
    socket.disconnect();
});

test('on() is idempotent for the same handler (Set, not array)', () => {
    installFakeWebSocket();
    const socket = createSocket();
    const handler = mock.fn();
    socket.on('page', handler);
    socket.on('page', handler);

    socket.connect();
    FakeWebSocket.instances[0].open();
    FakeWebSocket.instances[0].deliver({ type: 'page', data: { page: 1 } });

    assert.equal(handler.mock.callCount(), 1);
    socket.disconnect();
});

test('stale-socket onclose does not tear down a replacement socket or schedule reconnect', (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    installFakeWebSocket();
    const socket = createSocket();
    const disconnected = mock.fn();
    socket.on('disconnected', disconnected);

    socket.connect();
    const first = FakeWebSocket.instances[0];
    first.open();

    first.readyState = CLOSING;
    socket.connect();
    assert.equal(FakeWebSocket.instances.length, 2);

    const replacement = FakeWebSocket.instances[1];
    replacement.open();
    disconnected.mock.resetCalls();

    first.onclose?.({ target: first, code: 1006, reason: 'stale' });
    assert.equal(socket.isConnected, true);
    assert.equal(disconnected.mock.callCount(), 0);
    t.mock.timers.tick(10_000);
    assert.equal(FakeWebSocket.instances.length, 2);

    socket.disconnect();
});

test('disconnect cancels a pending reconnect', (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    installFakeWebSocket();
    const socket = createSocket();

    socket.connect();
    FakeWebSocket.instances[0].close(1006, 'drop');
    assert.equal(socket.reconnectAttempts, 1);

    socket.disconnect();
    t.mock.timers.tick(10_000);
    assert.equal(FakeWebSocket.instances.length, 1);
    assert.equal(socket.isConnected, false);
});

test('exponential backoff does not reset to 0 when visibility returns', (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    installFakeWebSocket();

    const listeners = new Map();
    globalThis.document = {
        visibilityState: 'hidden',
        addEventListener(type, handler) {
            listeners.set(type, handler);
        },
        removeEventListener(type) {
            listeners.delete(type);
        },
    };

    const socket = createSocket();
    socket.connect();
    FakeWebSocket.instances[0].close(1006, 'drop');
    assert.equal(socket.reconnectAttempts, 1);

    document.visibilityState = 'visible';
    listeners.get('visibilitychange')();
    assert.equal(socket.reconnectAttempts, 1, 'visibility must not zero the backoff counter');
    assert.equal(FakeWebSocket.instances.length, 2, 'visibility retries immediately without resetting attempts');

    FakeWebSocket.instances[1].close(1006, 'drop again');
    assert.equal(socket.reconnectAttempts, 2);

    socket.disconnect();
    delete globalThis.document;
});

test('reconnect delay applies equal jitter', (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    installFakeWebSocket();
    t.mock.method(Math, 'random', () => 0);
    const socket = createSocket({ reconnectJitterRatio: 0.5, reconnectBaseDelayMs: 1000 });
    socket.connect();
    FakeWebSocket.instances[0].close(1006, 'drop');
    t.mock.timers.tick(499);
    assert.equal(FakeWebSocket.instances.length, 1);
    t.mock.timers.tick(1);
    assert.equal(FakeWebSocket.instances.length, 2);
    socket.disconnect();
});

test('successful open resets backoff; terminal close codes do not reconnect', (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    installFakeWebSocket();
    const socket = createSocket({ terminalCloseCodes: [4004] });

    socket.connect();
    FakeWebSocket.instances[0].close(1006, 'drop');
    t.mock.timers.tick(1000);
    FakeWebSocket.instances[1].open();
    assert.equal(socket.reconnectAttempts, 0);
    assert.equal(socket.isConnected, true);

    FakeWebSocket.instances[1].close(4004, 'gone');
    t.mock.timers.tick(10_000);
    assert.equal(FakeWebSocket.instances.length, 2);
    socket.disconnect();
});
