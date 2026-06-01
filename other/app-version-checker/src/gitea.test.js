import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { isReleaseCommitNewer } from './gitea.js';

describe('isReleaseCommitNewer', () => {
  it('returns true when release is ahead of local', () => {
    assert.equal(isReleaseCommitNewer({ status: 'ahead', totalCommits: 3 }), true);
  });

  it('returns false when local matches or is ahead of release', () => {
    assert.equal(isReleaseCommitNewer({ status: 'identical', totalCommits: 0 }), false);
    assert.equal(isReleaseCommitNewer({ status: 'behind', totalCommits: 0 }), false);
  });

  it('does not treat diverged forward compare alone as newer (rewrite false positive)', () => {
    assert.equal(
      isReleaseCommitNewer({ status: 'diverged', totalCommits: 1 }),
      false
    );
  });

  it('returns false when diverged but local is ahead on reverse compare', () => {
    const forward = { status: 'diverged', totalCommits: 1 };
    const reverse = { status: 'ahead', totalCommits: 84 };
    assert.equal(isReleaseCommitNewer(forward, reverse), false);
  });

  it('returns true when diverged and release is ahead on reverse compare', () => {
    const forward = { status: 'diverged', totalCommits: 2 };
    const reverse = { status: 'behind', totalCommits: 0 };
    assert.equal(isReleaseCommitNewer(forward, reverse), true);
  });

  it('when both diverged, prefers local when it has more unique commits', () => {
    const forward = { status: 'diverged', totalCommits: 1 };
    const reverse = { status: 'diverged', totalCommits: 84 };
    assert.equal(isReleaseCommitNewer(forward, reverse), false);
  });

  it('when both diverged, reports newer if release has more unique commits', () => {
    const forward = { status: 'diverged', totalCommits: 5 };
    const reverse = { status: 'diverged', totalCommits: 2 };
    assert.equal(isReleaseCommitNewer(forward, reverse), true);
  });
});
