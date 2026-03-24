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

export function computeVisibleSharedTrackers(sortedTrackers, sortedGroups) {
  const sharedGroups = (sortedGroups || []).filter((group) => !isOwned(group));
  const trackIdsInSharedGroups = new Set(
    sharedGroups.flatMap((group) => (group.track_ids || []).map((id) => normalizeId(id)))
  );
  return (sortedTrackers || []).filter(
    (track) =>
      isSharedOrPublicTracker(track) &&
      !trackIdsInSharedGroups.has(normalizeId(track.id))
  );
}

export function computeVisibleSharedGroups(groups) {
  return (groups || []).filter(
    (group) =>
      !isOwned(group) &&
      (group?.visibility || '') === 'shared' &&
      group?.is_accepted === true
  );
}

export function isVisibleOwnedTracker(track) {
  return isOwned(track) && !track?.settings?.hidden;
}

export function isVisibleOwnedGroup(group) {
  return isOwned(group) && !group?.hidden;
}

export function isHiddenOwnedTracker(track) {
  return isOwned(track) && !!track?.settings?.hidden;
}

export function isHiddenOwnedGroup(group) {
  return isOwned(group) && !!group?.hidden;
}

export function isSharedGroupNotOwned(group) {
  return !isOwned(group);
}

