import test from 'node:test';
import assert from 'node:assert/strict';
import {
  computeVisibleSharedTrackers,
  filterByQuery,
  isAcceptedOrOwnedGroup,
  isGroupHiddenByMap
} from './sharingSelectors.js';

test('isAcceptedOrOwnedGroup matches canonical acceptance semantics', () => {
  assert.equal(isAcceptedOrOwnedGroup({ is_owner: true, is_accepted: false }), true);
  assert.equal(isAcceptedOrOwnedGroup({ is_owner: false, is_accepted: true }), true);
  assert.equal(isAcceptedOrOwnedGroup({ is_owner: false, is_accepted: false }), false);
});

test('computeVisibleSharedTrackers excludes hidden and grouped trackers', () => {
  const sortedTrackers = [
    { id: 't1', is_owner: false, visibility: 'shared' },
    { id: 't2', is_owner: false, visibility: 'public' },
    { id: 't3', is_owner: false, visibility: 'shared' }
  ];
  const sortedGroups = [{ id: 'g1', is_owner: false, track_ids: ['t1'] }];
  const visible = computeVisibleSharedTrackers(
    sortedTrackers,
    sortedGroups,
    new Set(['t3']),
    new Set()
  );
  assert.deepEqual(visible.map((t) => t.id), ['t2']);
});

test('isGroupHiddenByMap prefers hidden_group_ids then track coverage', () => {
  assert.equal(
    isGroupHiddenByMap({ id: 'g1', track_ids: ['t1', 't2'] }, [], ['g1']),
    true
  );
  assert.equal(
    isGroupHiddenByMap({ id: 'g1', track_ids: ['t1', 't2'] }, ['t1', 't2'], []),
    true
  );
  assert.equal(
    isGroupHiddenByMap({ id: 'g1', track_ids: ['t1', 't2'] }, ['t1'], []),
    false
  );
});

test('filterByQuery searches by name and owner_email', () => {
  const rows = [
    { name: 'Alpha', owner_email: 'alice@example.com' },
    { name: 'Bravo', owner_email: 'bob@example.com' }
  ];
  assert.equal(filterByQuery(rows, 'alp').length, 1);
  assert.equal(filterByQuery(rows, 'bob').length, 1);
});
