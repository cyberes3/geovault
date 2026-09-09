import test from 'node:test';
import assert from 'node:assert/strict';
import { FeatureSource } from './FeatureSource.ts';
import { HiddenFeatureSet } from '../session/HiddenFeatureSet.ts';

function point(id, lon = 1, lat = 2) {
    return {
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [lon, lat] },
        properties: { database_id: id, name: `f-${id}` },
    };
}

test('FeatureSource upsert replaces an existing id instead of keeping the stale geometry', () => {
    const source = new FeatureSource();
    source.upsert([point(1, 10, 20)]);
    source.upsert([point(1, 30, 40)]);
    assert.deepEqual(source.get('1')?.geometry.coordinates, [30, 40]);
    assert.equal(source.size(), 1);
});

test('FeatureSource.remove cascades label and replacement synthetics', () => {
    const source = new FeatureSource();
    source.upsert([point(7)]);
    source.setSynthetic('7:label', {
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [1, 2] },
        properties: { database_id: '7-label', _isLabelPoint: true, _originalFeatureId: 7 },
    });
    source.remove('7');
    const collection = source.buildRenderCollection();
    assert.equal(collection.features.length, 0);
    assert.equal(source.size(), 0);
});

test('HiddenFeatureSet is applied at upsert and in the render collection', () => {
    const source = new FeatureSource();
    const hidden = new HiddenFeatureSet();
    hidden.add(2);
    source.upsert([point(1), point(2), point(3)], hidden);
    assert.equal(source.has('2'), false);
    assert.equal(source.size(), 2);
    hidden.add(3);
    source.removeHidden(hidden);
    const collection = source.buildRenderCollection(hidden);
    assert.deepEqual(collection.features.map((f) => String(f.properties.database_id)), ['1']);
});
