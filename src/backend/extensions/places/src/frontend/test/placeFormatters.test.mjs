import test from 'node:test';
import assert from 'node:assert/strict';
import { filterPlaces } from '../src/utils/placeFormatters.js';

const samplePlaces = [
  {
    type: 'Feature',
    geometry: { type: 'Point', coordinates: [-12.0, 21.0] },
    properties: { database_id: 1, name: 'Camp Site', description: 'Near the lake' },
  },
  {
    type: 'Feature',
    geometry: { type: 'Point', coordinates: [-11.0, 22.0] },
    properties: { database_id: 2, name: 'Trailhead', description: 'Parking lot' },
  },
];

test('filterPlaces matches name and description', () => {
  assert.equal(filterPlaces(samplePlaces, 'lake').length, 1);
  assert.equal(filterPlaces(samplePlaces, 'trail').length, 1);
  assert.equal(filterPlaces(samplePlaces, 'missing').length, 0);
});

test('filterPlaces returns all places for empty query', () => {
  assert.equal(filterPlaces(samplePlaces, '').length, 2);
});
