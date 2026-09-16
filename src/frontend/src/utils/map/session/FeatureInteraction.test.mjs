import test from 'node:test';
import assert from 'node:assert/strict';
import { createFakeMap } from '../test/FakeMap.mjs';
import { FeatureInteraction } from './FeatureInteraction.ts';

test('FeatureInteraction writes feature-state ids for hover and select', () => {
    const map = createFakeMap();
    const interaction = new FeatureInteraction();
    const feature = {
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [1, 2] },
        properties: { database_id: 'feat-1' },
    };
    interaction.hover(map, 'feat-1');
    interaction.select(map, feature);
    assert.deepEqual(map.getFeatureState({ source: 'geojson-data', id: 'feat-1' }), {
        hovered: true,
        selected: true,
    });
    interaction.clear(map);
    assert.deepEqual(map.getFeatureState({ source: 'geojson-data', id: 'feat-1' }), {});
});
