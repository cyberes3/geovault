import { markRaw, shallowRef } from 'vue';
import { Map, View } from 'ol';
import type BaseLayer from 'ol/layer/Base';
import { Vector as VectorLayer } from 'ol/layer';
import { Vector as VectorSource } from 'ol/source';
import { GeoJSON } from 'ol/format';
import { fromLonLat } from 'ol/proj';
import { getCenter } from 'ol/extent';
import type { Extent } from 'ol/extent';
import type Control from 'ol/control/Control';
import type Interaction from 'ol/interaction/Interaction';
import type { FeatureLike } from 'ol/Feature';
import type { Style } from 'ol/style';
import { openLayersBasemap } from '@/utils/map/openlayers/index.js';

/**
 * A minimal GeoJSON Feature shape - matches what the import/feature-replacement APIs return.
 * `geometry` is intentionally loosely typed since it's only ever forwarded to `ol/format/GeoJSON`.
 */
export interface GeoJsonFeatureLike {
    geometry: unknown;
    properties?: Record<string, unknown> | null;
}

export type PreviewStyleFn = (feature: FeatureLike) => Style | Style[] | undefined;

export interface UseOpenLayersPreviewMapOptions {
    /** Style applied to the main feature/geometry layer. */
    getFeatureStyle?: PreviewStyleFn;
    /**
     * Style applied to a separate, decluttered label layer. Only dialogs that render text
     * labels over their features need this - when omitted, no label layer is created.
     */
    getLabelStyle?: PreviewStyleFn;
    /** Initial view center in [lon, lat], used until the first fit/zoom call repositions it. */
    initialCenterLonLat?: [number, number];
    initialZoom?: number;
    minZoom?: number;
    maxZoom?: number;
    /** Pass `[]` to render the map with no zoom/attribution controls. Omit for OL defaults. */
    controls?: Control[];
    /** Pass a restricted interaction set (e.g. pan + wheel-zoom only). Omit for OL defaults. */
    interactions?: Interaction[];
    /** Basemap tile source id, forwarded to `openLayersBasemap.createTileLayer()`. */
    tileSourceId?: string;
}

export interface FitOptions {
    padding?: [number, number, number, number];
    maxZoom?: number;
    duration?: number;
}

export interface ZoomToFeatureOptions extends FitOptions {
    /**
     * If the feature's extent is smaller than this (in map units, e.g. meters) in both
     * dimensions, buffer the fit extent out to `pointBufferMeters` around its center.
     * Used to avoid over-zooming into a single point.
     */
    pointBufferThresholdMeters?: number;
    pointBufferMeters?: number;
    /** Always fit a fixed-size square around the feature's center, ignoring its real extent. */
    forceBufferMeters?: number;
}

const DEFAULT_CENTER_LONLAT: [number, number] = [-104.692626, 38.881215];
const DEFAULT_ZOOM = 10;
const DEFAULT_FIT_PADDING: [number, number, number, number] = [50, 50, 50, 50];
const DEFAULT_FIT_MAX_ZOOM = 15;

function bufferedExtentAroundCenter(extent: Extent, bufferMeters: number): Extent {
    const center = getCenter(extent);
    return [center[0] - bufferMeters, center[1] - bufferMeters, center[0] + bufferMeters, center[1] + bufferMeters];
}

/**
 * Reads the `properties` bag off an OL feature loaded via `loadFeatures()`. Shared by all
 * three dialogs' style callbacks so they don't each re-derive/cast this from `feature.get(...)`.
 */
export function getFeatureProperties(feature: FeatureLike): Record<string, unknown> {
    const properties: unknown = feature.get('properties');
    if (properties && typeof properties === 'object') {
        return properties as Record<string, unknown>;
    }
    return {};
}

export function getStringProperty(properties: Record<string, unknown>, key: string, fallback = ''): string {
    const value = properties[key];
    return typeof value === 'string' && value.trim() ? value : fallback;
}

export function getNumberProperty(properties: Record<string, unknown>, key: string, fallback: number): number;
// eslint-disable-next-line no-redeclare -- TS overload signature; base no-redeclare doesn't understand these.
export function getNumberProperty(properties: Record<string, unknown>, key: string): number | undefined;
// eslint-disable-next-line no-redeclare -- TS overload signature; base no-redeclare doesn't understand these.
export function getNumberProperty(properties: Record<string, unknown>, key: string, fallback?: number): number | undefined {
    const value = properties[key];
    return typeof value === 'number' ? value : fallback;
}

/**
 * Manages the lifecycle of a single small OpenLayers preview map: creating the `Map`/`View`,
 * a `VectorSource` + styled `VectorLayer` (and optional label layer), loading GeoJSON features,
 * fit/zoom helpers, and teardown. This is a plain factory (not tied to a component's `setup()`
 * lifecycle), so it's safe to call multiple times to manage multiple independent map instances,
 * e.g. one per candidate feature plus an expanded preview.
 */
