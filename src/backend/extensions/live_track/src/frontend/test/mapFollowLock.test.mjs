import { test } from 'node:test';
import assert from 'node:assert/strict';
import { followLockUnlocks } from '../src/mapFollowLock.js';

test('followLockUnlocks only on pan and rotate', () => {
  assert.equal(followLockUnlocks('pan'), true);
  assert.equal(followLockUnlocks('rotate'), true);
  assert.equal(followLockUnlocks('pinch'), false);
  assert.equal(followLockUnlocks('wheel'), false);
  assert.equal(followLockUnlocks('dblclick'), false);
});
