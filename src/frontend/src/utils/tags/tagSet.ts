export interface TagSet {
    user: string[];
    system: string[];
}

export function tagSetFromProperties(properties: Record<string, unknown> | null | undefined): TagSet {
    const user = Array.isArray(properties?.tags) ? properties.tags.filter((tag): tag is string => typeof tag === 'string') : [];
    const system = Array.isArray(properties?.system_tags)
        ? properties.system_tags.filter((tag): tag is string => typeof tag === 'string')
        : [];
    return { user, system };
}

export function tagSetUnion(tagSet: TagSet): string[] {
    const seen = new Set<string>();
    const union: string[] = [];
    for (const tag of [...tagSet.user, ...tagSet.system]) {
        if (!seen.has(tag)) {
            seen.add(tag);
            union.push(tag);
        }
    }
    return union;
}
