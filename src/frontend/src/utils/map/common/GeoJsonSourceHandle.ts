import type { GeoJSONSource, Map as MapLibreMap } from 'maplibre-gl';
import type { GeoJsonFeatureCollection } from '@/types/geospatial';
import { ensureLayersExist } from '@/utils/map/maplibre/layerManagement.js';

/**
 * MapLibre `geojson-data` sink. `setData` is coalesced to one write per animation frame.
 */
export class GeoJsonSourceHandle {
    private pending: GeoJsonFeatureCollection | null = null;
    private frame = 0;

    constructor(
        private readonly getMap: () => MapLibreMap | null,
        private readonly getShowLabels: () => boolean = () => true,
    ) {}

    get source(): GeoJSONSource | undefined {
        return this.getMap()?.getSource('geojson-data') as GeoJSONSource | undefined;
    }

    setData(collection: GeoJsonFeatureCollection): void {
        this.pending = collection;
        if (this.frame) return;
        this.frame = requestAnimationFrame(() => {
            this.frame = 0;
            this.flush();
        });
    }

    flush(): void {
        if (this.frame) {
            cancelAnimationFrame(this.frame);
            this.frame = 0;
        }
        const collection = this.pending;
        this.pending = null;
        if (!collection) return;
        const map = this.getMap();
        const source = map?.getSource('geojson-data') as GeoJSONSource | undefined;
        if (!source || !map) return;
        source.setData(collection);
        ensureLayersExist(map, this.getShowLabels());
    }

    dispose(): void {
        if (this.frame) {
            cancelAnimationFrame(this.frame);
            this.frame = 0;
        }
        this.pending = null;
    }
}
