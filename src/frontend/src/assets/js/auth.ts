import { httpClient } from '@/api/httpClient';
import { ApiError } from '@/utils/apiError';

export class UserStatus {
    authorized: boolean;
    email: string | null;
    id: number | null;
    featureCount: number;
    tags: Array<{ tag: string; count: number }>;
    isSuperuser: boolean;

    constructor(
        authorized: boolean,
        email: string | null,
        id: number | null,
        featureCount = 0,
        tags: Array<{ tag: string; count: number }> = [],
        isSuperuser = false,
    ) {
        this.authorized = authorized;
        this.email = email;
        this.id = id;
        this.featureCount = featureCount;
        this.tags = tags;
        this.isSuperuser = isSuperuser;
    }
}

// Cache for in-flight getUserInfo requests to prevent duplicate concurrent calls.
let getUserInfoPromise: Promise<UserStatus | null> | null = null;

export async function getUserInfo(): Promise<UserStatus | null> {
    if (getUserInfoPromise) {
        return getUserInfoPromise;
    }

    getUserInfoPromise = (async () => {
        try {
            const response = await httpClient.get('/api/user/status/');
            const userStatusData = response.data;

            const processedTags = Array.isArray(userStatusData.tags)
                ? userStatusData.tags.map((tagObj: { tag: string; count: number }) => ({
                    tag: tagObj.tag,
                    count: tagObj.count,
                }))
                : [];

            return new UserStatus(
                userStatusData.authorized,
                userStatusData.email,
                userStatusData.id,
                userStatusData.featureCount,
                processedTags,
                userStatusData.is_superuser || false,
            );
        } catch (error) {
            const apiError = ApiError.from(error);
            if (apiError.status === 401) {
                return new UserStatus(false, null, null, 0, [], false);
            }
            console.error(apiError);
            return null;
        } finally {
            getUserInfoPromise = null;
        }
    })();

    return getUserInfoPromise;
}
