/**
 * Service to fetch and cache server configuration
 */

let cachedConfig = null;
let configPromise = null;

/**
 * Fetch server configuration including system tag prefixes
 * @returns {Promise<{systemTagPrefixes: string[]}>}
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
    configPromise = fetch('/api/config/')
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
            cachedConfig = { systemTagPrefixes: [] };
            return cachedConfig;
        })
        .finally(() => {
            configPromise = null;
        });
    
    return configPromise;
}

/**
 * Clear cached config (useful for testing or forced refresh)
 */
export function clearConfigCache() {
    cachedConfig = null;
    configPromise = null;
}

