import type { LiveTrack } from './types/track';

/**
 * Whether the current user may add this tracker to a group they own.
 * Matches backend `group_views.group_add_track` / group PATCH: non-owner
 * trackers require `settings.allow_group_reshare === true`.
 */
export function isTrackerAddableToGroup(track: LiveTrack | null | undefined): boolean {
  if (track?.is_owner === true) return true;
  return (track?.settings?.allow_group_reshare) === true;
}

/** Empty string when the tracker can be added; otherwise a short reason for UI (tooltip). */
export function getTrackerAddToGroupBlockedReason(track: LiveTrack | null | undefined): string {
  if (isTrackerAddableToGroup(track)) return '';
  return 'The tracker owner has not allowed adding this tracker to groups.';
}
