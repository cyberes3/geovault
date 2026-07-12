import { fetchConfig } from './configService';

// System tag prefixes that identify automatically generated tags.
// Fetched from backend config on initialization
let SYSTEM_TAG_PREFIXES: string[] = [];

// Tag priorities mapping (prefix -> priority 1-10)
// Fetched from backend config on initialization
let TAG_PRIORITIES: Record<string, number> = {};

// Initialize system tags and tag priorities from config
// This is called once when the module loads
let initPromise: Promise<void> | null = null;

/** Initialize system tag prefixes and tag priorities from server config. */
function initializeSystemTags(): Promise<void> {
  if (initPromise) {
    return initPromise;
  }

  initPromise = fetchConfig()
    .then((config) => {
      SYSTEM_TAG_PREFIXES = config.systemTagPrefixes;
      TAG_PRIORITIES = config.tagPriorities;
    })
    .catch((error: unknown) => {
      console.error('Error initializing system tags from config:', error);
      // Fallback to empty array/object if config fetch fails
      SYSTEM_TAG_PREFIXES = [];
      TAG_PRIORITIES = {};
    })
    .finally(() => {
      initPromise = null;
    });

  return initPromise;
}

// Start initialization immediately
void initializeSystemTags();

/**
 * Check if a tag is a system tag (protected tag).
 * Matches the backend's is_protected_tag logic.
 */
export function isSystemTag(tag: unknown): boolean {
  if (!tag || typeof tag !== 'string') {
    return false;
  }

  // If system tags haven't been loaded yet, return false
  // This is a defensive check - in practice, config should load quickly
  if (SYSTEM_TAG_PREFIXES.length === 0) {
    // Try to initialize if not already in progress
    if (!initPromise) {
      void initializeSystemTags();
    }
    return false;
  }

  const lowerTag = tag.toLowerCase();

  for (const prefix of SYSTEM_TAG_PREFIXES) {
    // Exact match
    if (lowerTag === prefix) {
      return true;
    }
    // Prefix match (e.g., "type:point" matches "type")
    if (lowerTag.startsWith(`${prefix}:`)) {
      return true;
    }
  }

  return false;
}

/** Get the current system tag prefixes (for debugging or other uses). */
export function getSystemTagPrefixes(): string[] {
  return [...SYSTEM_TAG_PREFIXES];
}

/** Ensure system tags are initialized (useful for components that need to wait). */
export function ensureSystemTagsInitialized(): Promise<void> {
  return initializeSystemTags();
}

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
