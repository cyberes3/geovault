/**
 * Lazily loads OpenLayers instead of bundling it into the app's eager boot path.
 *
 * OpenLayers is one of the largest dependencies in the app (~630KB / ~178KB gzipped) but is only
 * ever needed by the few places that actually render an OL map/layer (misc preview maps, the
 * exif_geotagger extension's basemap). `main.js` used to `import * as ol from 'ol'` (plus several
 * `ol/*` submodules) at the top level purely so it could be shared with extensions via
 * `window.gv_core.ol`, which forced every page load to download and parse it before the app could
 * even mount. This module is fetched with dynamic `import()`s instead, and the promise is cached
 * so repeated calls are free. See `lazyMaplibreGl.js` for the same pattern applied to MapLibre.
 *
 * Deliberately NOT called eagerly from `main.js` for the same reason `loadMaplibreGl()` isn't:
 * an unconditional dynamic import fired at boot gets treated by Vite's build analysis as "needed
 * immediately" and modulepreloaded on every page load anyway, defeating the point.
 */
let olPromise: Promise<Record<string, unknown>> | null = null;

export function loadOl(): Promise<Record<string, unknown>> {
    olPromise ??= Promise.all([
        import('ol'),
        import('ol/source'),
        import('ol/layer'),
        import('ol/proj'),
        import('ol/geom'),
        import('ol/style'),
        import('ol/interaction'),
        import('ol/Feature')
    ]).then(([ol, source, layer, proj, geom, style, interaction, featureMod]) => {
        const olNamespace = {
            ...ol,
            source,
            layer,
            proj,
            geom,
            style,
            interaction,
            Feature: featureMod.default
        };
        window.gv_core.ol = olNamespace;
        window.ol = olNamespace;
        return olNamespace;
    });
    return olPromise;
}
