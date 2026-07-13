/**
 * Ambient types for Vite's `import.meta.glob()` (used by `main.js` to build the lazy heroicon
 * resolver - see `resolveExtensionIcon.ts`'s `createHeroiconResolver`). The repo has no `vite/client`
 * types reference, so this needs to be declared explicitly, following the `image-assets.d.ts`
 * precedent for other Vite-specific ambient types.
 */
interface ImportGlobOptions {
    import?: string;
    eager?: boolean;
    query?: string | Record<string, string | number | boolean>;
}

interface ImportMeta {
    glob<T = unknown>(pattern: string | string[], options?: ImportGlobOptions): Record<string, () => Promise<T>>;
}
