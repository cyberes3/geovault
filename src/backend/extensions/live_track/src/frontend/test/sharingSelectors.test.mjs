import test from 'node:test';
import assert from 'node:assert/strict';
import { countOverlappingIncomingShares } from '../src/sharingSelectors.js';

test('countOverlappingIncomingShares returns zero when there is no overlap', () => {
  const incoming = [
    { id: 't-1', name: 'Tracker 1' },
    { id: 't-2', name: 'Tracker 2' },
  ];
  assert.equal(countOverlappingIncomingShares(incoming, ['t-3', 't-4']), 0);
});

test('countOverlappingIncomingShares counts partial overlap', () => {
  const incoming = [
    { id: 't-1', name: 'Tracker 1' },
    { id: 't-2', name: 'Tracker 2' },
    { id: 't-3', name: 'Tracker 3' },
  ];
  assert.equal(countOverlappingIncomingShares(incoming, ['t-2', 't-4', 't-3']), 2);
});

test('countOverlappingIncomingShares normalizes IDs before comparing', () => {
  const incoming = [{ id: 42, name: 'Tracker 42' }];
  assert.equal(countOverlappingIncomingShares(incoming, ['42']), 1);
});

test('countOverlappingIncomingShares handles empty inputs', () => {
  assert.equal(countOverlappingIncomingShares([], ['t-1']), 0);
  assert.equal(countOverlappingIncomingShares([{ id: 't-1' }], []), 0);
  assert.equal(countOverlappingIncomingShares(null, null), 0);
});
