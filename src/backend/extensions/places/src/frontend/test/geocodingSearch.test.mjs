import test from 'node:test';
import assert from 'node:assert/strict';
import {
  getGeocodingResultCoordinates,
  getGeocodingResultLabel,
  parseGeocodingFeatures,
  searchGeocoding,
} from '../../../../../../frontend/src/utils/geocodingSearch.js';

const TEST_LON = -12.5;
const TEST_LAT = 21.25;

test('parseGeocodingFeatures reads nested features array', () => {
  const payload = {
    data: {
      features: [{ text: 'Place Alpha', coordinates: [TEST_LON, TEST_LAT] }],
    },
  };
  assert.equal(parseGeocodingFeatures(payload).length, 1);
});

test('parseGeocodingFeatures supports legacy array payload', () => {
  const payload = {
    data: [{ place_name: 'Legacy Place', coordinates: [TEST_LON, TEST_LAT] }],
  };
  assert.equal(parseGeocodingFeatures(payload).length, 1);
});

test('getGeocodingResultCoordinates reads coordinates and center', () => {
  assert.deepEqual(
    getGeocodingResultCoordinates({ coordinates: [TEST_LON, TEST_LAT] }),
    { lon: TEST_LON, lat: TEST_LAT },
  );
  assert.deepEqual(
    getGeocodingResultCoordinates({ center: [-11.0, 22.0] }),
    { lon: -11, lat: 22 },
  );
});

test('getGeocodingResultLabel prefers text', () => {
  assert.equal(
    getGeocodingResultLabel({ text: 'Short label', place_name: 'Long place name' }),
    'Short label',
  );
});

test('searchGeocoding returns parsed features', async () => {
  const features = [{ text: 'Place Beta', coordinates: [-11.5, 22.5] }];
  const result = await searchGeocoding('Place Beta', {
    fetchFn: async () => ({
      ok: true,
      async json() {
        return { data: { features } };
      },
    }),
  });
  assert.equal(result.ok, true);
  assert.deepEqual(result.features, features);
});

test('searchGeocoding surfaces API errors', async () => {
  const result = await searchGeocoding('No match query', {
    fetchFn: async () => ({
      ok: false,
      async json() {
        return { error: 'No results' };
      },
    }),
  });
  assert.equal(result.ok, false);
  assert.equal(result.error, 'No results');
});
