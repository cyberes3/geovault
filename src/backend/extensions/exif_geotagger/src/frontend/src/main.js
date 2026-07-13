import Geotagger from './Geotagger.vue';

async function setup({ router, registry, api }) {
    registry.registerTool({
        label: 'Geotagger',
        path: '/'
    });

    router.addRoute({
        path: '/',
        name: 'exif-geotagger',
        meta: { title: 'Photo Geotagger' },
        component: window.gv_core.createRouteWrapper(Geotagger, { api, router })
    });
}

export default setup;
