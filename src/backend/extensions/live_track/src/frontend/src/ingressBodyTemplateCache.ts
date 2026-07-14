/**
 * Fetches /api/extensions/live-track/ingress-body-template/ once per site load
 * and caches the response so multiple components can use it without re-calling.
 */
import type { ExtensionApi } from './types/extension-api';

export interface IngressBodyTemplate {
  body_template: string;
  param_labels: Record<string, string>;
}

let cachedPromise: Promise<IngressBodyTemplate | null> | null = null;

export function getIngressBodyTemplate(api: ExtensionApi | null | undefined): Promise<IngressBodyTemplate | null> {
  if (!api) return Promise.resolve(null);
  if (cachedPromise) return cachedPromise;
  cachedPromise = api
    .get('/ingress-body-template/')
    .then((res) => (res.data ?? null) as IngressBodyTemplate | null)
    .catch(() => null);
  return cachedPromise;
}
