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
