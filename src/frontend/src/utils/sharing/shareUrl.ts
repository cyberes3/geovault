import { parseShareId } from './shareId';

export const MAP_SOCIAL_PREFIX = '/share/map/';
export const TRACK_SOCIAL_PREFIX = '/share/track/';
export const MAP_SPA_PATH = '/mapshare';
export const TRACK_SPA_PATH = '/extensions/live-track/share';

export const PUBLIC_SHARE_PREFIXES = [
    MAP_SPA_PATH,
    MAP_SOCIAL_PREFIX,
    TRACK_SOCIAL_PREFIX,
    TRACK_SPA_PATH,
] as const;

export function mapSocialUrl(shareId: string): string {
    return `${MAP_SOCIAL_PREFIX}${shareId}/`;
}

export function mapSpaUrl(shareId: string): string {
    return `/#/mapshare?id=${shareId}`;
}

export function trackSocialUrl(shareId: string): string {
    return `${TRACK_SOCIAL_PREFIX}${shareId}/`;
}

export function trackSpaUrl(shareId: string): string {
    return `/#/extensions/live-track/share?id=${shareId}`;
}

function uuidFromPrefixedPath(pathname: string, prefix: string): string | null {
    if (!pathname.startsWith(prefix)) {
        return null;
    }
    return parseShareId(pathname.slice(prefix.length).replace(/\/+$/, ''));
}

export function parseMapSocialPath(pathname: string): string | null {
    return uuidFromPrefixedPath(pathname, MAP_SOCIAL_PREFIX);
}

export function parseTrackSocialPath(pathname: string): string | null {
    return uuidFromPrefixedPath(pathname, TRACK_SOCIAL_PREFIX);
}

export function remapPathnameToHash(pathname: string, hashValue = ''): string | null {
    if (hashValue) {
        return null;
    }
    const mapId = parseMapSocialPath(pathname);
    if (mapId) {
        return mapSpaUrl(mapId);
    }
    const trackId = parseTrackSocialPath(pathname);
    if (trackId) {
        return trackSpaUrl(trackId);
    }
    return null;
}
