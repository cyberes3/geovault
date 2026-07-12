import { httpClient } from '../httpClient';

/** GET /api/admin/users/ - superuser-only user directory for the admin panel. */
export async function listUsers() {
    const response = await httpClient.get('/api/admin/users/');
    return response.data;
}