export function useOpenLayersPreviewMap(options: UseOpenLayersPreviewMapOptions = {}) {
    const map = shallowRef<Map | null>(null);
    const vectorSource = shallowRef<VectorSource | null>(null);
    const vectorLayer = shallowRef<VectorLayer | null>(null);
    const labelLayer = shallowRef<VectorLayer | null>(null);

    const geoJsonFormat = new GeoJSON();

    async function initMap(container: HTMLElement | null | undefined, viewOverrides: { centerLonLat?: [number, number]; zoom?: number } = {}): Promise<Map | null> {
        if (map.value) {
            cleanup();
        }
        if (!container) {
            return null;
        }

        const featureStyleFn = options.getFeatureStyle;
        const labelStyleFn = options.getLabelStyle;

        const source = markRaw(new VectorSource());
        const layer = markRaw(new VectorLayer({
            source,
            style: featureStyleFn ? (feature: FeatureLike) => featureStyleFn(feature) : undefined,
        }));

        let labels: VectorLayer | null = null;
        if (labelStyleFn) {
            labels = markRaw(new VectorLayer({
                source,
                style: (feature: FeatureLike) => labelStyleFn(feature),
                declutter: true,
            }));
        }

        const tileLayer = markRaw(await openLayersBasemap.createTileLayer(options.tileSourceId));

        const layers: BaseLayer[] = [tileLayer, layer];
        if (labels) {
            layers.push(labels);
        }

        const centerLonLat = viewOverrides.centerLonLat ?? options.initialCenterLonLat ?? DEFAULT_CENTER_LONLAT;
        const zoom = viewOverrides.zoom ?? options.initialZoom ?? DEFAULT_ZOOM;

        const view = new View({
            center: fromLonLat(centerLonLat),
            zoom,
            minZoom: options.minZoom,
            maxZoom: options.maxZoom,
        });

        const olMap = markRaw(new Map({
            target: container,
            layers,
            view,
            controls: options.controls,
            interactions: options.interactions,
        }));

        vectorSource.value = source;
        vectorLayer.value = layer;
        labelLayer.value = labels;
        map.value = olMap;

        return olMap;
    }

    /** Clears the source and loads the given GeoJSON-like features, projecting WGS84 -> EPSG:3857. */
    function loadFeatures(features: GeoJsonFeatureLike[]): FeatureLike[] {
        const source = vectorSource.value;
        if (!source) {
            return [];
        }

        source.clear();
        if (features.length === 0) {
            return [];
        }

        const geoJsonFeatures = features.map((feature) => ({
            type: 'Feature' as const,
            geometry: feature.geometry,
            properties: feature.properties ?? {},
        }));

        const olFeatures = geoJsonFormat.readFeatures(
            { type: 'FeatureCollection', features: geoJsonFeatures },
            { featureProjection: 'EPSG:3857', dataProjection: 'EPSG:4326' },
        );

        // Preserve original properties both nested and flattened, matching what the
        // dialog-specific style callbacks expect to read via feature.get(...).
        olFeatures.forEach((olFeature, index) => {
            const properties = geoJsonFeatures[index].properties;
            olFeature.set('properties', properties);
            Object.keys(properties).forEach((key) => {
                olFeature.set(key, properties[key]);
            });
        });

        source.addFeatures(olFeatures);
        return olFeatures;
    }

    /** Forces the feature/label layers to re-evaluate their style functions (e.g. after a selection change). */
    function refreshStyles(): void {
        vectorLayer.value?.changed();
        labelLayer.value?.changed();
    }

    function fitToAllFeatures(fitOptions: FitOptions = {}): void {
        const source = vectorSource.value;
        const olMap = map.value;
        if (!source || !olMap) {
            return;
        }
        if (source.getFeatures().length === 0) {
            return;
        }
        olMap.getView().fit(source.getExtent(), {
            padding: fitOptions.padding ?? DEFAULT_FIT_PADDING,
            maxZoom: fitOptions.maxZoom ?? DEFAULT_FIT_MAX_ZOOM,
            duration: fitOptions.duration,
        });
    }

    function zoomToFeature(feature: FeatureLike | null | undefined, zoomOptions: ZoomToFeatureOptions = {}): void {
        const olMap = map.value;
        if (!olMap || !feature) {
            return;
        }
        const geometry = feature.getGeometry();
        if (!geometry) {
            return;
        }
        const extent = geometry.getExtent();
        if (extent.length !== 4) {
            return;
        }

        let fitExtent: Extent = extent;

        if (zoomOptions.forceBufferMeters != null) {
            fitExtent = bufferedExtentAroundCenter(extent, zoomOptions.forceBufferMeters);
        } else if (zoomOptions.pointBufferMeters != null) {
            const threshold = zoomOptions.pointBufferThresholdMeters ?? 100;
            const width = extent[2] - extent[0];
            const height = extent[3] - extent[1];
            if (width < threshold && height < threshold) {
                fitExtent = bufferedExtentAroundCenter(extent, zoomOptions.pointBufferMeters);
            }
        }

        olMap.getView().fit(fitExtent, {
            padding: zoomOptions.padding ?? DEFAULT_FIT_PADDING,
            maxZoom: zoomOptions.maxZoom,
            duration: zoomOptions.duration ?? 500,
        });
    }

    /** Tears down the map and clears all refs. Call this from the owning component's `onBeforeUnmount`. */
    function cleanup(): void {
        if (map.value) {
            map.value.setTarget(undefined);
        }
        map.value = null;
        vectorSource.value?.clear();
        vectorSource.value = null;
        vectorLayer.value = null;
        labelLayer.value = null;
    }

    return {
        map,
        vectorSource,
        vectorLayer,
        labelLayer,
        initMap,
        loadFeatures,
        refreshStyles,
        fitToAllFeatures,
        zoomToFeature,
        cleanup,
    };
}

export type UseOpenLayersPreviewMapReturn = ReturnType<typeof useOpenLayersPreviewMap>;
