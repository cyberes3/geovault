import PlacesView from './views/PlacesView.vue';
import PlaceNewView from './views/PlaceNewView.vue';

async function setup({app, router, store, registry, api, metadata}) {
    app.provide('extensionApi', api);
    app.provide('extensionRouter', router);

    // Register Nav Link (path '' so fullPath is /extensions/places)
    registry.registerNavLink({
        label: 'Places',
        path: ''
    });

    // Register Routes
    router.addRoute({
        path: '',
        component: PlacesView,
        name: 'places-list'
    });

    router.addRoute({
        path: '/new',
        component: PlaceNewView,
        name: 'place-new'
    });
}

export default setup;
