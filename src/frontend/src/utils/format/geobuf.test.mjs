import test from 'node:test';
import assert from 'node:assert/strict';
import { EMPTY_FEATURE_COLLECTION } from './geobuf.ts';

test('empty FeatureCollection is a constant valid payload', () => {
    assert.equal(EMPTY_FEATURE_COLLECTION.type, 'FeatureCollection');
    assert.deepEqual(EMPTY_FEATURE_COLLECTION.features, []);
});
