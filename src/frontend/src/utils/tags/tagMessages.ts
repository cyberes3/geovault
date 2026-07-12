/**
 * Pure message builders for tag-related confirm/toast copy. Kept free of DOM
 * and network concerns so they're trivial to reuse and test.
 */

/** Confirmation message shown before deleting a tag and all of its features. */
export function buildDeleteTagMessage(tag: string, featureCount: number, isSystemTag = false): string {
    const tagType = isSystemTag ? 'system tag' : 'tag';
    return `Are you sure you want to delete the ${tagType} "${tag}"?\n\nThis will delete ${featureCount} ${featureCount === 1 ? 'feature' : 'features'} from your library. Deleted features cannot be recovered.`;
}

/** Confirmation message shown before removing a tag from a single feature. */
export function buildRemoveTagMessage(tag: string, featureName: string): string {
    return `Are you sure you want to remove the tag "${tag}" from "${featureName}"?`;
}
