import Geotagger from './Geotagger.vue';

async function setup({ app, router, registry, api }) {
    registry.registerTool({
        label: 'Geotagger',
        path: '/'
    });

    router.addRoute({
        path: '/',
        name: 'exif-geotagger',
        meta: { title: 'Photo Geotagger' },
        component: Geotagger
    });
}

export default setup;
