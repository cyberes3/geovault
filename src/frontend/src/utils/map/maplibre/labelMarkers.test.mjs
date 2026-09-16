import test from 'node:test';
import assert from 'node:assert/strict';
import { createFakeMap } from '../test/FakeMap.mjs';
import { MAX_VISIBLE_LABELS } from '../mapLayers.ts';
import { layoutVisibleLabels } from './labelMarkers.ts';

function namedPoint(id, lon, lat, extra = {}) {
    return {
        type: 'Feature',
        properties: { database_id: id, name: `Label ${id}`, ...extra },
        geometry: { type: 'Point', coordinates: [lon, lat] },
    };
}

test('layoutVisibleLabels uses projected boxes and the 200 cap', () => {
    const map = createFakeMap({
        project: ([lon, lat]) => ({ x: lon, y: lat }),
    });
    const overlapping = [
        namedPoint('a', 10, 10),
        namedPoint('b', 10.2, 10.1),
    ];
    const visibleOverlap = layoutVisibleLabels(map, overlapping);
    assert.equal(visibleOverlap.length, 1);

    const many = Array.from({ length: 250 }, (_, index) => namedPoint(String(index), index * 200, 0));
    const visible = layoutVisibleLabels(map, many);
    assert.equal(visible.length, MAX_VISIBLE_LABELS);
});
