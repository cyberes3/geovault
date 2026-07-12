import { httpClient } from '../httpClient';

/** GET /api/icons/registry/ - the catalog of built-in + custom icons available to features. */
export async function getIconRegistry() {
    const response = await httpClient.get('/api/icons/registry/');
    return response.data;
}

/** POST /api/icons/upload/ - multipart upload of a custom icon image. */
export async function uploadIcon(file: File) {
    const formData = new FormData();
    formData.append('file', file);
    const response = await httpClient.post('/api/icons/upload/', formData);
    return response.data;
}
