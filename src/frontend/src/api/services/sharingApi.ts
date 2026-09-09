import { AxiosHeaders } from 'axios';
import { httpClient } from '../httpClient';
import { normalizeBboxError, parseBboxAxiosResponse, type BboxResponseData } from '@/utils/format/geobuf';
import type { GeoJsonFeatureCollection } from '@/types/geospatial';
import type { ListPage } from '@/contracts/envelope';
import type { CreateSharePayload, PublicShare, ShareListItem, ShareType } from '@/contracts/share';
import { ApiError } from '@/utils/apiError';

export type { CreateSharePayload, ShareType, ShareListItem, PublicShare };

export interface ShareListFilters {
    type?: ShareType;
    tag?: string;
    collection_id?: string;
    feature_id?: string | number;
    audience?: 'world' | 'authenticated';
    page?: number;
    page_size?: number;
}

export async function listSharesPage(filters: ShareListFilters = {}): Promise<ListPage<ShareListItem>> {
    const response = await httpClient.get<ListPage<ShareListItem>>('/api/shares/', { params: filters });
    return response.data;
}

export async function listShares(filters: ShareListFilters = {}): Promise<ShareListItem[]> {
    const items: ShareListItem[] = [];
    let page = 1;
    while (true) {
        const result = await listSharesPage({ ...filters, page, page_size: filters.page_size ?? 100 });
        items.push(...result.items);
        if (page >= result.total_pages) {
            break;
        }
        page += 1;
    }
    return items;
}

export async function getFeatureShare(featureId: string | number): Promise<ShareListItem> {
    const page = await listSharesPage({ type: 'feature', feature_id: featureId, page_size: 1 });
    const item = page.items[0];
    if (!item) {
        throw new ApiError('No share exists for this feature', { status: 404 });
    }
    return item;
}

export async function getShare(shareId: string): Promise<ShareListItem> {
    const response = await httpClient.get<ShareListItem>(`/api/shares/${shareId}/`);
    return response.data;
}

export async function getPublicShareInfo(shareId: string, signal?: AbortSignal): Promise<PublicShare> {
    const response = await httpClient.get<PublicShare>(`/api/shares/${shareId}/info/`, { signal });
    return response.data;
}

async function getShareBboxFeatures(url: string, bboxString: string, zoom: number, signal?: AbortSignal): Promise<BboxResponseData> {
    try {
        const response = await httpClient.get<ArrayBuffer>(`${url}?bbox=${bboxString}&zoom=${Math.round(zoom)}&format=protobuf`, {
            responseType: 'arraybuffer',
            signal,
        });
        const headerRecord = (response.headers as AxiosHeaders).toJSON(true);
        return parseBboxAxiosResponse(headerRecord['content-type'] ?? '', headerRecord, response.data);
    } catch (error) {
        throw normalizeBboxError(error);
    }
}

export async function getPublicShareTagFeatures(shareId: string, bboxString: string, zoom: number, signal?: AbortSignal): Promise<BboxResponseData> {
    return getShareBboxFeatures(`/api/shares/${shareId}/features/`, bboxString, zoom, signal);
}

export async function getPublicShareCollectionFeatures(shareId: string, bboxString: string, zoom: number, signal?: AbortSignal): Promise<BboxResponseData> {
    return getShareBboxFeatures(`/api/shares/${shareId}/features/`, bboxString, zoom, signal);
}

export async function getPublicShareFeature(shareId: string, signal?: AbortSignal): Promise<GeoJsonFeatureCollection> {
    const response = await httpClient.get<GeoJsonFeatureCollection>(`/api/shares/${shareId}/features/`, { signal });
    return response.data;
}

export async function createShare(payload: CreateSharePayload): Promise<ShareListItem> {
    const response = await httpClient.post<ShareListItem>('/api/shares/', payload);
    return response.data;
}

export async function deleteShare(shareId: string): Promise<void> {
    await httpClient.delete(`/api/shares/${shareId}/`);
}

export async function updateShare(shareId: string, fields: Partial<Pick<ShareListItem, 'allow_downloads' | 'include_tags'>>): Promise<ShareListItem> {
    const response = await httpClient.patch<ShareListItem>(`/api/shares/${shareId}/`, fields);
    return response.data;
}

export async function updateFeatureShare(featureId: string | number, fields: Partial<Pick<ShareListItem, 'allow_downloads' | 'include_tags'>>): Promise<ShareListItem> {
    const share = await getFeatureShare(featureId);
    return updateShare(share.share_id, fields);
}

export async function getPublicFeatureElevations(shareId: string, featureRef?: string | number): Promise<{ coordinates?: number[][] } | null> {
    const response = await httpClient.get(`/api/shares/${shareId}/elevations/`, {
        params: featureRef == null ? undefined : { feature_ref: featureRef },
    });
    return response.data as { coordinates?: number[][] } | null;
}
