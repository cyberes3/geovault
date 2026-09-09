import { tagSetUnion, type TagSet } from './tagSet';

export type TagMatchMode = 'AND' | 'OR';

export interface TagQuery {
    tags: string[];
    matchMode: TagMatchMode;
    prefix: boolean;
}

export function parseMatchMode(raw: string | null | undefined): TagMatchMode {
    const mode = (raw ?? 'AND').toUpperCase();
    if (mode !== 'AND' && mode !== 'OR') {
        throw new Error('match_mode must be either AND or OR');
    }
    return mode;
}

export function tagQueryMatches(query: TagQuery, tagSet: TagSet): boolean {
    const haystack = tagSetUnion(tagSet);
    const checks = query.tags.map((tag) => tagMatches(tag, haystack, query.prefix));
    return query.matchMode === 'AND' ? checks.every(Boolean) : checks.some(Boolean);
}

function tagMatches(needle: string, haystack: string[], prefix: boolean): boolean {
    if (prefix && needle.endsWith(':')) {
        const start = needle.slice(0, -1);
        return haystack.some((item) => item.startsWith(start));
    }
    return haystack.includes(needle);
}
