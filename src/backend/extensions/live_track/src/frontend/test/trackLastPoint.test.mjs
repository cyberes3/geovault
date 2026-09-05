import { test } from 'node:test';
import assert from 'node:assert/strict';
import { latestCoordByTime, resolveTrackLastCoordinate } from '../src/trackLastPoint.js';

test('latestCoordByTime prefers a newer timestamp that is not last in the array', () => {
  const coords = [
    [1, 2, 1_700_000_100_000],
    [9, 9, 1_700_000_000_000],
  ];
  assert.deepEqual(latestCoordByTime(coords), [1, 2, 1_700_000_100_000]);
});

test('latestCoordByTime uses later array order when timestamps are missing', () => {
  assert.deepEqual(latestCoordByTime([[1, 2], [3, 4]]), [3, 4]);
});

test('resolveTrackLastCoordinate prefers newer last_point over older geometry', () => {
  const last = resolveTrackLastCoordinate({
    id: 1,
    geometry: { type: 'LineString', coordinates: [[1, 2, 1_700_000_000_000]] },
    last_point: [9, 9, 1_700_000_100_000],
  });
  assert.deepEqual(last, [9, 9, 1_700_000_100_000]);
});

test('resolveTrackLastCoordinate uses last_point when geometry is empty', () => {
  const last = resolveTrackLastCoordinate({
    id: 1,
    geometry: { type: 'LineString', coordinates: [] },
    last_point: [5, 6, 1_700_000_030_000],
  });
  assert.deepEqual(last, [5, 6, 1_700_000_030_000]);
});

test('resolveTrackLastCoordinate prefers dated geometry over undated last_point', () => {
  const last = resolveTrackLastCoordinate({
    id: 1,
    geometry: { type: 'LineString', coordinates: [[1, 2, 1_700_000_000_000]] },
    last_point: [9, 9],
  });
  assert.deepEqual(last, [1, 2, 1_700_000_000_000]);
});

test('resolveTrackLastCoordinate uses last_position when it is newer than geometry', () => {
  const last = resolveTrackLastCoordinate({
    id: 1,
    geometry: { type: 'LineString', coordinates: [[1, 2, 1_700_000_000_000]] },
    last_position: { lon: 7, lat: 8 },
    last_timestamp_ms: 1_700_000_200_000,
  });
  assert.deepEqual(last, [7, 8, 1_700_000_200_000]);
});
