/**
 * Service to fetch and cache server configuration
 */

let cachedConfig = null;
let configPromise = null;

/**
 * Fetch server configuration including protected tags
 * @returns {Promise<{protectedTags: string[]}>}
 */
export async function fetchConfig() {
    // Return cached config if available
    if (cachedConfig) {
        return cachedConfig;
    }
    
    // Return existing promise if fetch is in progress
    if (configPromise) {
        return configPromise;
    }
    
    // Fetch config from server
    configPromise = fetch('/api/data/config/')
        .then(response => {
            if (!response.ok) {
                throw new Error(`Failed to fetch config: ${response.status}`);
            }
            return response.json();
        })
        .then(data => {
            cachedConfig = data;
            return data;
        })
        .catch(error => {
            console.error('Error fetching config:', error);
            // Return default empty array on error
            cachedConfig = { protectedTags: [] };
            return cachedConfig;
        })
        .finally(() => {
            configPromise = null;
        });
    
    return configPromise;
}

/**
 * Get protected tags (from cache or fetch if needed)
 * @returns {Promise<string[]>}
 */
export async function getProtectedTags() {
    const config = await fetchConfig();
    return config.protectedTags || [];
}

/**
 * Clear cached config (useful for testing or forced refresh)
 */
export function clearConfigCache() {
    cachedConfig = null;
    configPromise = null;
}

