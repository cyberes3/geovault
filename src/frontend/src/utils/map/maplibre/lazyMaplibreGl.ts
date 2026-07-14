/**
 * Lazily loads maplibre-gl (and its CSS) instead of bundling it into the app's eager boot path.
 *
 * MapLibre is the single largest dependency in the app (~1MB / ~275KB gzipped) but is only ever
 * needed once a map actually renders. `main.js` used to `import maplibregl from 'maplibre-gl'`
 * at the top level purely so it could be shared with extensions via `window.gv_core.maplibre` -
 * which forced every page load (settings, tags, admin, ...) to download and parse it before the
 * app could even mount. This module is fetched with a dynamic `import()` instead, and the promise
 * is cached so repeated calls are free.
 *
 * Deliberately NOT called eagerly from `main.js`: an unconditional dynamic import fired at boot
 * gets treated by Vite's build analysis as "needed immediately" and modulepreloaded on every page
 * load anyway, defeating the point. Instead, each map-rendering call site calls this itself, right
 * before it actually needs to create a map - `await window.gv_core.loadMaplibreGl()` - and it also
 * populates `window.gv_core.maplibre`/`window.maplibregl` as a side effect so later callers (or code
 * that only needs synchronous access after the first load) can read the plain object directly.
 */
// maplibre-gl's own type declarations don't declare a `default` export (only named exports), but
// its actual runtime module (CJS-interop) does put the whole namespace behind `.default` - the same
// value `import maplibregl from 'maplibre-gl'` resolves to elsewhere in this codebase. Cast through
// `unknown` since the declared module type has no `default` member for TS to check against.
type MaplibreGlModule = typeof import('maplibre-gl')

let maplibreGlPromise: Promise<MaplibreGlModule> | null = null;

export function loadMaplibreGl(): Promise<MaplibreGlModule> {
    maplibreGlPromise ??= Promise.all([
        import('maplibre-gl'),
        import('maplibre-gl/dist/maplibre-gl.css')
    ]).then(([mod]) => {
        const maplibregl = (mod as unknown as { default: MaplibreGlModule }).default;
        window.gv_core.maplibre = maplibregl;
        window.maplibregl = maplibregl;
        return maplibregl;
    });
    return maplibreGlPromise;
}
