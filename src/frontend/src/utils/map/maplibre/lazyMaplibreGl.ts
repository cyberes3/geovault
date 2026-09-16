/**
 * Lazily loads maplibre-gl (CSS + worker URL) instead of bundling it into the eager boot path.
 */
export type MapLibreModule = typeof import('maplibre-gl');

export class MapLibreLoader {
    private promise: Promise<MapLibreModule> | null = null;

    load(): Promise<MapLibreModule> {
        this.promise ??= Promise.all([
            import('maplibre-gl'),
            import('maplibre-gl/dist/maplibre-gl.css'),
            import('maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url'),
        ]).then(([mod, , worker]) => {
            const workerUrl = typeof worker === 'string' ? worker : (worker as { default: string }).default;
            return this.publish(mod, workerUrl);
        });
        return this.promise;
    }

    publish(mod: MapLibreModule, workerUrl: string): MapLibreModule {
        mod.setWorkerUrl(workerUrl);
        window.gv_core.maplibre = mod;
        window.gv_core.map.maplibre = mod;
        window.maplibregl = mod;
        return mod;
    }
}

const loader = new MapLibreLoader();

export function loadMaplibreGl(): Promise<MapLibreModule> {
    return loader.load();
}

export function getLoadedMaplibreGl(): MapLibreModule {
    const maplibregl = window.gv_core?.maplibre as MapLibreModule | null | undefined;
    if (!maplibregl) {
        throw new Error('getLoadedMaplibreGl() called before loadMaplibreGl() resolved');
    }
    return maplibregl;
}

export { loader as mapLibreLoader };
