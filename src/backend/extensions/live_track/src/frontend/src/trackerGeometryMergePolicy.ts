import type { LiveTrack, PointParams, TrackGeometry } from './types/track';

/**
 * Merges a geometry-focused tracker payload into an existing snapshot without
 * dropping previously known metadata when the geometry endpoint omits them.
 * Port of Android TrackerGeometryMergePolicy.
 */
export function mergeTrackerGeometry(existing: LiveTrack | null | undefined, incoming: LiveTrack): LiveTrack {
  if (!existing) return incoming;
  const incomingHasGeometry = incoming.geometry != null;
  return {
    ...existing,
    ...incoming,
    name: incoming.name && incoming.name.trim() !== '' ? incoming.name : existing.name,
    color: incoming.color ?? existing.color,
    settings: incoming.settings ?? existing.settings,
    geometry: incoming.geometry ?? existing.geometry,
    point_params: mergedPointParams(existing, incoming, incomingHasGeometry),
    geometry_status: incoming.geometry_status != null
      ? incoming.geometry_status
      : (incomingHasGeometry ? undefined : existing.geometry_status),
    last_point: incoming.last_point ?? existing.last_point,
    bbox: incoming.bbox ?? existing.bbox,
    tracker_secret: incoming.tracker_secret ?? existing.tracker_secret,
    created_at: incoming.created_at ?? existing.created_at,
    updated_at: incoming.updated_at ?? existing.updated_at,
    is_owner: incoming.is_owner ?? existing.is_owner,
    visibility: incoming.visibility ?? existing.visibility,
    share_params_with_recipients: incoming.share_params_with_recipients ?? existing.share_params_with_recipients,
    share_params_with_world: incoming.share_params_with_world ?? existing.share_params_with_world,
    owner_email: incoming.owner_email ?? existing.owner_email,
    subscriber_count: incoming.subscriber_count ?? existing.subscriber_count,
    internal_share_id: incoming.internal_share_id ?? existing.internal_share_id,
    internal_share_url: incoming.internal_share_url ?? existing.internal_share_url,
    world_share_id: incoming.world_share_id ?? existing.world_share_id,
    world_share_url: incoming.world_share_url ?? existing.world_share_url,
    shared_with_emails: incoming.shared_with_emails ?? existing.shared_with_emails,
  };
}

function mergedPointParams(
  existing: LiveTrack,
  incoming: LiveTrack,
  incomingHasGeometry: boolean,
): PointParams[] | undefined {
  const incomingParams = incoming.point_params;
  const existingParams = existing.point_params;
  if (!incomingHasGeometry) {
    if (!incomingParams || incomingParams.length === 0) return existingParams;
    if (existingParams != null && incomingParams.length < existingParams.length) {
      return existingParams;
    }
    return incomingParams;
  }
  if (!incomingParams || incomingParams.length === 0) return existingParams;
  if (existingParams != null && incomingParams.length < existingParams.length) {
    return existingParams;
  }
  return incomingParams;
}

export function catalogRefreshLeavesTrunk(existing: LiveTrack, incoming: LiveTrack): LiveTrack {
  const trunkGeometry: TrackGeometry | undefined = existing.geometry;
  const trunkParams = existing.point_params;
  return mergeTrackerGeometry(existing, {
    ...incoming,
    geometry: undefined,
    point_params: incoming.point_params && incoming.point_params.length ? incoming.point_params : undefined,
    geometry_status: incoming.geometry_status,
  }) as LiveTrack & { geometry?: TrackGeometry };
}
