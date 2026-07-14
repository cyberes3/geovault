import type { LiveTrack, LiveTrackGroup, TrackVisibility } from './types/track';

function putBaselineValue(payload: Record<string, unknown>, key: string, value: unknown): void {
  if (value === undefined || value === null) return;
  payload[key] = value;
}

function applyOverride(payload: Record<string, unknown>, key: string, value: unknown): void {
  if (value === undefined) return;
  payload[key] = value;
}

function normalizeEmailList(emails: readonly string[] | null | undefined): string[] {
  return [...(emails ?? [])]
    .map((email) => email.trim().toLowerCase())
    .filter(Boolean);
}

export function isTrackerWorldShareEnabled(tracker: LiveTrack | null | undefined): boolean {
  return Boolean(tracker?.world_share_id ?? tracker?.world_share_url);
}

export function isGroupWorldShareEnabled(group: LiveTrackGroup | null | undefined): boolean {
  return Boolean(group?.world_share_id ?? group?.world_share_url);
}

export interface TrackerSettingsSnapshot {
  name?: string;
  color?: string;
  recentDataWindow?: string | null;
  visibility?: TrackVisibility;
  shareParamsWithRecipients?: boolean;
  shareParamsWithWorld?: boolean;
  worldShareEnabled?: boolean;
  trackerHidden?: boolean;
  allowGroupReshare?: boolean;
  sharedWithEmails?: string[];
}

export function buildTrackerSettingsPayloadFromSnapshot(snapshot: TrackerSettingsSnapshot | null | undefined): Record<string, unknown> {
  const payload: Record<string, unknown> = {
    name: String(snapshot?.name ?? '').trim(),
    color: snapshot?.color,
    recent_data_window: snapshot?.recentDataWindow ?? null,
    visibility: snapshot?.visibility ?? 'private',
    share_params_with_recipients: snapshot?.shareParamsWithRecipients === true,
    share_params_with_world: snapshot?.shareParamsWithWorld === true,
    world_share_enabled: snapshot?.worldShareEnabled === true,
    hidden: snapshot?.trackerHidden === true,
    allow_group_reshare: snapshot?.allowGroupReshare === true,
  };
  if (payload.visibility === 'shared') {
    payload.shared_with_emails = normalizeEmailList(snapshot?.sharedWithEmails);
  }
  return payload;
}

export interface TrackerPreservingSettingsOverrides {
  name?: string;
  color?: string;
  recent_data_window?: string | null;
  visibility?: TrackVisibility;
  share_params_with_recipients?: boolean;
  share_params_with_world?: boolean;
  shared_with_emails?: string[];
  world_share_enabled?: boolean;
  hidden?: boolean;
  allow_group_reshare?: boolean;
}

export function buildTrackerPreservingSettingsPayload(tracker: LiveTrack | null | undefined, overrides: TrackerPreservingSettingsOverrides = {}): Record<string, unknown> {
  const payload: Record<string, unknown> = {};
  const settings = tracker?.settings ?? {};
  const effectiveVisibility = overrides.visibility ?? tracker?.visibility;

  putBaselineValue(payload, 'name', tracker?.name);
  putBaselineValue(payload, 'color', tracker?.color);
  putBaselineValue(payload, 'recent_data_window', settings.recent_data_window);
  putBaselineValue(payload, 'visibility', tracker?.visibility);
  putBaselineValue(payload, 'share_params_with_recipients', tracker?.share_params_with_recipients);
  putBaselineValue(payload, 'share_params_with_world', tracker?.share_params_with_world);
  if (effectiveVisibility === 'shared') {
    putBaselineValue(payload, 'shared_with_emails', tracker?.shared_with_emails);
  }
  putBaselineValue(payload, 'world_share_enabled', isTrackerWorldShareEnabled(tracker));
  putBaselineValue(payload, 'hidden', settings.hidden);
  putBaselineValue(payload, 'allow_group_reshare', settings.allow_group_reshare);

  applyOverride(payload, 'name', overrides.name);
  applyOverride(payload, 'color', overrides.color);
  applyOverride(payload, 'recent_data_window', overrides.recent_data_window);
  applyOverride(payload, 'visibility', overrides.visibility);
  applyOverride(payload, 'share_params_with_recipients', overrides.share_params_with_recipients);
  applyOverride(payload, 'share_params_with_world', overrides.share_params_with_world);
  applyOverride(
    payload,
    'shared_with_emails',
    overrides.shared_with_emails !== undefined
      ? normalizeEmailList(overrides.shared_with_emails)
      : undefined,
  );
  applyOverride(payload, 'world_share_enabled', overrides.world_share_enabled);
  applyOverride(payload, 'hidden', overrides.hidden);
  applyOverride(payload, 'allow_group_reshare', overrides.allow_group_reshare);
  return payload;
}

export function buildTrackerSharingPayload(tracker: LiveTrack | null | undefined, visibility: TrackVisibility, sharedWithEmails: string[] | null | undefined): Record<string, unknown> {
  return buildTrackerPreservingSettingsPayload(tracker, {
    visibility,
    shared_with_emails: visibility === 'shared' ? normalizeEmailList(sharedWithEmails) : undefined,
    world_share_enabled: visibility === 'private' ? false : undefined,
  });
}

export function buildTrackerUnhidePayload(tracker: LiveTrack | null | undefined): Record<string, unknown> {
  return buildTrackerPreservingSettingsPayload(tracker, {
    hidden: false,
  });
}

export interface GroupPreservingPatchOverrides {
  name?: string;
  hidden?: boolean;
  visibility?: TrackVisibility;
  shared_with_emails?: string[];
  world_share_enabled?: boolean;
  add_track_ids?: Array<string | number>;
  remove_track_ids?: Array<string | number>;
}

export function buildGroupPreservingPatchPayload(group: LiveTrackGroup | null | undefined, overrides: GroupPreservingPatchOverrides = {}): Record<string, unknown> {
  const payload: Record<string, unknown> = {};
  const effectiveVisibility = overrides.visibility ?? group?.visibility;
  putBaselineValue(payload, 'name', group?.name);
  putBaselineValue(payload, 'hidden', group?.hidden);
  putBaselineValue(payload, 'visibility', group?.visibility);
  if (effectiveVisibility === 'shared') {
    putBaselineValue(payload, 'shared_with_emails', group?.shared_with_emails);
  }
  putBaselineValue(payload, 'world_share_enabled', isGroupWorldShareEnabled(group));

  applyOverride(payload, 'name', overrides.name);
  applyOverride(payload, 'hidden', overrides.hidden);
  applyOverride(payload, 'visibility', overrides.visibility);
  applyOverride(
    payload,
    'shared_with_emails',
    overrides.shared_with_emails !== undefined
      ? normalizeEmailList(overrides.shared_with_emails)
      : undefined,
  );
  applyOverride(payload, 'world_share_enabled', overrides.world_share_enabled);
  applyOverride(payload, 'add_track_ids', overrides.add_track_ids);
  applyOverride(payload, 'remove_track_ids', overrides.remove_track_ids);
  return payload;
}

export function buildGroupUnhidePayload(group: LiveTrackGroup | null | undefined): Record<string, unknown> {
  return buildGroupPreservingPatchPayload(group, {
    hidden: false,
  });
}

export function buildHiddenItemsClearPayload(targetTypes: readonly string[] | null | undefined): Record<string, unknown> {
  const normalized = [...(targetTypes ?? [])]
    .map((value) => value.trim().toLowerCase())
    .filter(Boolean);
  if (!normalized.length) {
    return {};
  }
  return { target_types: normalized };
}
