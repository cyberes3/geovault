import test from 'node:test';
import assert from 'node:assert/strict';
import { convertMapLibreFeature } from './featureConversion.ts';

test('string properties parse, objects stay, bad JSON uses fallback, database_id is copied', () => {
    const converted = convertMapLibreFeature({
        geometry: { type: 'Point', coordinates: [1, 2] },
        properties: {
            database_id: 'abc',
            tags: '["trail"]',
            system_tags: { kept: true },
            _elevations: 'not-json',
            _coordinateProperties: '{"times":[1]}',
            coordinateProperties: [1, 2],
        },
    });
    assert.equal(converted.database_id, 'abc');
    assert.deepEqual(converted.properties.tags, ['trail']);
    assert.deepEqual(converted.properties.system_tags, { kept: true });
    assert.equal(converted.properties._elevations, null);
    assert.deepEqual(converted.properties._coordinateProperties, { times: [1] });
    assert.deepEqual(converted.properties.coordinateProperties, [1, 2]);
});
