import { httpClient } from '@/api/httpClient';
import { getApiErrorMessage } from '@/utils/apiError';

export interface ServerConfig {
    systemTagPrefixes: string[];
    tagPriorities: Record<string, number>;
    maptiler?: { proxy_tiles?: boolean; apiKey?: string | null };
    [key: string]: unknown;
}

let cachedConfig: ServerConfig | null = null;
let configPromise: Promise<ServerConfig> | null = null;

/**
 * Fetch server configuration (system tag prefixes, tag priorities, maptiler settings, etc.),
 * caching the result for the lifetime of the page.
 */
export async function fetchConfig(): Promise<ServerConfig> {
    if (cachedConfig) {
        return cachedConfig;
    }
    if (configPromise) {
        return configPromise;
    }

    configPromise = httpClient
        .get<ServerConfig>('/api/config/')
        .then((response) => {
            cachedConfig = response.data;
            return cachedConfig;
        })
        .catch((error) => {
            console.error('Error fetching config:', getApiErrorMessage(error));
            cachedConfig = { systemTagPrefixes: [], tagPriorities: {} };
            return cachedConfig;
        })
        .finally(() => {
            configPromise = null;
        });

    return configPromise;
}

/** Clear cached config (useful for testing or forced refresh). */
export function clearConfigCache(): void {
    cachedConfig = null;
    configPromise = null;
}
