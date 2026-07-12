import { httpClient } from '../httpClient';

export interface ApiKey {
    id: number;
    name: string;
    key_prefix: string;
    created_at: string;
    last_used_at: string | null;
}

export interface OAuthAuthorizedToken {
    id: number;
    application_name: string;
    created: string;
    last_used_at: string | null;
}

export interface EmailStatusResponse {
    primary_email: string | null;
    pending_verification: string[];
    emails: Array<{ email: string; verified: boolean; primary: boolean }>;
    resend_on_cooldown?: boolean;
    resend_cooldown_remaining?: number;
}

/** GET /api/user/settings/ - current user's settings plus their hidden-feature list. */
export async function getUserSettings() {
    const response = await httpClient.get('/api/user/settings/');
    return response.data as { settings?: Record<string, unknown>; hidden_features?: unknown[] };
}

/** PUT /api/user/settings/update/ - partial nested settings update (e.g. `{map: {zoom: 10}}`). */
export async function updateUserSettings(settingsUpdate: Record<string, unknown>) {
    const response = await httpClient.put('/api/user/settings/update/', settingsUpdate);
    return response.data as { settings?: Record<string, unknown> };
}

/** POST /api/user/settings/hidden-features/clear/ */
export async function clearHiddenFeatures(): Promise<void> {
    await httpClient.post('/api/user/settings/hidden-features/clear/', {});
}

/** POST /api/user/settings/hidden-features/bulk/ */
export async function bulkUpdateHiddenFeatures(add: string[], remove: string[]): Promise<void> {
    await httpClient.post('/api/user/settings/hidden-features/bulk/', { add, remove });
}

/** GET /api/user/email/status/ */
export async function getEmailStatus(): Promise<EmailStatusResponse> {
    const response = await httpClient.get('/api/user/email/status/');
    return response.data;
}

/** POST /api/user/password/change/ - allauth ChangePasswordForm field names. */
export async function changePassword(currentPassword: string, newPassword: string, confirmPassword: string) {
    const response = await httpClient.post('/api/user/password/change/', {
        oldpassword: currentPassword,
        password1: newPassword,
        password2: confirmPassword,
    });
    return response.data as { message?: string };
}

/** POST /api/user/email/resend-verification/ */
export async function resendEmailVerification(email: string) {
    const response = await httpClient.post('/api/user/email/resend-verification/', { email });
    return response.data as { message?: string; cooldown_remaining?: number };
}

/** GET /api/user/api-keys/ */
export async function listApiKeys(): Promise<ApiKey[]> {
    const response = await httpClient.get('/api/user/api-keys/');
    return response.data.api_keys || [];
}

/** POST /api/user/api-keys/create/ */
export async function createApiKey(name: string): Promise<{ raw_key: string }> {
    const response = await httpClient.post('/api/user/api-keys/create/', { name });
    return response.data;
}

/** DELETE /api/user/api-keys/:id/ */
export async function deleteApiKey(keyId: number): Promise<void> {
    await httpClient.delete(`/api/user/api-keys/${keyId}/`);
}

/** GET /api/user/oauth-authorized-tokens/ */
export async function listOAuthTokens(): Promise<OAuthAuthorizedToken[]> {
    const response = await httpClient.get('/api/user/oauth-authorized-tokens/');
    return response.data.authorized_tokens || [];
}

/** DELETE /api/user/oauth-authorized-tokens/:id/ */
export async function revokeOAuthToken(tokenId: number): Promise<void> {
    await httpClient.delete(`/api/user/oauth-authorized-tokens/${tokenId}/`);
}

/** GET /api/user/storage/usage/ - supports an AbortSignal for the dashboard's fetch timeout. */
export async function getStorageUsage(signal?: AbortSignal) {
    const response = await httpClient.get('/api/user/storage/usage/', { signal });
    return response.data;
}
