export type VerdictKind = 'none' | 'hash' | 'geometry';
export type VerdictScope = 'none' | 'library' | 'draft_queue';

export interface DuplicateVerdictWire {
    kind: VerdictKind;
    scope: VerdictScope;
    blocked: boolean;
    restorable: boolean;
    match_feature_store_id: number | null;
    match_queue_id: number | null;
    match_spatial_index: number | null;
    match_name: string;
    match_type: string;
}

export interface SkipIntentWire {
    skipped: string[];
    restored: string[];
}

export interface DuplicateCountsWire {
    hash: number;
    geometry: number;
}

export function emptyVerdict(): DuplicateVerdictWire {
    return {
        kind: 'none',
        scope: 'none',
        blocked: false,
        restorable: false,
        match_feature_store_id: null,
        match_queue_id: null,
        match_spatial_index: null,
        match_name: '',
        match_type: '',
    };
}

export function emptySkipIntent(): SkipIntentWire {
    return { skipped: [], restored: [] };
}

export function emptyDuplicateCounts(): DuplicateCountsWire {
    return { hash: 0, geometry: 0 };
}

export function parseVerdict(raw: unknown): DuplicateVerdictWire {
    if (!raw || typeof raw !== 'object') {
        return emptyVerdict();
    }
    const value = raw as Partial<DuplicateVerdictWire>;
    return {
        kind: value.kind === 'hash' || value.kind === 'geometry' ? value.kind : 'none',
        scope: value.scope === 'library' || value.scope === 'draft_queue' ? value.scope : 'none',
        blocked: value.blocked === true || value.kind === 'hash',
        restorable: value.restorable === true || value.kind === 'geometry',
        match_feature_store_id: typeof value.match_feature_store_id === 'number' ? value.match_feature_store_id : null,
        match_queue_id: typeof value.match_queue_id === 'number' ? value.match_queue_id : null,
        match_spatial_index: typeof value.match_spatial_index === 'number' ? value.match_spatial_index : null,
        match_name: typeof value.match_name === 'string' ? value.match_name : '',
        match_type: typeof value.match_type === 'string' ? value.match_type : '',
    };
}

export function parseSkipIntent(raw: unknown): SkipIntentWire {
    if (!raw || typeof raw !== 'object') {
        return emptySkipIntent();
    }
    const value = raw as Partial<SkipIntentWire>;
    return {
        skipped: Array.isArray(value.skipped) ? value.skipped.filter((item): item is string => typeof item === 'string') : [],
        restored: Array.isArray(value.restored) ? value.restored.filter((item): item is string => typeof item === 'string') : [],
    };
}

export function parseDuplicateCounts(raw: unknown): DuplicateCountsWire {
    if (!raw || typeof raw !== 'object') {
        return emptyDuplicateCounts();
    }
    const value = raw as Partial<DuplicateCountsWire>;
    return {
        hash: typeof value.hash === 'number' ? value.hash : 0,
        geometry: typeof value.geometry === 'number' ? value.geometry : 0,
    };
}

export function cloneSkipIntent(intent: SkipIntentWire): SkipIntentWire {
    return {
        skipped: [...intent.skipped],
        restored: [...intent.restored],
    };
}

export function skipIntentEqual(left: SkipIntentWire, right: SkipIntentWire): boolean {
    const leftSkipped = [...left.skipped].sort();
    const rightSkipped = [...right.skipped].sort();
    const leftRestored = [...left.restored].sort();
    const rightRestored = [...right.restored].sort();
    return (
        leftSkipped.length === rightSkipped.length &&
        leftRestored.length === rightRestored.length &&
        leftSkipped.every((hash, index) => hash === rightSkipped[index]) &&
        leftRestored.every((hash, index) => hash === rightRestored[index])
    );
}
