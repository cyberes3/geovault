export type MatchMode = 'AND' | 'OR';

export type ViewportSpatial = 'bbox' | 'global';

export interface ViewportKeyInput {
    bbox: [number, number, number, number] | null;
    matchMode?: MatchMode | null;
    tags?: string[] | null;
    shareId?: string | null;
    collectionId?: string | null;
    spatial?: ViewportSpatial;
}

/**
 * Viewport cache identity. `zoom` is omitted on purpose: SQL does not use it.
 */
export class ViewportKey {
    readonly bbox: string;
    readonly matchMode: MatchMode | '';
    readonly tags: string;
    readonly shareId: string;
    readonly collectionId: string;
    readonly spatial: ViewportSpatial;

    constructor(input: ViewportKeyInput) {
        this.bbox = input.bbox ? input.bbox.map((value) => Number(value).toFixed(4)).join(',') : 'global';
        this.matchMode = input.matchMode ?? '';
        this.tags = (input.tags ?? []).map((tag) => tag.trim()).filter(Boolean).sort().join('\u001f');
        this.shareId = input.shareId ?? '';
        this.collectionId = input.collectionId ?? '';
        this.spatial = input.spatial ?? (input.bbox ? 'bbox' : 'global');
    }

    toString(): string {
        return [this.bbox, this.matchMode, this.tags, this.shareId, this.collectionId, this.spatial].join('|');
    }

    static from(input: ViewportKeyInput): string {
        return new ViewportKey(input).toString();
    }
}
