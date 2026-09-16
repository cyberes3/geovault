import type { Map as MapLibreMap, SourceSpecification } from 'maplibre-gl';
import { fetchConfig as fetchCachedConfig, type ServerConfig } from '@/utils/configService';
import type { TileSource, TileSourceClientConfig } from '@/api/services/tilesApi';

export class MapTilerConfig {
    apiKey: string | null = null;
    useProxy = false;
    terrainSource: TileSourceClientConfig | null = null;
    hillshadeSource: TileSourceClientConfig | null = null;
    terrainExaggeration = 1.5;
    hillshadeOpacity = 0.3;

    async fetchConfig(tileSources: TileSource[] | null = null, serverConfig: ServerConfig | null = null): Promise<boolean> {
        try {
            const config = serverConfig ?? await fetchCachedConfig();
            if (!config.maptiler) return false;

            this.useProxy = config.maptiler.proxy_tiles ?? false;
            this.apiKey = config.maptiler.apiKey ?? null;

            let sources = tileSources;
            if (!sources) {
                const tilesResponse = await fetch('/api/tiles/sources/');
                const tilesData: unknown = await tilesResponse.json();
                sources = ((tilesData as { sources?: TileSource[] }).sources) ?? [];
            }

            for (const source of sources) {
                if (source.id === 'maptiler-terrain') {
                    this.terrainSource = source.client_config;
                    this.terrainExaggeration = source.exaggeration ?? 1.5;
                } else if (source.id === 'maptiler-hillshade') {
                    this.hillshadeSource = source.client_config;
                    this.hillshadeOpacity = source.opacity ?? 0.3;
                }
            }
            return true;
        } catch (error) {
            console.error('Error fetching MapTiler config:', error);
            this.apiKey = null;
            this.useProxy = false;
            this.terrainSource = null;
            this.hillshadeSource = null;
            return false;
        }
    }

    isAvailable(): boolean {
        return (this.apiKey !== null || this.useProxy) && this.terrainSource !== null;
    }

    createTerrainSource(): TileSourceClientConfig | null {
        return this.terrainSource;
    }

    createHillshadeSource(): TileSourceClientConfig | null {
        return this.hillshadeSource;
    }
}

export function setupSky(map: MapLibreMap | null | undefined): void {
    if (!map) return;
    map.setSky({
        'sky-color': '#80b3ff',
        'sky-horizon-blend': 0.2,
        'horizon-color': '#d1e7ff',
    });
}

export function setupAtmosphere(map: MapLibreMap | null | undefined): void {
    if (!map) return;
    map.setSky({
        'sky-color': '#80b3ff',
        'sky-horizon-blend': 0.5,
        'horizon-color': '#d1e7ff',
        'horizon-fog-blend': 0.21,
        'fog-color': '#c0d8f0',
        'fog-ground-blend': 0.042,
    });
}

export function removeAtmosphere(map: MapLibreMap | null | undefined): void {
    setupSky(map);
}

export async function setupTerrain(map: MapLibreMap | null | undefined, config: MapTilerConfig, applyAtmosphere = true): Promise<void> {
    if (!map || !config.isAvailable()) return;

    if (map.getSource('terrain-source')) {
        map.setTerrain(null);
        map.removeSource('terrain-source');
    }

    const terrainSource = config.createTerrainSource();
    if (!terrainSource) return;
    map.addSource('terrain-source', terrainSource as unknown as SourceSpecification);
    map.setTerrain({
        source: 'terrain-source',
        exaggeration: config.terrainExaggeration,
    });

    removeAtmosphere(map);
    if (applyAtmosphere) {
        setupAtmosphere(map);
    } else {
        setupSky(map);
    }
}

export function removeTerrain(map: MapLibreMap | null | undefined): void {
    if (!map) return;
    removeAtmosphere(map);
    map.setTerrain(null);
    if (map.getSource('terrain-source')) {
        map.removeSource('terrain-source');
    }
}

export function addHillshade(map: MapLibreMap | null | undefined, config: MapTilerConfig, beforeLayer = 'polygons'): void {
    if (!map || !config.isAvailable()) return;
    if (map.getLayer('hillshade-layer')) return;

    if (!map.getSource('hillshade-source')) {
        const hillshadeSource = config.createHillshadeSource();
        if (!hillshadeSource) return;
        map.addSource('hillshade-source', hillshadeSource as unknown as SourceSpecification);
    }

    const layerConfig = {
        id: 'hillshade-layer',
        type: 'raster' as const,
        source: 'hillshade-source',
        paint: {
            'raster-opacity': config.hillshadeOpacity,
        },
    };

    if (map.getLayer(beforeLayer)) {
        map.addLayer(layerConfig, beforeLayer);
    } else {
        map.addLayer(layerConfig);
    }
}

export function removeHillshade(map: MapLibreMap | null | undefined): void {
    if (!map) return;
    if (map.getLayer('hillshade-layer')) {
        map.removeLayer('hillshade-layer');
    }
    if (map.getSource('hillshade-source')) {
        map.removeSource('hillshade-source');
    }
}
