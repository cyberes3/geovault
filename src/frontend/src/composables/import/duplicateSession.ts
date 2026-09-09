import type { DuplicateCountsWire, DuplicateVerdictWire, SkipIntentWire } from '@/contracts/duplicates';
import { cloneSkipIntent } from '@/contracts/duplicates';

export function isBlockedVerdict(verdict: DuplicateVerdictWire | null | undefined): boolean {
    return verdict?.blocked === true || verdict?.kind === 'hash';
}

export function isRestorableVerdict(verdict: DuplicateVerdictWire | null | undefined): boolean {
    return verdict?.restorable === true || verdict?.kind === 'geometry';
}

export function isDuplicateVerdict(verdict: DuplicateVerdictWire | null | undefined): boolean {
    return isBlockedVerdict(verdict) || isRestorableVerdict(verdict);
}

export function isFeatureSkipped(
    hash: string | undefined,
    verdict: DuplicateVerdictWire | null | undefined,
    intent: SkipIntentWire,
): boolean {
    if (isBlockedVerdict(verdict)) {
        return true;
    }
    if (!hash) {
        return false;
    }
    if (intent.restored.includes(hash)) {
        return false;
    }
    if (intent.skipped.includes(hash)) {
        return true;
    }
    return isRestorableVerdict(verdict);
}

export function toggleSkipIntent(
    intent: SkipIntentWire,
    hash: string,
    verdict: DuplicateVerdictWire | null | undefined,
): SkipIntentWire {
    if (isBlockedVerdict(verdict) || !hash) {
        return cloneSkipIntent(intent);
    }
    const skipped = new Set(intent.skipped);
    const restored = new Set(intent.restored);
    const currentlySkipped = isFeatureSkipped(hash, verdict, intent);
    if (currentlySkipped) {
        skipped.delete(hash);
        if (isRestorableVerdict(verdict)) {
            restored.add(hash);
        }
    } else {
        restored.delete(hash);
        skipped.add(hash);
    }
    return {
        skipped: [...skipped],
        restored: [...restored],
    };
}

export function importableCount(draftCount: number, counts: DuplicateCountsWire, intent: SkipIntentWire): number {
    return Math.max(0, draftCount - counts.hash - intent.skipped.length);
}

export function totalDuplicateCount(counts: DuplicateCountsWire): number {
    return counts.hash + counts.geometry;
}
