export class ImportQueueItem {
    id: number;
    original_filename: string;
    raw_kml_hash: string;
    data: object;
    log: any[];
    timestamp: string;
    processing: boolean;
    feature_count: number;
    imported: boolean;
    processing_failed: boolean;
    duplicate_status: string | null;
    deleting?: boolean;
    deleteProgress?: number;
    deleteError?: string;

    constructor(data: any) {
        this.id = data.id;
        this.original_filename = data.original_filename;
        this.raw_kml_hash = data.raw_kml_hash;
        this.data = data.data;
        this.log = data.log;
        this.timestamp = data.timestamp;
        this.processing = data.processing;
        this.feature_count = data.feature_count;
        this.imported = data.imported || false;
        this.processing_failed = data.processing_failed || false;
        this.duplicate_status = data.duplicate_status || null;
        this.deleting = data.deleting || false;
        this.deleteProgress = data.deleteProgress || 0;
        this.deleteError = data.deleteError || null;
    }
}
