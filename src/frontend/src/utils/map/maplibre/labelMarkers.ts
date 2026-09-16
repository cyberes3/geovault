import type { Map as MapLibreMap, Marker, PositionAnchor } from 'maplibre-gl';
import type { Position } from 'geojson';
import { getLoadedMaplibreGl } from './lazyMaplibreGl.js';
import { MAX_VISIBLE_LABELS } from '@/utils/map/mapLayers';
import type { FeatureSource } from '@/utils/map/common/FeatureSource';
import { canonicalFeatureId, originalFeatureId } from '@/utils/map/common/featureIdentity';
import type { RenderFeature } from '@/utils/map/common/types';

interface CandidateLabel {
    id: string;
    name: string;
    position: Position;
    isLabelPoint: boolean;
    hasIcon: boolean;
}

interface LabelBox extends CandidateLabel {
    bbox: [number, number, number, number];
}

interface LabelMarkerData {
    marker: Marker;
    fullText: string;
    position: Position;
}

function truncateLabelText(text: string, zoom: number): string {
    if (zoom >= 10) return text;
    if (zoom >= 9) return text.length > 15 ? `${text.substring(0, 12)}...` : text;
    if (zoom >= 8) return text.length > 10 ? `${text.substring(0, 7)}...` : text;
    return text.length > 8 ? `${text.substring(0, 5)}...` : text;
}

function createLabelElement(text: string, zoom: number): HTMLElement {
    const el = document.createElement('div');
    el.className = 'maplibre-label-marker';
    el.textContent = truncateLabelText(text, zoom);
    el.style.cssText = `
        padding: 0;
        margin: 0;
        font-size: 12px;
        font-family: 'Noto Sans Regular', 'Arial Unicode MS Regular', sans-serif;
        color: #000000;
        white-space: nowrap;
        pointer-events: none;
        user-select: none;
        text-shadow:
            -1px -1px 0 #ffffff,
             1px -1px 0 #ffffff,
            -1px  1px 0 #ffffff,
             1px  1px 0 #ffffff,
            -1px  0   0 #ffffff,
             1px  0   0 #ffffff,
             0   -1px 0 #ffffff,
             0    1px 0 #ffffff;
    `;
    return el;
}

function estimateBox(map: Pick<MapLibreMap, 'project'>, label: CandidateLabel): LabelBox | null {
    let screen: { x: number; y: number };
    try {
        screen = map.project([label.position[0], label.position[1]]);
    } catch {
        return null;
    }
    const width = label.name.length * 7;
    const height = 12;
    const pad = 4;
    return {
        ...label,
        bbox: [
            screen.x - width / 2 - pad,
            screen.y - height / 2 - pad,
            screen.x + width / 2 + pad,
            screen.y + height / 2 + pad,
        ],
    };
}

function boxesOverlap(a: [number, number, number, number], b: [number, number, number, number]): boolean {
    return !(a[2] < b[0] || a[0] > b[2] || a[3] < b[1] || a[1] > b[3]);
}

export function collectLabelCandidates(features: RenderFeature[]): CandidateLabel[] {
    const featureMap = new Map<string, RenderFeature>();
    const labelPoints = new Map<string, RenderFeature>();

    for (const feature of features) {
        if (feature.properties?._isLabelPoint) {
            const parent = originalFeatureId(feature);
            if (parent) labelPoints.set(parent, feature);
            continue;
        }
        if (feature.properties?._isSmallFeatureReplacement) continue;
        const id = canonicalFeatureId(feature);
        if (id) featureMap.set(id, feature);
    }

    const candidates: CandidateLabel[] = [];
    for (const [id, labelPoint] of labelPoints) {
        if (labelPoint.geometry.type !== 'Point') continue;
        const name = String(labelPoint.properties?.name ?? '').trim();
        if (!name) continue;
        candidates.push({
            id,
            name,
            position: labelPoint.geometry.coordinates as Position,
            isLabelPoint: true,
            hasIcon: false,
        });
    }

    for (const [id, feature] of featureMap) {
        if (candidates.some((candidate) => candidate.id === id)) continue;
        if (feature.geometry.type !== 'Point') continue;
        const name = String(feature.properties?.name ?? '').trim();
        if (!name) continue;
        candidates.push({
            id,
            name,
            position: feature.geometry.coordinates as Position,
            isLabelPoint: false,
            hasIcon: !!feature.properties?.['_icon-id'],
        });
    }

    return candidates;
}

