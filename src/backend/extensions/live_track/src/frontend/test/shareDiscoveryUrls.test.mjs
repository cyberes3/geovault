import test from 'node:test';
import assert from 'node:assert/strict';
import {
  isShareNotAvailableStatus,
  shareDataUrlForInfo,
  shareInfoUrl
} from '../src/shareDiscoveryUrls.js';

test('shareInfoUrl uses the unified shares info endpoint', () => {
  assert.equal(
    shareInfoUrl('f8a918ab-7f53-4ef3-be11-a957c40ebd02'),
    '/api/shares/f8a918ab-7f53-4ef3-be11-a957c40ebd02/info/'
  );
});

test('shareInfoUrl encodes share IDs before building the URL', () => {
  assert.equal(
    shareInfoUrl('id with spaces'),
    '/api/shares/id%20with%20spaces/info/'
  );
});

test('shareDataUrlForInfo uses the unified track endpoint for internal shares', () => {
  assert.equal(
    shareDataUrlForInfo('f8a918ab-7f53-4ef3-be11-a957c40ebd02', { share_access: 'internal' }),
    '/api/shares/f8a918ab-7f53-4ef3-be11-a957c40ebd02/track/'
  );
});

test('shareDataUrlForInfo uses the unified track endpoint for world shares', () => {
  assert.equal(
    shareDataUrlForInfo('f8a918ab-7f53-4ef3-be11-a957c40ebd02', { share_access: 'world' }),
    '/api/shares/f8a918ab-7f53-4ef3-be11-a957c40ebd02/track/'
  );
});

test('shareDataUrlForInfo defaults unknown discovery metadata to the track endpoint', () => {
  assert.equal(
    shareDataUrlForInfo('f8a918ab-7f53-4ef3-be11-a957c40ebd02', {}),
    '/api/shares/f8a918ab-7f53-4ef3-be11-a957c40ebd02/track/'
  );
});

test('isShareNotAvailableStatus identifies missing or unauthorized share responses', () => {
  assert.equal(isShareNotAvailableStatus(401), true);
  assert.equal(isShareNotAvailableStatus(403), true);
  assert.equal(isShareNotAvailableStatus(404), true);
  assert.equal(isShareNotAvailableStatus(500), false);
});
