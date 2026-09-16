import type { GeoJSONSource, Map as MapLibreMap } from 'maplibre-gl';
import type { GeoJsonFeatureCollection } from '@/types/geospatial';
import { GEOJSON_SOURCE_ID } from '@/utils/map/mapLayers';

/**
 * MapLibre `geojson-data` sink. `setData` is coalesced to one write per animation frame.
 */
export class GeoJsonSourceHandle {
    private pending: GeoJsonFeatureCollection | null = null;
    private frame = 0;
    private readonly getMap: () => MapLibreMap | null;
    constructor(getMap: () => MapLibreMap | null, _getShowLabels: () => boolean = () => true) {
        this.getMap = getMap;
    }

    get source(): GeoJSONSource | undefined {
        return this.getMap()?.getSource(GEOJSON_SOURCE_ID) as GeoJSONSource | undefined;
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
        const source = map?.getSource(GEOJSON_SOURCE_ID) as GeoJSONSource | undefined;
        if (!source || !map) return;
        source.setData(collection);
    }

    dispose(): void {
        if (this.frame) {
            cancelAnimationFrame(this.frame);
            this.frame = 0;
        }
        this.pending = null;
    }
}
