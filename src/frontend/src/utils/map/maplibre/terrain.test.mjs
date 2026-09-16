import test from 'node:test';
import assert from 'node:assert/strict';

globalThis.window = { location: { origin: 'http://localhost' } };

const { MapTilerConfig, setupTerrain, setupAtmosphere, removeAtmosphere } = await import('./terrain.ts');
const { createFakeMap } = await import('../test/FakeMap.mjs');

function terrainSources() {
    return [
        { id: 'osm', name: 'OSM', type: 'xyz', hidden: false, client_config: {} },
        {
            id: 'maptiler-terrain',
            name: 'MapTiler Terrain',
            type: 'terrain',
            hidden: true,
            exaggeration: 1.5,
            client_config: {
                type: 'raster-dem',
                tiles: ['/api/tiles/maptiler-terrain/{z}/{x}/{y}'],
            },
        },
        {
            id: 'maptiler-hillshade',
            name: 'MapTiler Hillshade',
            type: 'hillshade',
            hidden: true,
            opacity: 0.3,
            client_config: { type: 'raster' },
        },
    ];
}

const serverConfig = {
    systemTagPrefixes: [],
    tagPriorities: {},
    maptiler: { proxy_tiles: true },
};

function terrainMap() {
    const map = createFakeMap();
    map.isStyleLoaded = () => true;
    map.terrain = null;
    map.sky = null;
    map.setTerrain = (spec) => {
        map.terrain = spec;
    };
    map.setSky = (spec) => {
        map.sky = spec;
    };
    return map;
}

test('MapTilerConfig reads hidden DEM and hillshade sources', async () => {
    const config = new MapTilerConfig();
    await config.fetchConfig(terrainSources(), serverConfig);
    assert.equal(config.isAvailable(), true);
    assert.equal(config.terrainExaggeration, 1.5);
    assert.equal(config.hillshadeOpacity, 0.3);
    assert.equal(config.createTerrainSource()?.type, 'raster-dem');
    assert.equal(config.createTerrainSource()?.encoding, 'mapbox');
});

test('MapTilerConfig is unavailable when the DEM source is omitted', async () => {
    const config = new MapTilerConfig();
    await config.fetchConfig(
        terrainSources().filter((source) => source.id !== 'maptiler-terrain'),
        serverConfig,
    );
    assert.equal(config.isAvailable(), false);
});

test('setupTerrain adds raster-dem and atmosphere sky', async () => {
    const config = new MapTilerConfig();
    await config.fetchConfig(terrainSources(), serverConfig);
    const map = terrainMap();
    await setupTerrain(map, config, true);
    assert.equal(map.getSource('terrain-source')?.type, 'raster-dem');
    assert.deepEqual(map.terrain, { source: 'terrain-source', exaggeration: 1.5 });
    assert.equal(map.sky['fog-color'], '#c0d8f0');
    assert.equal(map.sky['fog-ground-blend'], 0.042);
});

test('setupTerrain uses sky without fog when atmosphere is off', async () => {
    const config = new MapTilerConfig();
    await config.fetchConfig(terrainSources(), serverConfig);
    const map = terrainMap();
    await setupTerrain(map, config, false);
    assert.equal(map.sky['sky-color'], '#80b3ff');
    assert.equal(map.sky['fog-color'], undefined);
});

test('removeAtmosphere clears sky', () => {
    const map = terrainMap();
    setupAtmosphere(map);
    assert.ok(map.sky);
    removeAtmosphere(map);
    assert.equal(map.sky, undefined);
});
