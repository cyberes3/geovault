import test from 'node:test';
import assert from 'node:assert/strict';
import { buildPlacePayload } from '../src/utils/placePayload.js';

const sampleFeature = {
  type: 'Feature',
  geometry: { type: 'Point', coordinates: [-12.0, 21.0] },
  properties: {
    database_id: 1,
    name: 'Camp Site',
    description: 'Near the lake',
    tags: ['outdoor'],
    system_tags: ['gv:place'],
    geojson_hash: 'abc123',
    created_at: '2024-01-01T00:00:00Z',
    address: '123 Main St',
  },
};

test('buildPlacePayload strips response-only properties', () => {
  const payload = buildPlacePayload(sampleFeature);
  assert.deepEqual(payload, {
    type: 'Feature',
    geometry: { type: 'Point', coordinates: [-12.0, 21.0] },
    properties: {
      name: 'Camp Site',
      description: 'Near the lake',
      address: '123 Main St',
    },
  });
});

test('buildPlacePayload applies description override', () => {
  const payload = buildPlacePayload(sampleFeature, { description: 'Updated text' });
  assert.equal(payload.properties.description, 'Updated text');
  assert.equal(payload.properties.name, 'Camp Site');
});

test('buildPlacePayload normalizes empty description to null', () => {
  const payload = buildPlacePayload(sampleFeature, { description: '   ' });
  assert.equal(payload.properties.description, null);
});

test('buildPlacePayload matches description-only update contract', () => {
  const payload = buildPlacePayload(sampleFeature, { description: 'Updated description' });
  assert.deepEqual(Object.keys(payload.properties).sort(), ['address', 'description', 'name']);
  assert.notEqual(payload.properties.database_id, 1);
  assert.notEqual(payload.properties.tags, ['outdoor']);
});
