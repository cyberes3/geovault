import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createSessionStartCache } from '../src/sessionStartCache.js';

test('isSessionWindowTrack recognizes session and current_session windows only', () => {
  const cache = createSessionStartCache();
  assert.equal(cache.isSessionWindowTrack({ settings: { recent_data_window: 'session' } }), true);
  assert.equal(cache.isSessionWindowTrack({ settings: { recent_data_window: 'current_session' } }), true);
  assert.equal(cache.isSessionWindowTrack({ settings: { recent_data_window: 'all' } }), false);
  assert.equal(cache.isSessionWindowTrack({}), false);
  assert.equal(cache.isSessionWindowTrack(null), false);
});

test('getKnownStartMs falls back to latestPointParams.starttimestamp when nothing cached', () => {
  const cache = createSessionStartCache();
  const track = { id: 1, latestPointParams: { starttimestamp: 1700000000 } };
  assert.equal(cache.getKnownStartMs(track), 1700000000000);
});

test('getKnownStartMs prefers the cached live value over latestPointParams', () => {
  const cache = createSessionStartCache();
  cache.setKnownStartMs(1, 1234567890000);
  const track = { id: 1, latestPointParams: { starttimestamp: 1700000000 } };
  assert.equal(cache.getKnownStartMs(track), 1234567890000);
});

test('setKnownStartMs with null clears the cached entry', () => {
  const cache = createSessionStartCache();
  cache.setKnownStartMs(1, 1234567890000);
  cache.setKnownStartMs(1, null);
  const track = { id: 1, latestPointParams: {} };
  assert.equal(cache.getKnownStartMs(track), null);
});

test('refreshFromTrackers only caches session-window tracks and clears prior entries', () => {
  const cache = createSessionStartCache();
  cache.setKnownStartMs(99, 5000);
  const tracks = [
    { id: 1, settings: { recent_data_window: 'session' }, latestPointParams: { starttimestamp: 1700000000 } },
    { id: 2, settings: { recent_data_window: 'all' }, latestPointParams: { starttimestamp: 1600000000 } },
    { id: 3, settings: { recent_data_window: 'current_session' }, latestPointParams: {} }
  ];
  cache.refreshFromTrackers(tracks);

  assert.equal(cache.getKnownStartMs({ id: 1, latestPointParams: {} }), 1700000000000);
  assert.equal(cache.getKnownStartMs({ id: 2, latestPointParams: {} }), null);
  assert.equal(cache.getKnownStartMs({ id: 3, latestPointParams: {} }), null);
  assert.equal(cache.getKnownStartMs({ id: 99, latestPointParams: {} }), null, 'stale entry from before refresh should be cleared');
});
