/**
 * Ambient module declarations for the shared UI parts the core app exposes to extensions under
 * the `platform/components/...` specifier (see `vite.extension-shared.mjs`: at build time these
 * resolve to `window.gv_core.*` globals rather than being bundled, so there's no real module to
 * point `vue-tsc` at). Declared loosely as generic Vue components; extensions only need enough
 * typing here to satisfy the compiler, not full prop-level checking of core-owned components.
 */
declare module 'platform/components/parts/*.vue' {
    import type { DefineComponent } from 'vue';
    const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>;
    export default component;
}

declare module 'platform/components/settings/components/*.vue' {
    import type { DefineComponent } from 'vue';
    const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>;
    export default component;
}
