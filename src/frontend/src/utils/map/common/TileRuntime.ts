import type { Map as MapLibreMap } from 'maplibre-gl';
import type { TileSource } from '@/api/services/tilesApi';
import { MapTilerConfig, setupTerrain, removeTerrain, addHillshade, removeHillshade } from '@/utils/map/maplibre/maptilerIntegration.js';
import { resolveMapStyle, MAX_ZOOM_LEVEL } from '@/utils/map/maplibre/mapInitialization.js';
import { tileSourceCatalog } from '@/utils/map/tileSources/sharedCatalog.js';
import { MapRuntime } from './MapRuntime';

/**
 * Basemap + terrain/hillshade. Hillshade is inserted before `polygons`.
 * No client OSM CDN fallback — the catalog is the only source list.
 */
export class TileRuntime {
    sources: TileSource[] = [];
    selectedId = '';
    readonly maptiler = new MapTilerConfig();
    terrainEnabled = false;
    hillshadeEnabled = false;

    async loadCatalog(preferredId?: string): Promise<TileSource[]> {
        this.sources = await tileSourceCatalog.load();
        if (preferredId && this.sources.some((source) => source.id === preferredId)) {
            this.selectedId = preferredId;
        } else if (!this.selectedId || !this.sources.some((source) => source.id === this.selectedId)) {
            this.selectedId = this.sources[0]?.id ?? '';
        }
        await this.maptiler.fetchConfig(this.sources);
        return this.sources;
    }

    selectedSource(): TileSource | undefined {
        return this.sources.find((source) => source.id === this.selectedId);
    }

    resolveStyle() {
        return resolveMapStyle(this.selectedSource());
    }

    async switchBasemap(runtime: MapRuntime, sourceId: string): Promise<void> {
        const source = this.sources.find((item) => item.id === sourceId);
        if (!source) {
            throw new Error(`Tile source not found: ${sourceId}`);
        }
        this.selectedId = sourceId;
        await runtime.setStyle(resolveMapStyle(source));
        runtime.map?.setMaxZoom(MAX_ZOOM_LEVEL);
        await this.applyTerrainAndHillshade(runtime.map);
    }

    async applyTerrainAndHillshade(map: MapLibreMap | null): Promise<void> {
        if (!map || !this.maptiler.isAvailable()) return;
        if (this.terrainEnabled) {
            const name = this.selectedSource()?.name ?? '';
            const atmosphere = name.toLowerCase().includes('imagery') || name.toLowerCase().includes('satellite');
            await setupTerrain(map, this.maptiler, atmosphere);
        } else {
            removeTerrain(map);
        }
        if (this.hillshadeEnabled) {
            addHillshade(map, this.maptiler, 'polygons');
        } else {
            removeHillshade(map);
        }
    }

    setHillshade(map: MapLibreMap | null, enabled: boolean): void {
        this.hillshadeEnabled = enabled;
        if (!map || !this.maptiler.isAvailable()) return;
        if (enabled) {
            addHillshade(map, this.maptiler, 'polygons');
        } else {
            removeHillshade(map);
        }
    }
}
