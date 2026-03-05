/**
 * Fetches /api/extensions/live-track/ingress-body-template/ once per site load
 * and caches the response so multiple components can use it without re-calling.
 */

let cachedPromise = null;

/**
 * @param {{ get: (path: string) => Promise<{ data?: { body_template?: string, param_labels?: Record<string, string> } }> }} api - extension API
 * @returns {Promise<{ body_template: string, param_labels: Record<string, string> } | null>}
 */
export function getIngressBodyTemplate(api) {
  if (!api) return Promise.resolve(null);
  if (cachedPromise) return cachedPromise;
  cachedPromise = api
    .get('/ingress-body-template/')
    .then((res) => res?.data ?? null)
    .catch(() => null);
  return cachedPromise;
}
