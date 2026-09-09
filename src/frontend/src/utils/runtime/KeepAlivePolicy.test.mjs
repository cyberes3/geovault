import test from 'node:test';
import assert from 'node:assert/strict';
import { CORE_KEEP_ALIVE_NAMES, buildKeepAliveInclude } from './KeepAlivePolicy.ts';

test('keep-alive include list is core names plus extension keys', () => {
    assert.deepEqual(CORE_KEEP_ALIVE_NAMES, ['Map', 'TagsPage']);
    assert.deepEqual(
        buildKeepAliveInclude(['ExtensionBoundary_live-track_index']),
        ['Map', 'TagsPage', 'ExtensionBoundary_live-track_index'],
    );
});