/** Projected AABB collision, then the 200-label cap. */
export function layoutVisibleLabels(
    map: Pick<MapLibreMap, 'project'>,
    features: RenderFeature[],
): LabelBox[] {
    const boxes = collectLabelCandidates(features)
        .map((candidate) => estimateBox(map, candidate))
        .filter((box): box is LabelBox => box !== null);

    const visible: LabelBox[] = [];
    for (const box of boxes) {
        if (visible.some((placed) => boxesOverlap(box.bbox, placed.bbox))) continue;
        visible.push(box);
        if (visible.length >= MAX_VISIBLE_LABELS) break;
    }
    return visible;
}

export class LabelMarkerManager {
    private readonly markers = new Map<string, LabelMarkerData>();
    private showAllLabels = true;
    private updateTimer: ReturnType<typeof setTimeout> | null = null;

    private readonly map: MapLibreMap;
    private readonly featureSource?: FeatureSource;

    constructor(map: MapLibreMap, featureSource?: FeatureSource) {
        this.map = map;
        this.featureSource = featureSource;
    }

    setVisibility(show: boolean): void {
        this.showAllLabels = show;
        const display = show ? 'block' : 'none';
        this.markers.forEach(({ marker }) => {
            marker.getElement().style.display = display;
        });
    }

    sync(immediate = false): void {
        const features = (this.featureSource?.buildRenderCollection().features ?? []) as RenderFeature[];
        this.updateMarkers(features, immediate);
    }

    updateMarkers(features: RenderFeature[], immediate = false): void {
        if (immediate) {
            if (this.updateTimer) {
                clearTimeout(this.updateTimer);
                this.updateTimer = null;
            }
            this.performUpdate(features);
            return;
        }
        if (this.updateTimer) clearTimeout(this.updateTimer);
        this.updateTimer = setTimeout(() => {
            this.updateTimer = null;
            this.performUpdate(features);
        }, 50);
    }

    removeMarker(featureId: string): void {
        const data = this.markers.get(featureId);
        if (!data) return;
        data.marker.remove();
        this.markers.delete(featureId);
    }

    clearAllMarkers(): void {
        this.markers.forEach(({ marker }) => marker.remove());
        this.markers.clear();
    }

    clear(): void {
        if (this.updateTimer) {
            clearTimeout(this.updateTimer);
            this.updateTimer = null;
        }
        this.clearAllMarkers();
    }

    private performUpdate(features: RenderFeature[]): void {
        if (!this.showAllLabels) {
            this.clearAllMarkers();
            return;
        }
        const visible = layoutVisibleLabels(this.map, features);
        const keep = new Set(visible.map((label) => label.id));
        const zoom = this.map.getZoom?.() ?? 10;
        const maplibregl = getLoadedMaplibreGl();

        for (const label of visible) {
            this.ensureMarker(maplibregl, label, zoom);
        }
        for (const id of [...this.markers.keys()]) {
            if (!keep.has(id)) this.removeMarker(id);
        }
    }

    private ensureMarker(
        maplibregl: ReturnType<typeof getLoadedMaplibreGl>,
        label: CandidateLabel,
        zoom: number,
    ): void {
        const existing = this.markers.get(label.id);
        if (existing) {
            existing.marker.setLngLat(label.position as [number, number]);
            const display = truncateLabelText(label.name, zoom);
            if (existing.marker.getElement().textContent !== display) {
                existing.marker.getElement().textContent = display;
            }
            existing.fullText = label.name;
            existing.position = label.position;
            return;
        }

        const el = createLabelElement(label.name, zoom);
        el.style.display = this.showAllLabels ? 'block' : 'none';
        const anchor: PositionAnchor = label.isLabelPoint ? 'center' : 'top';
        const marker = new maplibregl.Marker({ element: el, anchor })
            .setLngLat(label.position as [number, number])
            .addTo(this.map);
        this.markers.set(label.id, { marker, fullText: label.name, position: label.position });
    }
}
