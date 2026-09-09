/**
 * First-party public-share and map prefixes baked from manifests.
 * Seeded before listExtensions so a failed catalog fetch cannot empty guest routes.
 */
export const BAKED_MAP_ROUTE_PREFIXES = [
    '/extensions/places',
    '/extensions/live-track',
];

export const BAKED_PUBLIC_SHARE_ROUTE_PREFIXES = [
    '/extensions/live-track/share',
];
