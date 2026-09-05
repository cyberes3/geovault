import test from 'node:test';
import assert from 'node:assert/strict';
import {
  isRollingRecentDataWindow,
  pruneCoordinatesForRecentDataWindow,
  shouldClearGeometryForSessionTransition,
  shouldReloadGeometryForSessionTransition,
} from '../src/recentDataWindowGeometryPolicy.js';

test('isRollingRecentDataWindow recognizes all rolling filter options', () => {
  for (const option of ['1min', '1h', '1d', '1w', '1m']) {
    assert.equal(isRollingRecentDataWindow(option), true);
  }
  assert.equal(isRollingRecentDataWindow('session'), false);
  assert.equal(isRollingRecentDataWindow('current_session'), false);
  assert.equal(isRollingRecentDataWindow(''), false);
});

test('pruneCoordinatesForRecentDataWindow removes stale rolling-window coordinates', () => {
  const nowMs = 1_700_000_000_000;
  const coordinates = [
    [-100.0, 40.0, nowMs - 90_000],
    [-100.1, 40.1, nowMs - 30_000],
    [-100.2, 40.2, nowMs],
  ];

  assert.deepEqual(
    pruneCoordinatesForRecentDataWindow(coordinates, '1min', nowMs),
    coordinates.slice(1),
  );
});

test('pruneCoordinatesForRecentDataWindow keeps time-max point when every coordinate is stale', () => {
  const nowMs = 1_700_000_000_000;
  const coordinates = [
    [-100.0, 40.0, nowMs - 90_000],
    [-100.1, 40.1, nowMs - 120_000],
  ];

  assert.deepEqual(
    pruneCoordinatesForRecentDataWindow(coordinates, '1min', nowMs),
    [coordinates[0]],
  );
});

test('pruneCoordinatesForRecentDataWindow preserves coordinates without timestamps', () => {
  const nowMs = 1_700_000_000_000;
  const coordinates = [
    [-100.0, 40.0],
    [-100.1, 40.1, nowMs - 90_000],
  ];

  assert.deepEqual(
    pruneCoordinatesForRecentDataWindow(coordinates, '1min', nowMs),
    [coordinates[0]],
  );
});

test('session transition policy reloads last-session geometry but clears current-session geometry', () => {
  assert.equal(shouldReloadGeometryForSessionTransition('session', 1_000, 2_000), true);
  assert.equal(shouldClearGeometryForSessionTransition('session', 1_000, 2_000), false);

  assert.equal(shouldReloadGeometryForSessionTransition('current_session', 1_000, 2_000), false);
  assert.equal(shouldClearGeometryForSessionTransition('current_session', 1_000, 2_000), true);
});

test('session transition policy ignores missing or non-advancing session starts', () => {
  assert.equal(shouldReloadGeometryForSessionTransition('session', 2_000, 2_000), false);
  assert.equal(shouldReloadGeometryForSessionTransition('session', 2_000, 1_000), false);
  assert.equal(shouldReloadGeometryForSessionTransition('session', null, 2_000), false);
  assert.equal(shouldClearGeometryForSessionTransition('current_session', 2_000, null), false);
});
