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
