import type { VaultFeature } from '@/contracts/feature';

export function canonicalFeatureId(feature: { properties?: { database_id?: string | number | null; feature_ref?: string | number | null } } | null | undefined): string | null {
    const id = feature?.properties?.database_id ?? feature?.properties?.feature_ref;
    if (id == null || id === '') return null;
    return String(id);
}

export function isSyntheticFeature(feature: { properties?: object } | null | undefined): boolean {
    const properties = feature?.properties as { _isLabelPoint?: unknown; _isSmallFeatureReplacement?: unknown } | undefined;
    return !!properties?._isLabelPoint || !!properties?._isSmallFeatureReplacement;
}

export function originalFeatureId(feature: { properties?: object } | null | undefined): string | null {
    const original = (feature?.properties as { _originalFeatureId?: unknown } | undefined)?._originalFeatureId;
    if (original == null || original === '') return null;
    return String(original);
}

export function cloneFeature<T extends VaultFeature>(feature: T): T {
    return {
        ...feature,
        geometry: { ...feature.geometry },
        properties: { ...feature.properties },
    };
}

export function stripRuntimeProperties(properties: Record<string, unknown>): Record<string, unknown> {
    const next: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(properties)) {
        if (key.startsWith('_')) continue;
        next[key] = value;
    }
    return next;
}
