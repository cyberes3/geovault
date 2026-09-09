import { fetchConfig } from './configService';
import { isProtectedTag } from './tags/protected';

export { ensureSystemTagsInitialized, getSystemTagPrefixes } from './tags/protected';

export function isSystemTag(tag: unknown): boolean {
  return isProtectedTag(tag);
}

let TAG_PRIORITIES: Record<string, number> = {};
let priorityInitPromise: Promise<void> | null = null;

function initializeTagPriorities(): Promise<void> {
  if (priorityInitPromise) {
    return priorityInitPromise;
  }
  priorityInitPromise = fetchConfig()
    .then((config) => {
      TAG_PRIORITIES = config.tagPriorities;
    })
    .catch((error: unknown) => {
      console.error('Error initializing tag priorities from config:', error);
      TAG_PRIORITIES = {};
    })
    .finally(() => {
      priorityInitPromise = null;
    });
  return priorityInitPromise;
}

void initializeTagPriorities();

/** Filter out system tags from a list of tags. */
export function filterSystemTags(tags: unknown): string[] {
  if (!Array.isArray(tags)) {
    return [];
  }

  return (tags as unknown[]).filter((tag): tag is string => !isSystemTag(tag));
}

/**
 * Get the priority for a tag based on prefix matching.
 * Matches the backend's get_tag_priority logic.
 */
export function getTagPriority(tag: unknown): number {
  if (!tag || typeof tag !== 'string') {
    return 0;
  }

  const tagLower = tag.toLowerCase();

  // Check each prefix in the priorities mapping
  for (const [prefix, priority] of Object.entries(TAG_PRIORITIES)) {
    const prefixLower = prefix.toLowerCase();
    // Exact match
    if (tagLower === prefixLower) {
      return priority;
    }
    // Prefix match (e.g., "type:point" matches "type")
    if (tagLower.startsWith(`${prefixLower}:`)) {
      return priority;
    }
  }

  // No match found, return 0 (lowest priority)
  return 0;
}

/**
 * Sort tags by priority (ascending: 1 first, then 2, ..., then 0), then alphabetically.
 * Tags without an assigned priority get priority 0 (lowest). Use this for system tags.
 */
export function sortTagsByPriority(tags: string[]): string[] {
  if (!Array.isArray(tags)) {
    return [];
  }

  // Create a copy to avoid mutating the original array
  const tagsCopy = [...tags];

  // Sort by priority first (ascending: 1, 2, ..., 10, 0), then alphabetically
  tagsCopy.sort((a, b) => {
    const priorityA = getTagPriority(a);
    const priorityB = getTagPriority(b);

    // First sort by priority
    if (priorityA !== priorityB) {
      // Handle priority 0 specially - it should come after all other priorities
      if (priorityA === 0 && priorityB !== 0) {
        return 1; // a comes after b
      }
      if (priorityB === 0 && priorityA !== 0) {
        return -1; // a comes before b
      }
      return priorityA - priorityB;
    }

    // If priorities are equal, sort alphabetically
    return a.toLowerCase().localeCompare(b.toLowerCase());
  });

  return tagsCopy;
}

/** Sort user tags alphabetically (no priority sorting). */
export function sortUserTagsAlphabetically(tags: string[]): string[] {
  if (!Array.isArray(tags)) {
    return [];
  }

  // Create a copy to avoid mutating the original array
  const tagsCopy = [...tags];

  // Sort alphabetically
  tagsCopy.sort((a, b) => a.toLowerCase().localeCompare(b.toLowerCase()));

  return tagsCopy;
}
