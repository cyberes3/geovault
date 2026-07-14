import { httpClient } from '../httpClient';

export interface AdminUser {
    id: number;
    email: string | null;
    last_activity: string | null;
    date_joined: string | null;
    feature_count: number;
    share_count: number;
    storage_bytes: number;
}

/** GET /api/admin/users/ - superuser-only user directory for the admin panel. */
export async function listUsers(): Promise<AdminUser[]> {
    const response = await httpClient.get<{ users?: AdminUser[] }>('/api/admin/users/');
    return response.data.users ?? [];
}
