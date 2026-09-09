import { latestCoordByTime } from './trackLastPoint';
import type { LiveTrack, PointParams, TrackCoordinate } from './types/track';

export interface LiveTrackHeadPoint {
  point: TrackCoordinate;
  props: PointParams;
}

/**
 * Sole writer of live heads on web. Catalog metadata refresh must not write here.
 */
export class LiveTrackHead {
  private readonly heads = new Map<string, LiveTrackHeadPoint>();

  upsert(trackId: string, point: TrackCoordinate, props: PointParams = {}): void {
    const id = String(trackId).trim();
    if (!id || !point || point.length < 2) return;
    const existing = this.heads.get(id);
    const nextTs = typeof point[2] === 'number' ? point[2] : Number.NEGATIVE_INFINITY;
    const existingTs = existing && typeof existing.point[2] === 'number'
      ? existing.point[2]
      : Number.NEGATIVE_INFINITY;
    if (existing && existingTs >= nextTs) return;
    this.heads.set(id, { point, props });
  }

  get(trackId: string): LiveTrackHeadPoint | undefined {
    return this.heads.get(String(trackId));
  }

  remove(trackId: string): void {
    this.heads.delete(String(trackId));
  }

  clear(): void {
    this.heads.clear();
  }

  applyToTrack(track: LiveTrack): LiveTrack {
    const head = this.get(String(track.id));
    if (!head) return track;
    const geomLast = latestCoordByTime(track.geometry?.coordinates ?? []);
    if (geomLast && typeof geomLast[2] === 'number' && typeof head.point[2] === 'number' && geomLast[2] >= head.point[2]) {
      return track;
    }
    return {
      ...track,
      last_point: head.point,
    };
  }
}
