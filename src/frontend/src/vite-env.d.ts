/// <reference types="vite/client" />

/** Side-effect CSS imports (e.g. `import 'maplibre-gl/dist/maplibre-gl.css'`) have no exports to type. */
declare module '*.css' {}

/** Service worker cache-busting version string, injected by `vite.config.js`'s `define`. */
declare const __SW_VERSION__: string;
