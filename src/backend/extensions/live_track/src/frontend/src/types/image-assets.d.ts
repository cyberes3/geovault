/**
 * Ambient types for static image imports (handled by Vite's asset pipeline at build time).
 */
declare module '*.svg' {
    const src: string;
    export default src;
}

declare module '*.png' {
    const src: string;
    export default src;
}
