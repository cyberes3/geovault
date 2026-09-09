export type TagKind = 'user' | 'system';

export interface TagCatalogEntry {
    name: string;
    kind: TagKind;
    count: number;
}

export interface TagFeatureRef {
    id: number;
    name: string;
    geometry_type: string;
}

export interface TagRenamePayload {
    new_name: string;
}
