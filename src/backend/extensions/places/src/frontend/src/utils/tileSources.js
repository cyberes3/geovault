const TILE_SOURCES_API_URL = '/api/tiles/sources/';

export const defaultOsmSource = {
  id: 'osm',
  name: 'OpenStreetMap',
  type: 'osm',
  client_config: {
    url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
    tileSize: 256,
    attribution: '© OpenStreetMap'
  }
};

function normalizeTileArray(tileSource) {
  const clientConfig = tileSource?.client_config || {};
  const url = clientConfig.url || `/api/tiles/${tileSource?.id || 'osm'}/{z}/{x}/{y}`;
  const subdomains = Array.isArray(clientConfig.tileSubdomains) ? clientConfig.tileSubdomains : null;
  if (!subdomains || subdomains.length === 0) {
    return [url.replace('{s}', 'a')];
  }
  return subdomains.map((subdomain) => url.replace('{s}', subdomain));
}

export function isStyleBasedSource(tileSource) {
  const clientConfig = tileSource?.client_config || {};
  return !!(clientConfig.style_url || tileSource?.type === 'maptiler');
}

export function buildRasterSourceSpec(tileSource) {
  const clientConfig = tileSource?.client_config || {};
  return {
    type: 'raster',
    tiles: normalizeTileArray(tileSource),
    tileSize: clientConfig.tileSize || 256,
    attribution: clientConfig.attribution || ''
  };
}

export function buildRasterStyle(tileSource, { sourceId = 'base-raster', layerId = 'base-raster-layer' } = {}) {
  const clientConfig = tileSource?.client_config || {};
  const sourceSpec = buildRasterSourceSpec(tileSource);
  const minzoom = clientConfig.minzoom ?? 0;
  const maxzoom = Math.max(clientConfig.maxzoom ?? 18, 19);
  return {
    version: 8,
    sources: {
      [sourceId]: sourceSpec
    },
    layers: [
      {
        id: layerId,
        type: 'raster',
        source: sourceId,
        minzoom,
        maxzoom
      }
    ]
  };
}

export async function fetchVisibleTileSources(apiUrl = TILE_SOURCES_API_URL) {
  try {
    const response = await fetch(apiUrl, {credentials: 'include'});
    if (!response.ok) {
      throw new Error(`Tile sources HTTP ${response.status}`);
    }
    const data = await response.json();
    if (!Array.isArray(data?.sources)) {
      return [defaultOsmSource];
    }
    const visibleSources = data.sources.filter((source) => !source.hidden);
    if (visibleSources.length === 0) {
      return [defaultOsmSource];
    }
    return visibleSources;
  } catch (e) {
    console.warn('fetchVisibleTileSources: using OSM fallback', e);
    return [defaultOsmSource];
  }
}

export function getTileSourceSelectOptions(tileSources) {
  if (!Array.isArray(tileSources) || tileSources.length === 0) {
    return [{
      id: defaultOsmSource.id,
      name: defaultOsmSource.name,
      type: defaultOsmSource.type
    }];
  }
  return tileSources.map((source) => ({
    id: source.id,
    name: source.name || source.id || 'Unnamed source',
    type: source.type || 'unknown'
  }));
}

export function resolveInitialBaseSource(tileSources, preferredId = 'osm') {
  if (!Array.isArray(tileSources) || tileSources.length === 0) {
    return defaultOsmSource;
  }

  if (preferredId) {
    const preferred = tileSources.find((source) => source.id === preferredId);
    if (preferred) {
      return preferred;
    }
  }

  const osmSource = tileSources.find((source) => source.id === defaultOsmSource.id);
  if (osmSource) {
    return osmSource;
  }

  return tileSources[0];
}

export function resolveEffectiveBaseSourceId(tileSources, preferredId = 'osm') {
  return resolveInitialBaseSource(tileSources, preferredId)?.id || defaultOsmSource.id;
}
