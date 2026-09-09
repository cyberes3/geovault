import { fetchConfig } from '@/utils/configService';

let SYSTEM_TAG_PREFIXES: string[] = [];
let initPromise: Promise<void> | null = null;

function initializeSystemTags(): Promise<void> {
    if (initPromise) {
        return initPromise;
    }
    initPromise = fetchConfig()
        .then((config) => {
            SYSTEM_TAG_PREFIXES = config.systemTagPrefixes;
        })
        .catch((error: unknown) => {
            console.error('Error initializing system tags from config:', error);
            SYSTEM_TAG_PREFIXES = [];
        })
        .finally(() => {
            initPromise = null;
        });
    return initPromise;
}

void initializeSystemTags();

/** Protected check after lowercase, matching the backend tag kernel. */
export function isProtectedTag(tag: unknown): boolean {
    if (!tag || typeof tag !== 'string') {
        return false;
    }
    if (SYSTEM_TAG_PREFIXES.length === 0) {
        if (!initPromise) {
            void initializeSystemTags();
        }
        return false;
    }
    const lowerTag = tag.toLowerCase();
    for (const prefix of SYSTEM_TAG_PREFIXES) {
        if (lowerTag === prefix || lowerTag.startsWith(`${prefix}:`)) {
            return true;
        }
    }
    return false;
}

export function isSystemTag(tag: unknown): boolean {
    return isProtectedTag(tag);
}

export function ensureSystemTagsInitialized(): Promise<void> {
    return initializeSystemTags();
}

export function getSystemTagPrefixes(): string[] {
    return [...SYSTEM_TAG_PREFIXES];
}
