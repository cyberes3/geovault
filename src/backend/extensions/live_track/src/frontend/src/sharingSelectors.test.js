import test from 'node:test';
import assert from 'node:assert/strict';
import {
  computeVisibleSharedGroups,
  computeVisibleSharedTrackers,
  filterByQuery,
  isAcceptedOrOwnedGroup,
  isGroupHiddenByMap,
  isHiddenInListGroup,
  isHiddenInListTracker,
  isPublic,
  isShared,
  isSharedGroupNotOwned,
  isSharedOrPublicOwned,
  isVisibleInListGroup,
  isVisibleInListTracker
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

test('owned/shared/public predicates are canonical', () => {
  assert.equal(isSharedOrPublicOwned({ is_owner: true, visibility: 'shared' }), true);
  assert.equal(isSharedOrPublicOwned({ is_owner: true, visibility: 'public' }), true);
  assert.equal(isSharedOrPublicOwned({ is_owner: true, visibility: 'private' }), false);
  assert.equal(isPublic({ visibility: 'public' }), true);
  assert.equal(isShared({ visibility: 'shared' }), true);
  assert.equal(isSharedGroupNotOwned({ is_owner: false }), true);
});

test('list visibility predicates match hidden_in_list semantics', () => {
  assert.equal(isVisibleInListTracker({ is_owner: true, settings: {} }), true);
  assert.equal(isVisibleInListTracker({ is_owner: true, settings: { hidden_in_list: true } }), false);
  assert.equal(isVisibleInListGroup({ is_owner: true, hidden_in_list: false }), true);
  assert.equal(isVisibleInListGroup({ is_owner: true, hidden_in_list: true }), false);
  assert.equal(isHiddenInListTracker({ is_owner: true, settings: { hidden_in_list: true } }), true);
  assert.equal(isHiddenInListGroup({ is_owner: true, hidden_in_list: true }), true);
});

test('computeVisibleSharedGroups excludes pending and hidden', () => {
  const groups = [
    { id: 'g1', is_owner: false, visibility: 'shared', is_accepted: true },
    { id: 'g2', is_owner: false, visibility: 'shared', is_accepted: false },
    { id: 'g3', is_owner: true, visibility: 'shared', is_accepted: true }
  ];
  const visible = computeVisibleSharedGroups(groups, ['g1']);
  assert.deepEqual(visible.map((g) => g.id), []);
  const visible2 = computeVisibleSharedGroups(groups, []);
  assert.deepEqual(visible2.map((g) => g.id), ['g1']);
});
