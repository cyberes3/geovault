import { httpClient } from '../httpClient';
import { getCookie } from '@/utils/cookies';

/**
 * POST /accounts/logout/ - django-allauth logout view. Expects the CSRF token as
 * `csrfmiddlewaretoken` in an url-encoded form body (not the JSON body the rest of the
 * API uses), so this bypasses the shared client's default JSON content type.
 */
export async function logout(): Promise<void> {
    const csrfToken = getCookie('csrftoken') || '';
    const formData = new URLSearchParams();
    formData.append('csrfmiddlewaretoken', csrfToken);

    await httpClient.post('/accounts/logout/', formData.toString(), {
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded',
        },
    });
}
