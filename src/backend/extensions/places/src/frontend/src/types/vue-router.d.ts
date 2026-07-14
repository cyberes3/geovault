/**
 * Minimal ambient module for `vue-router`. Like `vue`, core externalizes `vue-router` and exposes
 * it as the `window.VueRouter` global at runtime (see `vite.extension-shared.mjs`), but unlike
 * `vue`, this extension has no real `vue-router` npm dependency to resolve types from - only the
 * two composition-API helpers actually used here (`useRoute`, `onBeforeRouteLeave`) are declared.
 */
declare module 'vue-router' {
    export interface RouteLocationLike {
        readonly params: Record<string, string | string[] | undefined>;
    }

    export function useRoute(): RouteLocationLike;

    export type NavigationGuardNext = (valid?: boolean) => void;
    export type NavigationGuard = (to: RouteLocationLike, from: RouteLocationLike, next: NavigationGuardNext) => void;

    export function onBeforeRouteLeave(guard: NavigationGuard): void;
}
