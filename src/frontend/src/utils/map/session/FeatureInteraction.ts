import type { InteractionMode } from './types';

export class FeatureInteraction {
    mode: InteractionMode = 'idle';
    selectedId: string | null = null;
    hoveredId: string | null = null;

    get isEditing(): boolean {
        return this.mode === 'editing';
    }

    canHandleClick(): boolean {
        return this.mode !== 'editing';
    }

    select(id: string | null): void {
        this.selectedId = id;
        this.mode = id ? 'selected' : 'idle';
    }

    disambiguate(): void {
        this.mode = 'disambiguating';
    }

    beginEdit(): void {
        if (this.selectedId) {
            this.mode = 'editing';
        }
    }

    cancelEdit(): void {
        this.mode = this.selectedId ? 'selected' : 'idle';
    }

    showElevationProfile(): void {
        this.mode = 'elevationProfile';
    }

    closeElevationProfile(): void {
        this.mode = this.selectedId ? 'selected' : 'idle';
    }

    clear(): void {
        this.mode = 'idle';
        this.selectedId = null;
        this.hoveredId = null;
    }
}
