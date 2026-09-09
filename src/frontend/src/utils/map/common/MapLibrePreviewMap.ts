import type { GeoJSONSource, Map as MapLibreMap, MapMouseEvent } from 'maplibre-gl';
import { initializeMap, resolveMapStyle, createTransformRequest, MAX_ZOOM_LEVEL } from '@/utils/map/maplibre/mapInitialization.js';
import { tileSourceCatalog } from '@/utils/map/tileSources/sharedCatalog.js';
import { getDefaultBasemapFromStore } from '@/utils/map/mapConfigUtils';
import { WORLD_VIEW_CENTER_LONLAT, WORLD_VIEW_ZOOM } from '@/utils/map/worldViewDefault';
import type { GeoJsonFeature, GeoJsonFeatureCollection } from '@/types/geospatial';
import { getFeatureCoordinates } from '@/utils/map/maplibre/mapUtils.js';
import { isValidMapLngLatPair } from '@/utils/map/mapGeography.js';
import { getLoadedMaplibreGl } from '@/utils/map/maplibre/lazyMaplibreGl.js';

const PREVIEW_SOURCE = 'preview-geojson';

export interface PreviewFitOptions {
    padding?: number;
    duration?: number;
    maxZoom?: number;
}

/**
 * One MapLibre preview or point-picker. Callers must not construct N instances for a list.
 */
export class MapLibrePreviewMap {
    map: MapLibreMap | null = null;
    private clickHandler: ((lng: number, lat: number) => void) | null = null;

    async create(container: HTMLElement, options: { interactive?: boolean; onPick?: (lng: number, lat: number) => void } = {}): Promise<MapLibreMap> {
        this.destroy();
        const sources = await tileSourceCatalog.load();
        const preferred = getDefaultBasemapFromStore();
        const tileSource = sources.find((source) => source.id === preferred) ?? sources[0];
        this.map = await initializeMap(container, {
            center: WORLD_VIEW_CENTER_LONLAT,
            zoom: WORLD_VIEW_ZOOM,
            style: resolveMapStyle(tileSource),
            antialias: false,
            transformRequest: createTransformRequest(),
        });
        this.map.addSource(PREVIEW_SOURCE, {
            type: 'geojson',
            data: { type: 'FeatureCollection', features: [] },
        });
        this.addPreviewLayers();
        if (options.onPick) {
            this.clickHandler = options.onPick;
            this.map.on('click', this.onClick);
        }
        if (options.interactive === false) {
            this.map.dragPan.disable();
            this.map.scrollZoom.disable();
            this.map.boxZoom.disable();
            this.map.dragRotate.disable();
            this.map.keyboard.disable();
            this.map.doubleClickZoom.disable();
            this.map.touchZoomRotate.disable();
        }
        return this.map;
    }

    loadFeatures(features: GeoJsonFeature[]): void {
        const source = this.map?.getSource(PREVIEW_SOURCE) as GeoJSONSource | undefined;
        if (!source) return;
        const collection: GeoJsonFeatureCollection = { type: 'FeatureCollection', features };
        source.setData(collection);
    }

    fitToFeatures(features: GeoJsonFeature[], options: PreviewFitOptions = {}): void {
        if (!this.map || features.length === 0) return;
        const points: Array<[number, number]> = [];
        for (const feature of features) {
            for (const coord of getFeatureCoordinates(feature.geometry as never)) {
                if (Array.isArray(coord) && coord.length >= 2 && isValidMapLngLatPair(Number(coord[0]), Number(coord[1]))) {
                    points.push([Number(coord[0]), Number(coord[1])]);
                }
            }
        }
        if (points.length === 0) return;
        if (points.length === 1) {
            this.map.jumpTo({ center: points[0], zoom: Math.min(options.maxZoom ?? 12, MAX_ZOOM_LEVEL) });
            return;
        }
        let minLon = Infinity;
        let minLat = Infinity;
        let maxLon = -Infinity;
        let maxLat = -Infinity;
        for (const [lon, lat] of points) {
            minLon = Math.min(minLon, lon);
            minLat = Math.min(minLat, lat);
            maxLon = Math.max(maxLon, lon);
            maxLat = Math.max(maxLat, lat);
        }
        const maplibregl = getLoadedMaplibreGl();
        this.map.fitBounds(new maplibregl.LngLatBounds([minLon, minLat], [maxLon, maxLat]), {
            padding: options.padding ?? 24,
            duration: options.duration ?? 0,
            maxZoom: options.maxZoom ?? 15,
        });
    }

    flyTo(lng: number, lat: number, zoom = 12): void {
        this.map?.flyTo({ center: [lng, lat], zoom, duration: 400 });
    }

    setMarker(lng: number, lat: number): void {
        this.loadFeatures([{
            type: 'Feature',
            geometry: { type: 'Point', coordinates: [lng, lat] },
            properties: {},
        }]);
    }

    resize(): void {
        this.map?.resize();
    }

    destroy(): void {
        if (this.map && this.clickHandler) {
            this.map.off('click', this.onClick);
        }
        this.clickHandler = null;
        if (this.map) {
            this.map.remove();
            this.map = null;
        }
    }

    private onClick = (event: MapMouseEvent): void => {
        this.clickHandler?.(event.lngLat.lng, event.lngLat.lat);
    };

    private addPreviewLayers(): void {
        const map = this.map;
        if (!map) return;
        map.addLayer({
            id: 'preview-polygons',
            type: 'fill',
            source: PREVIEW_SOURCE,
            filter: ['any', ['==', ['geometry-type'], 'Polygon'], ['==', ['geometry-type'], 'MultiPolygon']],
            paint: { 'fill-color': ['coalesce', ['get', 'fill'], '#163D8A'], 'fill-opacity': 0.3 },
        });
        map.addLayer({
            id: 'preview-lines',
            type: 'line',
            source: PREVIEW_SOURCE,
            filter: ['any', ['==', ['geometry-type'], 'LineString'], ['==', ['geometry-type'], 'MultiLineString']],
            paint: { 'line-color': ['coalesce', ['get', 'stroke'], '#163D8A'], 'line-width': ['coalesce', ['get', 'stroke-width'], 3] },
        });
        map.addLayer({
            id: 'preview-points',
            type: 'circle',
            source: PREVIEW_SOURCE,
            filter: ['any', ['==', ['geometry-type'], 'Point'], ['==', ['geometry-type'], 'MultiPoint']],
            paint: {
                'circle-radius': 6,
                'circle-color': ['coalesce', ['get', 'marker-color'], '#fbbf24'],
                'circle-stroke-width': 2,
                'circle-stroke-color': '#000000',
            },
        });
    }
}

export async function createGeoJsonPreviewMap(container: HTMLElement): Promise<MapLibrePreviewMap> {
    const preview = new MapLibrePreviewMap();
    await preview.create(container);
    return preview;
}

export async function createPointPickerMap(container: HTMLElement, onPick: (lng: number, lat: number) => void): Promise<MapLibrePreviewMap> {
    const preview = new MapLibrePreviewMap();
    await preview.create(container, { onPick });
    return preview;
}
