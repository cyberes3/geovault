import test from 'node:test';
import assert from 'node:assert/strict';
import { PublicTrackSession } from '../src/liveTrackSession.ts';

function stubShare(info) {
  return {
    status: 'idle',
    info: null,
    error: null,
    shareId: null,
    resetForShareIdChange(shareId = null) {
      this.status = 'idle';
      this.info = null;
      this.error = null;
      this.shareId = shareId;
    },
    async ensureInfo() {
      this.info = info;
      this.status = info ? 'ready' : 'invalid';
      this.error = info ? null : 'Invalid share link';
      return !!info;
    },
  };
}

test('PublicTrackSession wraps PublicShareSession and loads track data', async () => {
  const previousFetch = globalThis.fetch;
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    json: async () => ({ id: 'track-1', name: 'Shared tracker' }),
  });
  try {
    const session = new PublicTrackSession(stubShare({
      share_type: 'live_track',
      share_access: 'world',
      track_name: 'Shared tracker',
    }));
    const ok = await session.ensureSource('share-1');
    assert.equal(ok, true);
    assert.equal(session.status, 'ready');
    assert.equal(session.sourceMode, 'world');
    assert.equal(session.dataUrl, '/api/shares/share-1/track/');
    assert.equal(session.payload.name, 'Shared tracker');
    assert.equal(session.share.info.share_type, 'live_track');
  } finally {
    globalThis.fetch = previousFetch;
  }
});

test('PublicTrackSession surfaces an invalid share from the wrapped session', async () => {
  const session = new PublicTrackSession(stubShare(null));
  const ok = await session.ensureSource('missing');
  assert.equal(ok, false);
  assert.equal(session.status, 'invalid');
  assert.equal(session.error, 'Invalid share link');
  assert.equal(session.payload, null);
});
