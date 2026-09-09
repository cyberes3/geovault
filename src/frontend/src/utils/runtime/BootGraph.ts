import { PUBLIC_SHARE_PREFIXES } from '@/utils/sharing/shareUrl';

export function firstPaintPath(hash = typeof window === 'undefined' ? '' : window.location.hash): string {
    return (hash || '').replace(/^#/, '').split('?')[0] || '/';
}

/**
 * Public-share and core first paint mount without waiting on UMD setup().
 * Extension deep-links wait so the route component exists before mount.
 */
export function mountWaitsForExtensions(path: string): boolean {
    if (PUBLIC_SHARE_PREFIXES.some((prefix) => path === prefix || path.startsWith(`${prefix}/`))) {
        return false;
    }
    return path.startsWith('/extensions/');
}
