import test from 'node:test';
import assert from 'node:assert/strict';
import {
  isShareNotAvailableStatus,
  shareDataUrlForInfo,
  shareInfoUrl
} from '../src/shareDiscoveryUrls.js';

test('shareInfoUrl uses the neutral live-track discovery endpoint', () => {
  assert.equal(
    shareInfoUrl('f8a918ab-7f53-4ef3-be11-a957c40ebd02'),
    '/api/extensions/live-track/share/f8a918ab-7f53-4ef3-be11-a957c40ebd02/info/'
  );
});

test('shareInfoUrl encodes share IDs before building the URL', () => {
  assert.equal(
    shareInfoUrl('id with spaces'),
    '/api/extensions/live-track/share/id%20with%20spaces/info/'
  );
});

test('shareDataUrlForInfo dispatches internal shares to the internal data endpoint', () => {
  assert.equal(
    shareDataUrlForInfo('f8a918ab-7f53-4ef3-be11-a957c40ebd02', { share_access: 'internal' }),
    '/api/extensions/live-track/internal/share/f8a918ab-7f53-4ef3-be11-a957c40ebd02/'
  );
});

test('shareDataUrlForInfo dispatches world shares to the world data endpoint', () => {
  assert.equal(
    shareDataUrlForInfo('f8a918ab-7f53-4ef3-be11-a957c40ebd02', { share_access: 'world' }),
    '/api/extensions/live-track/world/share/f8a918ab-7f53-4ef3-be11-a957c40ebd02/'
  );
});

test('shareDataUrlForInfo defaults unknown discovery metadata to world data endpoint', () => {
  assert.equal(
    shareDataUrlForInfo('f8a918ab-7f53-4ef3-be11-a957c40ebd02', {}),
    '/api/extensions/live-track/world/share/f8a918ab-7f53-4ef3-be11-a957c40ebd02/'
  );
});

test('isShareNotAvailableStatus identifies missing or unauthorized share responses', () => {
  assert.equal(isShareNotAvailableStatus(401), true);
  assert.equal(isShareNotAvailableStatus(403), true);
  assert.equal(isShareNotAvailableStatus(404), true);
  assert.equal(isShareNotAvailableStatus(500), false);
});
