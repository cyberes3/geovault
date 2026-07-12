/**
 * Minimal ambient types for the untyped `vue-virtual-scroller` package (no upstream
 * `@types/vue-virtual-scroller`). Only the pieces actually used in this codebase are typed;
 * everything else is intentionally left loose since these are plain Vue 3 components consumed
 * from templates, not from TypeScript code.
 */
declare module 'vue-virtual-scroller' {
    import type { DefineComponent } from 'vue';

    export const RecycleScroller: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>;
    export const DynamicScroller: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>;
    export const DynamicScrollerItem: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>;
}
