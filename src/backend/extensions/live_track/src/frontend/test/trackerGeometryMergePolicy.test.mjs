import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mergeTrackerGeometry } from '../src/trackerGeometryMergePolicy.ts';

test('merge preserves existing metadata when incoming omits fields', () => {
  const existing = {
    id: 't1',
    name: 'Existing',
    color: '#112233',
    geometry: { type: 'LineString', coordinates: [[1, 2, 10]] },
    point_params: [{ acc: 1 }],
    owner_email: 'owner@example.com',
  };
  const incoming = {
    id: 't1',
    name: '',
    geometry: { type: 'LineString', coordinates: [[3, 4, 20]] },
  };
  const merged = mergeTrackerGeometry(existing, incoming);
  assert.equal(merged.name, 'Existing');
  assert.equal(merged.color, '#112233');
  assert.equal(merged.owner_email, 'owner@example.com');
  assert.deepEqual(merged.geometry.coordinates, [[3, 4, 20]]);
  assert.deepEqual(merged.point_params, [{ acc: 1 }]);
});

test('merge keeps longer existing point_params when incoming list is shorter without geometry', () => {
  const existing = {
    id: 't1',
    name: 'Existing',
    geometry: { type: 'LineString', coordinates: [[1, 2]] },
    point_params: [{ acc: 1 }, { acc: 2 }],
  };
  const incoming = {
    id: 't1',
    name: 'Existing',
    point_params: [{ acc: 9 }],
  };
  const merged = mergeTrackerGeometry(existing, incoming);
  assert.equal(merged.point_params.length, 2);
});
