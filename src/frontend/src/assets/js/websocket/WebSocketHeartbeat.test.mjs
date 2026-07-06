import test from 'node:test';
import assert from 'node:assert/strict';
import { mock } from 'node:test';
import { WebSocketHeartbeat } from './WebSocketHeartbeat.js';

test('sends a ping on the configured interval', (t) => {
  t.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  const sendPing = mock.fn();
  const heartbeat = new WebSocketHeartbeat({ sendPing, onTimeout: () => {}, intervalMs: 1000, timeoutMs: 500 });

  heartbeat.start();
  assert.equal(sendPing.mock.callCount(), 0);

  t.mock.timers.tick(1000);
  assert.equal(sendPing.mock.callCount(), 1);

  t.mock.timers.tick(1000);
  assert.equal(sendPing.mock.callCount(), 2);
});

test('does not time out when a pong arrives before the timeout', (t) => {
  t.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  const onTimeout = mock.fn();
  const heartbeat = new WebSocketHeartbeat({ sendPing: () => {}, onTimeout, intervalMs: 1000, timeoutMs: 500 });

  heartbeat.start();
  t.mock.timers.tick(1000); // triggers the first ping and arms the timeout
  heartbeat.onPong();
  t.mock.timers.tick(500); // timeout would have fired here if not cancelled

  assert.equal(onTimeout.mock.callCount(), 0);
});

test('calls onTimeout when no pong arrives before the deadline', (t) => {
  t.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  const onTimeout = mock.fn();
  const heartbeat = new WebSocketHeartbeat({ sendPing: () => {}, onTimeout, intervalMs: 1000, timeoutMs: 500 });

  heartbeat.start();
  t.mock.timers.tick(1000); // ping sent, timeout armed
  t.mock.timers.tick(500); // no pong received in time

  assert.equal(onTimeout.mock.callCount(), 1);
});

test('stop() cancels both the ping interval and any pending timeout', (t) => {
  t.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  const sendPing = mock.fn();
  const onTimeout = mock.fn();
  const heartbeat = new WebSocketHeartbeat({ sendPing, onTimeout, intervalMs: 1000, timeoutMs: 500 });

  heartbeat.start();
  t.mock.timers.tick(1000); // ping sent, timeout armed
  heartbeat.stop();
  t.mock.timers.tick(10000);

  assert.equal(sendPing.mock.callCount(), 1);
  assert.equal(onTimeout.mock.callCount(), 0);
});

test('start() is idempotent and does not stack multiple intervals', (t) => {
  t.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  const sendPing = mock.fn();
  const heartbeat = new WebSocketHeartbeat({ sendPing, onTimeout: () => {}, intervalMs: 1000, timeoutMs: 500 });

  heartbeat.start();
  heartbeat.start();
  t.mock.timers.tick(1000);

  assert.equal(sendPing.mock.callCount(), 1);
});
