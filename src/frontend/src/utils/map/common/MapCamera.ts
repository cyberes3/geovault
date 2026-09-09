import type { LngLatBoundsLike, Map as MapLibreMap } from 'maplibre-gl';
import { getLoadedMaplibreGl } from '@/utils/map/maplibre/lazyMaplibreGl.js';
import { MAX_ZOOM_LEVEL } from '@/utils/map/maplibre/mapInitialization.js';
import { isValidMapLngLatPair } from '@/utils/map/mapGeography.js';
import { getFeatureCoordinates } from '@/utils/map/maplibre/mapUtils.js';
import { WORLD_VIEW_CENTER_LONLAT, WORLD_VIEW_ZOOM } from '@/utils/map/worldViewDefault';
import type { CameraSnapshot } from './types';

export class MapCamera {
    snapshot: CameraSnapshot | null = null;

    save(map: MapLibreMap | null): CameraSnapshot | null {
        if (!map) return this.snapshot;
        const center = map.getCenter();
        this.snapshot = {
            center: [center.lng, center.lat],
            zoom: map.getZoom(),
            pitch: map.getPitch(),
            bearing: map.getBearing(),
        };
        return this.snapshot;
    }

    restore(map: MapLibreMap | null): void {
        if (!map || !this.snapshot) return;
        map.jumpTo({
            center: this.snapshot.center,
            zoom: this.snapshot.zoom,
            pitch: this.snapshot.pitch,
            bearing: this.snapshot.bearing,
        });
    }

    apply(map: MapLibreMap | null, snapshot: CameraSnapshot): void {
        if (!map) return;
        this.snapshot = snapshot;
        map.jumpTo({
            center: snapshot.center,
            zoom: snapshot.zoom,
            pitch: snapshot.pitch,
            bearing: snapshot.bearing,
        });
    }

    fitToPoints(map: MapLibreMap | null, points: Array<[number, number]>, options: { padding?: number; duration?: number; maxZoom?: number } = {}): void {
        if (!map || points.length === 0) return;
        const valid = points.filter(([lon, lat]) => isValidMapLngLatPair(lon, lat));
        if (valid.length === 0) return;
        if (valid.length === 1) {
            map.flyTo({
                center: valid[0],
                zoom: Math.min(options.maxZoom ?? 10, MAX_ZOOM_LEVEL),
                duration: options.duration ?? 0,
            });
            return;
        }
        let minLon = Infinity;
        let minLat = Infinity;
        let maxLon = -Infinity;
        let maxLat = -Infinity;
        for (const [lon, lat] of valid) {
            minLon = Math.min(minLon, lon);
            minLat = Math.min(minLat, lat);
            maxLon = Math.max(maxLon, lon);
            maxLat = Math.max(maxLat, lat);
        }
        const maplibregl = getLoadedMaplibreGl();
        const bounds = new maplibregl.LngLatBounds([minLon, minLat], [maxLon, maxLat]);
        map.fitBounds(bounds as LngLatBoundsLike, {
            padding: options.padding ?? 50,
            duration: options.duration ?? 0,
            maxZoom: options.maxZoom ?? MAX_ZOOM_LEVEL,
        });
    }

    fitFeatureGeometries(map: MapLibreMap | null, features: Array<{ geometry: { coordinates?: unknown; type: string } }>, options: { padding?: number; duration?: number } = {}): void {
        const points: Array<[number, number]> = [];
        for (const feature of features) {
            if (!feature.geometry?.coordinates) continue;
            for (const coord of getFeatureCoordinates(feature.geometry as never)) {
                if (Array.isArray(coord) && coord.length >= 2) {
                    points.push([Number(coord[0]), Number(coord[1])]);
                }
            }
        }
        this.fitToPoints(map, points, options);
    }

    worldView(): CameraSnapshot {
        return {
            center: WORLD_VIEW_CENTER_LONLAT,
            zoom: WORLD_VIEW_ZOOM,
            pitch: 0,
            bearing: 0,
        };
    }
}
