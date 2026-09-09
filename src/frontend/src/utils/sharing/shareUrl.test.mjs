import test from 'node:test';
import assert from 'node:assert/strict';
import { mapSpaUrl, remapPathnameToHash, trackSpaUrl } from './shareUrl.ts';

const SHARE_ID = 'f8a918ab-7f53-4ef3-be11-a957c40ebd02';

test('remapPathnameToHash maps map and track social paths once', () => {
    assert.equal(remapPathnameToHash(`/share/map/${SHARE_ID}/`), mapSpaUrl(SHARE_ID));
    assert.equal(remapPathnameToHash(`/share/track/${SHARE_ID}/`), trackSpaUrl(SHARE_ID));
    assert.equal(remapPathnameToHash(`/share/map/${SHARE_ID}/`, '#/already'), null);
    assert.equal(remapPathnameToHash('/not-a-share/'), null);
});
