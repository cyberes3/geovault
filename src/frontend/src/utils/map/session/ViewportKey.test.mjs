import test from 'node:test';
import assert from 'node:assert/strict';
import { ViewportKey } from './ViewportKey.ts';
import { FeatureViewportCache } from './FeatureViewportCache.ts';
import { MapFilterState } from './MapFilterState.ts';

test('ViewportKey includes matchMode and omits zoom', () => {
    const andKey = ViewportKey.from({
        bbox: [1, 2, 3, 4],
        matchMode: 'AND',
        tags: ['b', 'a'],
        collectionId: 'c1',
    });
    const orKey = ViewportKey.from({
        bbox: [1, 2, 3, 4],
        matchMode: 'OR',
        tags: ['a', 'b'],
        collectionId: 'c1',
    });
    assert.notEqual(andKey, orKey);
    assert.match(andKey, /AND/);
    assert.doesNotMatch(andKey, /zoom/i);
});

test('FeatureViewportCache is an LRU with a hard cap', () => {
    const cache = new FeatureViewportCache(2);
    cache.set('a', []);
    cache.set('b', []);
    cache.set('c', []);
    assert.equal(cache.has('a'), false);
    assert.equal(cache.has('b'), true);
    cache.get('b');
    cache.set('d', []);
    assert.equal(cache.has('c'), false);
    assert.equal(cache.has('b'), true);
});

test('MapFilterState derives a typed load context without share_ concat', () => {
    const filters = new MapFilterState();
    filters.applyRoute({ path: '/map', query: { tag: ['trail', 'peak'], match_mode: 'OR' } });
    const context = filters.getLoadContext(null);
    assert.equal(context.kind, 'tag');
    assert.deepEqual(context.tags, ['trail', 'peak']);
    assert.equal(context.matchMode, 'OR');
    assert.equal(context.replaceSource, true);
});

test('MapFilterState requires share info instead of a share_unknown state', () => {
    const filters = new MapFilterState();
    filters.applyRoute({ path: '/mapshare', query: { id: 'abc' } });
    assert.throws(() => filters.getLoadContext(null), /share info/);
});
