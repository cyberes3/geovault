import type { DuplicateVerdictWire } from '@/contracts/duplicates';
import type { ImportFeatureItem } from '@/assets/js/types/import-types';
import { isBlockedVerdict, isDuplicateVerdict } from '@/composables/import/duplicateSession';

export function isItemDuplicate(item: ImportFeatureItem | null | undefined): boolean {
    return isDuplicateVerdict(item?.duplicate_verdict);
}

export function isItemHashDuplicate(item: ImportFeatureItem | null | undefined): boolean {
    return isBlockedVerdict(item?.duplicate_verdict);
}

export function getFeatureId(item: ImportFeatureItem | null | undefined, index: number, currentPage: number, pageSize: number): string {
    if (item?.properties.geojson_hash) {
        return item.properties.geojson_hash;
    }
    const globalIndex = (currentPage - 1) * pageSize + index;
    return `index_${globalIndex}`;
}

export function featureVerdict(item: ImportFeatureItem | null | undefined): DuplicateVerdictWire | undefined {
    return item?.duplicate_verdict;
}
