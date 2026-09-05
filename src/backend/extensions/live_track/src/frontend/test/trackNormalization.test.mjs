import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizeTrackForMemory } from '../src/trackNormalization.js';

test('derives last_position and last_timestamp_ms from the last geometry coordinate', () => {
  const track = {
    id: 1,
    geometry: { type: 'LineString', coordinates: [[1, 2, 1700000000], [3, 4, 1700000010]] },
    updated_at: 1700000020
  };
  const normalized = normalizeTrackForMemory(track);
  assert.deepEqual(normalized.last_position, { lon: 3, lat: 4 });
  assert.equal(normalized.last_timestamp_ms, 1700000010000);
  assert.equal(normalized.updated_at_ms, 1700000020000);
});

test('falls back to last_point when geometry has no coordinates', () => {
  const track = {
    id: 1,
    geometry: { type: 'LineString', coordinates: [] },
    last_point: [5, 6, 1700000030]
  };
  const normalized = normalizeTrackForMemory(track);
  assert.deepEqual(normalized.last_position, { lon: 5, lat: 6 });
  assert.equal(normalized.last_timestamp_ms, 1700000030000);
});

test('defaults geometry to an empty LineString when absent', () => {
  const normalized = normalizeTrackForMemory({ id: 1 });
  assert.deepEqual(normalized.geometry, { type: 'LineString', coordinates: [] });
  assert.equal(normalized.last_position, null);
  assert.equal(normalized.last_timestamp_ms, null);
});

test('keeps point_params and last_point and prefers newer last_point over older geometry', () => {
  const track = {
    id: 1,
    geometry: { type: 'LineString', coordinates: [[1, 2, 1_700_000_000_000]] },
    point_params: [{ speed: 1 }],
    last_point: [9, 9, 1_700_000_100_000]
  };
  const normalized = normalizeTrackForMemory(track);
  assert.deepEqual(normalized.last_position, { lon: 9, lat: 9 });
  assert.equal(normalized.last_timestamp_ms, 1_700_000_100_000);
  assert.deepEqual(normalized.point_params, [{ speed: 1 }]);
  assert.deepEqual(normalized.last_point, [9, 9, 1_700_000_100_000]);
  assert.deepEqual(normalized.latestPointParams, { speed: 1 });
});

test('latestPointParams is an empty object when point_params is absent or empty', () => {
  assert.deepEqual(normalizeTrackForMemory({ id: 1 }).latestPointParams, {});
  assert.deepEqual(normalizeTrackForMemory({ id: 1, point_params: [] }).latestPointParams, {});
});
