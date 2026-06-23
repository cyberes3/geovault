import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { isReleaseCommitNewer } from './gitea.js';

describe('isReleaseCommitNewer', () => {
  it('returns false when forward has 0 commits (identical or local is ahead)', () => {
    assert.equal(isReleaseCommitNewer({ totalCommits: 0 }), false);
  });

  it('returns true when forward has commits and no reverse compare is provided', () => {
    assert.equal(isReleaseCommitNewer({ totalCommits: 3 }), true);
  });

  it('returns true when release is strictly ahead (local behind, no unique local commits)', () => {
    assert.equal(isReleaseCommitNewer({ totalCommits: 3 }, { totalCommits: 0 }), true);
  });

  it('returns false when local is strictly ahead (release behind, no unique release commits)', () => {
    assert.equal(isReleaseCommitNewer({ totalCommits: 0 }, { totalCommits: 5 }), false);
  });

  it('returns false when diverged and local has equal unique commits as release', () => {
    assert.equal(isReleaseCommitNewer({ totalCommits: 1 }, { totalCommits: 1 }), false);
  });

  it('returns false when diverged and local is way ahead of release', () => {
    assert.equal(isReleaseCommitNewer({ totalCommits: 1 }, { totalCommits: 84 }), false);
  });

  it('returns true when diverged and release has more unique commits than local', () => {
    assert.equal(isReleaseCommitNewer({ totalCommits: 5 }, { totalCommits: 2 }), true);
  });

  it('returns false when forward totalCommits is null or missing', () => {
    assert.equal(isReleaseCommitNewer({ totalCommits: null }), false);
    assert.equal(isReleaseCommitNewer({}), false);
    assert.equal(isReleaseCommitNewer(null), false);
  });
});
