import type { Map as MapLibreMap } from 'maplibre-gl';
import { GEOJSON_SOURCE_ID } from '@/utils/map/mapLayers';
import { canonicalFeatureId } from '@/utils/map/common/featureIdentity';
import type { RenderFeature } from '@/utils/map/common/types';
import type { InteractionMode } from './types';

export interface PopupPoint {
    x: number;
    y: number;
}

/**
 * Selection machine. Vue reads these fields; it does not keep a second copy.
 */
export class FeatureInteraction {
    mode: InteractionMode = 'idle';
    selected: RenderFeature | null = null;
    hoveredId: string | null = null;
    overlapping: RenderFeature[] = [];
    popupPoint: PopupPoint | null = null;
    revision = 0;

    private readonly listeners = new Set<() => void>();

    get isEditing(): boolean {
        return this.mode === 'editing';
    }

    subscribe(listener: () => void): () => void {
        this.listeners.add(listener);
        return () => { this.listeners.delete(listener); };
    }

    canHandleClick(): boolean {
        return this.mode !== 'editing';
    }

    handleClick(map: MapLibreMap | null, hits: RenderFeature[], point: PopupPoint): void {
        if (!this.canHandleClick()) return;
        if (this.mode === 'elevationProfile') {
            this.closeElevationProfile();
        }
        if (hits.length === 0) {
            this.select(map, null);
            this.overlapping = [];
            this.popupPoint = null;
            this.notify();
            return;
        }
        if (hits.length === 1) {
            this.select(map, hits[0]);
            this.overlapping = [];
            this.popupPoint = null;
            this.notify();
            return;
        }
        this.overlapping = hits;
        this.popupPoint = point;
        this.mode = 'disambiguating';
        this.notify();
    }

    hover(map: MapLibreMap | null, id: string | null): void {
        if (this.hoveredId === id) return;
        this.clearFeatureState(map, this.hoveredId, 'hovered');
        this.hoveredId = id;
        this.setFeatureState(map, id, { hovered: true });
        this.notify();
    }

    select(map: MapLibreMap | null, feature: RenderFeature | null): void {
        const previous = this.selected ? canonicalFeatureId(this.selected) : null;
        this.clearFeatureState(map, previous, 'selected');
        this.selected = feature;
        this.mode = feature ? 'selected' : 'idle';
        this.overlapping = [];
        this.popupPoint = null;
        const next = feature ? canonicalFeatureId(feature) : null;
        this.setFeatureState(map, next, { selected: true });
        this.notify();
    }

    disambiguate(): void {
        this.mode = 'disambiguating';
        this.notify();
    }

    beginEdit(): void {
        if (this.selected) {
            this.mode = 'editing';
            this.notify();
        }
    }

    cancelEdit(): void {
        this.mode = this.selected ? 'selected' : 'idle';
        this.notify();
    }

    showElevationProfile(): void {
        this.mode = 'elevationProfile';
        this.notify();
    }

    closeElevationProfile(): void {
        this.mode = this.selected ? 'selected' : 'idle';
        this.notify();
    }

    closePopup(): void {
        this.overlapping = [];
        this.popupPoint = null;
        if (this.mode === 'disambiguating') {
            this.mode = this.selected ? 'selected' : 'idle';
        }
        this.notify();
    }

    clear(map?: MapLibreMap | null): void {
        this.clearFeatureState(map ?? null, this.hoveredId, 'hovered');
        const selectedId = this.selected ? canonicalFeatureId(this.selected) : null;
        this.clearFeatureState(map ?? null, selectedId, 'selected');
        this.mode = 'idle';
        this.selected = null;
        this.hoveredId = null;
        this.overlapping = [];
        this.popupPoint = null;
        this.notify();
    }

    private setFeatureState(map: MapLibreMap | null, id: string | null, state: Record<string, boolean>): void {
        if (!map || !id) return;
        map.setFeatureState({ source: GEOJSON_SOURCE_ID, id }, state);
    }

    private clearFeatureState(map: MapLibreMap | null, id: string | null, key: string): void {
        if (!map || !id) return;
        map.removeFeatureState({ source: GEOJSON_SOURCE_ID, id }, key);
    }

    private notify(): void {
        this.revision += 1;
        for (const listener of this.listeners) listener();
    }
}
