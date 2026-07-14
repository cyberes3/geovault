import type { LiveTrack } from './types/track';

export const DEFAULT_ACCURACY_CIRCLE_LAYER_ID = 'live-track-accuracy-circle';

const METERS_TO_PIXELS_ZOOM_0_EQ = 256 / 40075016.686;
const METERS_TO_PIXELS_ZOOM_24_EQ = (256 * Math.pow(2, 24)) / 40075016.686;

export function getLatestTrackAccuracyMeters(track: LiveTrack | null | undefined): number {
  const acc = track?.latestPointParams?.acc ?? track?.point_params?.[track.point_params.length - 1]?.acc;
  return typeof acc === 'number' && Number.isFinite(acc) && acc > 0 ? acc : 0;
}

export function resolveSelectedTrackAccuracyMeters(track: LiveTrack | null | undefined, isListSelected: boolean): number {
  if (!isListSelected) return 0;
  return getLatestTrackAccuracyMeters(track);
}

export function hexToRgb(hex: string | null | undefined): [number, number, number] {
  const m = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex ?? '#6C93DE');
  return m ? [parseInt(m[1], 16), parseInt(m[2], 16), parseInt(m[3], 16)] : [51, 136, 255];
}

export interface AccuracyCircleLayerSpecOptions {
  layerId?: string;
  sourceId: string;
}

export function buildAccuracyCircleLayerSpec({
  layerId = DEFAULT_ACCURACY_CIRCLE_LAYER_ID,
  sourceId,
}: AccuracyCircleLayerSpecOptions): Record<string, unknown> {
  return {
    id: layerId,
    type: 'circle',
    source: sourceId,
    filter: ['all', ['>', ['get', 'accuracy'], 0]],
    paint: {
      'circle-color': [
        'case',
        ['has', 'colorRgb'],
        ['rgba', ['at', 0, ['get', 'colorRgb']], ['at', 1, ['get', 'colorRgb']], ['at', 2, ['get', 'colorRgb']], 0.25],
        ['rgba', 51, 136, 255, 0.25]
      ],
      'circle-stroke-color': ['case', ['has', 'color'], ['get', 'color'], '#6C93DE'],
      'circle-stroke-width': 1,
      'circle-radius': [
        'interpolate',
        ['exponential', 2],
        ['zoom'],
        0,
        ['max', 6, ['*', ['get', 'accuracy'], ['/', METERS_TO_PIXELS_ZOOM_0_EQ, ['max', 0.001, ['cos', ['*', ['get', 'latitude'], Math.PI / 180]]]]]],
        24,
        ['max', 6, ['*', ['get', 'accuracy'], ['/', METERS_TO_PIXELS_ZOOM_24_EQ, ['max', 0.001, ['cos', ['*', ['get', 'latitude'], Math.PI / 180]]]]]]
      ]
    }
  };
}
