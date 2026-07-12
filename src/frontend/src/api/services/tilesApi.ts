import { httpClient } from '../httpClient';

export interface TileSourceClientConfig {
    type?: string;
    style_url?: string;
    url?: string;
    tileSubdomains?: string[];
    tileSize?: number;
    attribution?: string;
    minzoom?: number;
    maxzoom?: number;
    [key: string]: unknown;
}

export interface TileSource {
    id: string;
    name: string;
    type: string;
    requires_proxy: boolean;
    hidden?: boolean;
    exaggeration?: number;
    opacity?: number;
    client_config: TileSourceClientConfig;
}

export interface TileSourcesResponse {
    sources: TileSource[];
}

/** GET /api/tiles/sources/ - available basemap/terrain/hillshade tile sources. */
export async function getTileSources(): Promise<TileSourcesResponse> {
    const response = await httpClient.get<TileSourcesResponse>('/api/tiles/sources/');
    return response.data;
}
