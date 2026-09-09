import { httpClient } from '@/api/httpClient';
import { ApiError } from '@/utils/apiError';

export interface DownloadKmzParams {
    share?: string;
    feature_ref?: string | number;
    tag?: string;
    collection?: string;
    all?: boolean;
}

function filenameFromDisposition(header: string | undefined): string {
    const match = header ? /filename="?([^"]+)"?/.exec(header) : null;
    return match?.[1] || 'export.kmz';
}

export async function downloadKmz(params: DownloadKmzParams): Promise<void> {
    const query: Record<string, string> = {};
    if (params.share) {
        query.share = params.share;
    }
    if (params.feature_ref != null) {
        query.feature = String(params.feature_ref);
    }
    if (params.tag) {
        query.tag = params.tag;
    }
    if (params.collection) {
        query.collection = params.collection;
    }
    if (params.all) {
        query.all = 'true';
    }

    const response = await httpClient.get<Blob>('/api/export-kmz', {
        params: query,
        responseType: 'blob',
    });
    const blob = response.data;
    if (!(blob instanceof Blob)) {
        throw new ApiError('Failed to download KMZ');
    }
    const filename = filenameFromDisposition(response.headers['content-disposition'] as string | undefined);
    const objectUrl = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = objectUrl;
    link.download = filename;
    link.click();
    URL.revokeObjectURL(objectUrl);
}
