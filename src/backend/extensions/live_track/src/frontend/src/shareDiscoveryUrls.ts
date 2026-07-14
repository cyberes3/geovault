export const SHARE_SOURCE_MODES = Object.freeze({
  WORLD: 'world',
  INTERNAL: 'internal'
});

const SHARE_INFO_BASE_URL = '/api/extensions/live-track/share';
const WORLD_SHARE_BASE_URL = '/api/extensions/live-track/world/share';
const INTERNAL_SHARE_BASE_URL = '/api/extensions/live-track/internal/share';

export function shareInfoUrl(shareId) {
  return `${SHARE_INFO_BASE_URL}/${encodeURIComponent(shareId)}/info/`;
}

export function shareDataUrlForInfo(shareId, info) {
  const sourceBaseUrl = info?.share_access === SHARE_SOURCE_MODES.INTERNAL
    ? INTERNAL_SHARE_BASE_URL
    : WORLD_SHARE_BASE_URL;
  return `${sourceBaseUrl}/${encodeURIComponent(shareId)}/`;
}

export function isShareNotAvailableStatus(status) {
  return [401, 403, 404].includes(Number(status));
}
