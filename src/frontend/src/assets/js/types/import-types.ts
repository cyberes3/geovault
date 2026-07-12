/** Properties bag of a feature being previewed/edited on the import processing page. */
export interface ImportFeatureProperties {
    name?: string;
    original_name?: string;
    description?: string;
    created?: string | null;
    tags?: string[];
    system_tags?: string[];
    geojson_hash?: string;
    'marker-color'?: string;
    stroke?: string;
    fill?: string;
    'fill-opacity'?: number;
    // Mirrors `ICON_PROPERTY_NAMES` in `@/utils/map/iconUtils.ts` - keep both in sync.
    icon?: string;
    'icon-href'?: string;
    iconUrl?: string;
    icon_url?: string;
    'marker-icon'?: string;
    'marker-symbol'?: string;
    symbol?: string;
    address?: string;
    [key: string]: unknown;
}

/** GeoJSON-ish geometry as sent by the backend for import preview (Point/Line/Polygon coordinates vary in nesting depth). */
export interface ImportFeatureGeometry {
    type: string;
    coordinates: unknown;
}

/**
 * A single feature on the import processing page (an instance of `GeoPoint`/`GeoLineString`/
 * `GeoPolygon`, or a plain object with the same shape while still being parsed).
 */
export interface ImportFeatureItem {
    type?: string;
    id?: string | number;
    geometry: ImportFeatureGeometry;
    properties: ImportFeatureProperties;
    isFeatureStoreHashDup?: boolean;
    isFeatureStoreGeometryDup?: boolean;
    isCrossQueueHashDup?: boolean;
    isCrossQueueGeometryDup?: boolean;
    isDuplicate?: boolean;
    duplicateInfo?: Record<string, unknown>;
}

/** The 4 duplicate-detection lists sent by the backend for a page of features. */
export interface ImportDuplicateSets {
    featureStoreHash: Array<{ page_index: number; hash?: string; feature_store_id?: number }>;
    featureStoreGeometry: Array<{ page_index: number; hash?: string; feature_store_id?: number }>;
    crossQueueHash: Array<{ page_index: number; hash?: string; global_index?: number; queue_item_id?: number; queue_item_filename?: string }>;
    crossQueueGeometry: Array<{ page_index: number; hash?: string; global_index?: number; queue_item_id?: number; queue_item_filename?: string }>;
    indices: number[];
}

export class ImportTableItem {
    id: number;
    original_filename: string;
    raw_file_hash: string;
    data: object;
    log: any[];
    timestamp: string;
    processing: boolean;
    feature_count: number;
    imported: boolean;
    processing_failed: boolean;
    queued: boolean;
    file_duplicate: {
        status: string | null;
        originalFilename: string | null;
    } | null;
    deleting?: boolean;
    deleteProgress?: number;
    deleteError?: string;

    constructor(data: any) {
        this.id = data.id;
        this.original_filename = data.original_filename;
        this.raw_file_hash = data.raw_file_hash;
        this.data = data.data;
        this.log = data.log;
        this.timestamp = data.timestamp;
        this.processing = data.processing;
        this.feature_count = data.feature_count;
        this.imported = data.imported || false;
        this.processing_failed = data.processing_failed || false;
        this.queued = data.queued || false;
        this.file_duplicate = data.file_duplicate || {
            status: null,
            originalFilename: null
        };
        this.deleting = data.deleting || false;
        this.deleteProgress = data.deleteProgress || 0;
        this.deleteError = data.deleteError || null;
    }
}
