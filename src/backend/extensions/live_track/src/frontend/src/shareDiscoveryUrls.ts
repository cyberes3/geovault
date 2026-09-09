export const SHARE_SOURCE_MODES = Object.freeze({
  WORLD: 'world',
  INTERNAL: 'internal'
});

export interface ShareInfo {
  share_access?: string;
  [key: string]: unknown;
}

export function shareInfoUrl(shareId: string): string {
  return `/api/shares/${encodeURIComponent(shareId)}/info/`;
}

export function shareDataUrlForInfo(shareId: string, _info?: ShareInfo | null): string {
  return `/api/shares/${encodeURIComponent(shareId)}/track/`;
}

export function isShareNotAvailableStatus(status: number | string | null | undefined): boolean {
  return [401, 403, 404].includes(Number(status));
}

export interface ShareFetchResult {
  ok: boolean;
  status: number;
  data: unknown;
}

export async function fetchShareJson(url: string): Promise<ShareFetchResult> {
  const response = await fetch(url);
  if (!response.ok) {
    return { ok: false, status: response.status, data: null };
  }
  return { ok: true, status: response.status, data: await response.json() };
}
