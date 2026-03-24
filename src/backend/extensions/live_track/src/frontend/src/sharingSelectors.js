export function normalizeId(id) {
  if (id == null) return '';
  return String(id);
}

export function isOwned(item) {
  return item?.is_owner === true;
}

export function isAcceptedOrOwnedGroup(group) {
  if (isOwned(group)) return true;
  return group?.is_accepted === true;
}

export function isSharedOrPublicTracker(track) {
  const visibility = (track?.visibility || '');
  return !isOwned(track) && (visibility === 'shared' || visibility === 'public');
}

export function isSharedOrPublicOwned(track) {
  const visibility = (track?.visibility || '');
  return isOwned(track) && (visibility === 'shared' || visibility === 'public');
}

export function isPublic(item) {
  return (item?.visibility || '') === 'public';
}

export function isShared(item) {
  return (item?.visibility || '') === 'shared';
}

export function toIdSet(value) {
  if (value instanceof Set) return new Set([...value].map((id) => normalizeId(id)));
  if (Array.isArray(value)) return new Set(value.map((id) => normalizeId(id)));
  return new Set();
}

export function filterByQuery(list, query, nameKey = 'name', ownerKey = 'owner_email') {
  const q = (query || '').trim().toLowerCase();
  if (!q) return list;
  return list.filter(
    (item) =>
      (item?.[nameKey] || '').toLowerCase().includes(q) ||
      (item?.[ownerKey] || '').toLowerCase().includes(q)
  );
}

export function computeVisibleSharedTrackers(sortedTrackers, sortedGroups, hiddenTrackIds, hiddenGroupIds) {
  const hiddenTrackSet = toIdSet(hiddenTrackIds);
  const hiddenGroupSet = toIdSet(hiddenGroupIds);
  const sharedGroups = (sortedGroups || []).filter(
    (group) => !isOwned(group) && !hiddenGroupSet.has(normalizeId(group.id))
  );
  const trackIdsInSharedGroups = new Set(
    sharedGroups.flatMap((group) => (group.track_ids || []).map((id) => normalizeId(id)))
  );
  return (sortedTrackers || []).filter(
    (track) =>
      isSharedOrPublicTracker(track) &&
      !trackIdsInSharedGroups.has(normalizeId(track.id)) &&
      !hiddenTrackSet.has(normalizeId(track.id))
  );
}

export function computeVisibleSharedGroups(groups, hiddenGroupIds) {
  const hiddenGroupSet = toIdSet(hiddenGroupIds);
  return (groups || []).filter(
    (group) =>
      !isOwned(group) &&
      (group?.visibility || '') === 'shared' &&
      group?.is_accepted === true &&
      !hiddenGroupSet.has(normalizeId(group.id))
  );
}

export function isVisibleInListTracker(track) {
  return isOwned(track) && !(track?.settings?.hidden_in_list);
}

export function isVisibleInListGroup(group) {
  return isOwned(group) && !group?.hidden_in_list;
}

export function isHiddenInListTracker(track) {
  return isOwned(track) && !!(track?.settings?.hidden_in_list);
}

export function isHiddenInListGroup(group) {
  return isOwned(group) && !!group?.hidden_in_list;
}

/** Owner list-hide: hide on map (not persisted in map-visibility). */
export function isHiddenFromOwnerMapTrack(track, groups) {
  if (isHiddenInListTracker(track)) return true;
  const tid = normalizeId(track?.id);
  if (!tid) return false;
  for (const group of groups || []) {
    if (!isHiddenInListGroup(group)) continue;
    const ids = group.track_ids || [];
    for (let i = 0; i < ids.length; i += 1) {
      if (normalizeId(ids[i]) === tid) return true;
    }
  }
  return false;
}

export function isSharedGroupNotOwned(group) {
  return !isOwned(group);
}

export function isGroupHiddenByMap(group, hiddenTrackIds, hiddenGroupIds) {
  const groupSet = toIdSet(hiddenGroupIds);
  const trackSet = toIdSet(hiddenTrackIds);
  if (groupSet.has(normalizeId(group?.id))) return true;
  const trackIds = group?.track_ids || [];
  if (!trackIds.length) return false;
  return trackIds.every((id) => trackSet.has(normalizeId(id)));
}
