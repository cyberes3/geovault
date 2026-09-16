import test from 'node:test';
import assert from 'node:assert/strict';

globalThis.window = { location: { origin: 'http://localhost' } };

const { createFakeMap } = await import('./test/FakeMap.mjs');
const { FeatureSource } = await import('./common/FeatureSource.ts');
const { FEATURE_LAYER_STACK } = await import('./mapLayers.ts');
const { pickFeaturesAtPoint } = await import('./pickFeatures.ts');

function seedLayers(map) {
    for (const id of FEATURE_LAYER_STACK) {
        map.addLayer({ id });
    }
}

test('pickFeaturesAtPoint skips label points, resolves replacements, and dedupes', () => {
    const map = createFakeMap();
    seedLayers(map);
    const source = new FeatureSource();
    source.upsert([{
        type: 'Feature',
        geometry: { type: 'LineString', coordinates: [[0, 0], [1, 1]] },
        properties: { database_id: 'line-1', name: 'Trail' },
    }]);
    map.queryFeatures = [
        { properties: { _isLabelPoint: true, database_id: 'label' }, geometry: { type: 'Point', coordinates: [0, 0] } },
        {
            properties: { _isSmallFeatureReplacement: true, _originalFeatureId: 'line-1', database_id: 'line-1_small_replacement' },
            geometry: { type: 'Point', coordinates: [0.5, 0.5] },
        },
        {
            properties: { _isSmallFeatureReplacement: true, _originalFeatureId: 'line-1', database_id: 'line-1_small_replacement' },
            geometry: { type: 'Point', coordinates: [0.5, 0.5] },
        },
    ];
    const hits = pickFeaturesAtPoint(map, { x: 10, y: 10 }, 15, source);
    assert.equal(hits.length, 1);
    assert.equal(String(hits[0].properties.database_id), 'line-1');
    assert.equal(hits[0].properties._isSmallFeatureReplacement, undefined);
});
