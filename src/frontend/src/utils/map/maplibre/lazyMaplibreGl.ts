/**
 * Lazily loads maplibre-gl (CSS + worker URL) instead of bundling it into the eager boot path.
 */
import { describeError, mapBootError, mapBootLog } from '@/utils/map/mapBootLog';

export type MapLibreModule = typeof import('maplibre-gl');

export class MapLibreLoader {
    private promise: Promise<MapLibreModule> | null = null;

    load(): Promise<MapLibreModule> {
        this.promise ??= (() => {
            mapBootLog('maplibre:load:start');
            return Promise.all([
                import('maplibre-gl'),
                import('maplibre-gl/dist/maplibre-gl.css'),
                import('maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url'),
            ]).then(([mod, , worker]) => {
                const workerUrl = typeof worker === 'string' ? worker : (worker as { default: string }).default;
                const published = this.publish(mod, workerUrl);
                mapBootLog('maplibre:load:done', { version: published.getVersion?.() ?? null });
                return published;
            }).catch((error: unknown) => {
                mapBootError('maplibre:load', { error: describeError(error) });
                throw error;
            });
        })();
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
