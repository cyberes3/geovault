import test from 'node:test';
import assert from 'node:assert/strict';
import { FEATURE_LAYER_STACK, sourceWantsAtmosphere } from './mapLayers.ts';

test('feature layer stack is polygons through point-icons', () => {
    assert.deepEqual([...FEATURE_LAYER_STACK], [
        'polygons',
        'polygon-outlines',
        'lines',
        'points',
        'replacement-points',
        'point-icons',
    ]);
});

test('sourceWantsAtmosphere uses ids and map_id, not display names', () => {
    assert.equal(sourceWantsAtmosphere({ id: 'global-imagery' }), true);
    assert.equal(sourceWantsAtmosphere({ id: 'google-satellite-hybrid' }), true);
    assert.equal(sourceWantsAtmosphere({ id: 'maptiler', client_config: { map_id: 'satellite' } }), true);
    assert.equal(sourceWantsAtmosphere({ id: 'maptiler', client_config: { map_id: 'hybrid' } }), true);
    assert.equal(sourceWantsAtmosphere({ id: 'osm', name: 'Satellite preview' }), false);
});
