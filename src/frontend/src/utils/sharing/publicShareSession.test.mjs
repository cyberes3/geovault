import test from 'node:test';
import assert from 'node:assert/strict';
import { PublicShareSession } from './publicShareSession.ts';

test('PublicShareSession maps ready info and resets on share id change', () => {
    const session = new PublicShareSession();
    session.resetForShareIdChange('share-1');
    session.status = 'ready';
    session.info = {
        share_type: 'tag',
        tag: 'trail',
        created_at: '2026-01-01T00:00:00Z',
        include_tags: true,
        allow_downloads: false,
    };

    assert.equal(session.allowDownloads, false);
    assert.equal(session.includeTags, true);
    const mapped = session.toMapShareInfo();
    assert.equal(mapped.share_type, 'tag');
    assert.equal(mapped.tag, 'trail');
    assert.equal(mapped.share_id, 'share-1');

    session.resetForShareIdChange('share-2');
    assert.equal(session.status, 'idle');
    assert.equal(session.info, null);
    assert.equal(session.toMapShareInfo(), null);
});
