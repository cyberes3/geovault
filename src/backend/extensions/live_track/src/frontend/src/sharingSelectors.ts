import type { LiveTrack, LiveTrackGroup } from './types/track';

type OwnableItem = { is_owner?: boolean };
type VisibilityItem = { visibility?: string };

export function normalizeId(id: string | number | null | undefined): string {
  if (id == null) return '';
  return String(id);
}

export function isOwned(item: OwnableItem | null | undefined): boolean {
  return item?.is_owner === true;
}

export function isAcceptedOrOwnedGroup(group: LiveTrackGroup | null | undefined): boolean {
  if (isOwned(group)) return true;
  return group?.is_accepted === true;
}

export function isSharedOrPublicTracker(track: LiveTrack | null | undefined): boolean {
  const visibility = track?.visibility ?? '';
  return !isOwned(track) && (visibility === 'shared' || visibility === 'public');
}

export function isSharedOrPublicOwned(track: LiveTrack | null | undefined): boolean {
  const visibility = track?.visibility ?? '';
  return isOwned(track) && (visibility === 'shared' || visibility === 'public');
}

export function isPublic(item: VisibilityItem | null | undefined): boolean {
  return (item?.visibility ?? '') === 'public';
}

export function isShared(item: VisibilityItem | null | undefined): boolean {
  return (item?.visibility ?? '') === 'shared';
}

export function toIdSet(value: unknown): Set<string> {
  if (value instanceof Set) return new Set([...value].map((id) => normalizeId(id as string | number)));
  if (Array.isArray(value)) return new Set(value.map((id) => normalizeId(id)));
  return new Set();
}

export function filterByQuery<T extends Record<string, unknown>>(list: T[], query: string | null | undefined, nameKey: string = 'name', ownerKey: string = 'owner_email'): T[] {
  const q = (query ?? '').trim().toLowerCase();
  if (!q) return list;
  return list.filter(
    (item) =>
      String((item[nameKey] as string | number | undefined) ?? '').toLowerCase().includes(q) ||
      String((item[ownerKey] as string | number | undefined) ?? '').toLowerCase().includes(q)
  );
}

export function computeVisibleSharedTrackers(sortedTrackers: LiveTrack[] | null | undefined, sortedGroups: LiveTrackGroup[] | null | undefined): LiveTrack[] {
  const sharedGroups = (sortedGroups ?? []).filter((group) => !isOwned(group));
  const trackIdsInSharedGroups = new Set(
    sharedGroups.flatMap((group) => (group.track_ids ?? []).map((id) => normalizeId(id)))
  );
  return (sortedTrackers ?? []).filter(
    (track) =>
      isSharedOrPublicTracker(track) &&
      !trackIdsInSharedGroups.has(normalizeId(track.id))
  );
}

export function computeVisibleSharedGroups(groups: LiveTrackGroup[] | null | undefined): LiveTrackGroup[] {
  return (groups ?? []).filter(
    (group) =>
      !isOwned(group) &&
      (group.visibility ?? '') === 'shared' &&
      group.is_accepted === true
  );
}

export function isVisibleOwnedTracker(track: LiveTrack | null | undefined): boolean {
  return isOwned(track) && !track?.settings?.hidden;
}

export function isVisibleOwnedGroup(group: LiveTrackGroup | null | undefined): boolean {
  return isOwned(group) && !group?.hidden;
}

export function isHiddenOwnedTracker(track: LiveTrack | null | undefined): boolean {
  return isOwned(track) && !!track?.settings?.hidden;
}

export function isHiddenOwnedGroup(group: LiveTrackGroup | null | undefined): boolean {
  return isOwned(group) && !!group?.hidden;
}

export function isSharedGroupNotOwned(group: LiveTrackGroup | null | undefined): boolean {
  return !isOwned(group);
}

export function countOverlappingIncomingShares(incomingTrackers: LiveTrack[] | null | undefined, groupTrackIds: Array<string | number> | null | undefined): number {
  const incomingIds = toIdSet((incomingTrackers ?? []).map((t) => t.id));
  return (groupTrackIds ?? []).filter((id) => incomingIds.has(normalizeId(id))).length;
}

