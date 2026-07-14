/**
 * Ambient types for static image imports (handled by Vite's asset pipeline at build time).
 * This project has no `vite/client` types reference, so these need to be declared explicitly.
 */
declare module '*.svg' {
    const src: string;
    export default src;
}
