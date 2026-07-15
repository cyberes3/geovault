import { httpClient } from '../httpClient';

/** A single icon entry as served by `GET /api/icons/registry/`. */
export interface IconRegistryEntry {
    url: string;
    filename: string;
    style: string;
    base_name?: string;
}

/** Response shape of `GET /api/icons/registry/`. */
export interface IconRegistryResponse {
    points?: IconRegistryEntry[];
    letters?: IconRegistryEntry[];
    recreation?: IconRegistryEntry[];
}

export interface UploadIconResponse {
    icon_url: string;
}

/** GET /api/icons/registry/ - the catalog of built-in + custom icons available to features. */
export async function getIconRegistry(): Promise<IconRegistryResponse> {
    const response = await httpClient.get<IconRegistryResponse>('/api/icons/registry/');
    return response.data;
}

/** POST /api/icons/upload/ - multipart upload of a custom icon image. */
export async function uploadIcon(file: File): Promise<UploadIconResponse> {
    const formData = new FormData();
    formData.append('file', file);
    const response = await httpClient.post<UploadIconResponse>('/api/icons/upload/', formData);
    return response.data;
}
