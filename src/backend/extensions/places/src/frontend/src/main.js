import PlacesView from './views/PlacesView.vue';
import PlaceDetailView from './views/PlaceDetailView.vue';

async function setup({ app, router, store, registry, api, utils, toast, metadata }) {
    app.provide('placesExtensionApi', api);

    // Register Nav Link
    registry.registerNavLink({
        label: 'Places',
        path: '/places'
    });

    // Register Routes
    router.addRoute({
        path: '/places',
        component: PlacesView,
        name: 'places-list'
    });

    router.addRoute({
        path: '/places/:id',
        component: PlaceDetailView,
        name: 'place-detail'
    });
}

export default setup;
