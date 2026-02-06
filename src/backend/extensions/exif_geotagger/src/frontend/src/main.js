import Geotagger from './Geotagger.vue';

async function setup({ app, router, registry, api }) {
    // Register as a tool in the "Tools" dropdown
    registry.registerTool({
        label: 'Geotagger',
        path: '/'
    });

    router.addRoute({
        path: '/',
        component: Geotagger
    });
}

export default setup;
