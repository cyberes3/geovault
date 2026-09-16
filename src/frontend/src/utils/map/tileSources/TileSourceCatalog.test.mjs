import test from 'node:test';
import assert from 'node:assert/strict';

globalThis.window = { location: { origin: 'http://localhost' } };

const { TileSourceCatalog } = await import('./TileSourceCatalog.ts');

function source(id = 'osm') {
    return { id, name: id, type: 'xyz', hidden: false, client_config: {} };
}

test('catalog defaults showAttribution to false and reads show_attribution', async () => {
    const catalog = new TileSourceCatalog({
        apiUrl: '/api/tiles/sources/',
        fetchFn: async () => new Response(JSON.stringify({
            sources: [source()],
            show_attribution: true,
        })),
    });
    assert.equal(catalog.showAttribution, false);
    await catalog.load();
    assert.equal(catalog.showAttribution, true);
    catalog.reset();
    assert.equal(catalog.showAttribution, false);
});

test('catalog ignores a missing or false show_attribution flag', async () => {
    const catalog = new TileSourceCatalog({
        apiUrl: '/api/tiles/sources/',
        fetchFn: async () => new Response(JSON.stringify({
            sources: [source()],
        })),
    });
    await catalog.load();
    assert.equal(catalog.showAttribution, false);
});

test('catalog keeps hidden DEM sources while load() returns visible basemaps', async () => {
    const catalog = new TileSourceCatalog({
        apiUrl: '/api/tiles/sources/',
        fetchFn: async () => new Response(JSON.stringify({
            sources: [
                source('osm'),
                {
                    id: 'maptiler-terrain',
                    name: 'MapTiler Terrain',
                    type: 'terrain',
                    hidden: true,
                    exaggeration: 1.5,
                    client_config: { type: 'raster-dem', encoding: 'mapbox' },
                },
                {
                    id: 'maptiler-hillshade',
                    name: 'MapTiler Hillshade',
                    type: 'hillshade',
                    hidden: true,
                    opacity: 0.3,
                    client_config: { type: 'raster' },
                },
            ],
        })),
    });
    const visible = await catalog.load();
    assert.deepEqual(visible.map((item) => item.id), ['osm']);
    assert.deepEqual(catalog.visibleSources().map((item) => item.id), ['osm']);
    assert.equal(catalog.allSources().some((item) => item.id === 'maptiler-terrain'), true);
    assert.equal(catalog.allSources().some((item) => item.id === 'maptiler-hillshade'), true);
});
