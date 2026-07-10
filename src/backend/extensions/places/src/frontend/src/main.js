import PlacesView from './views/PlacesView.vue';
import PlaceEditView from './views/PlaceEditView.vue';
import PlacesSettings from './PlacesSettings.vue';

/**
 * Uses platform createRouteWrapper so extensionApi/extensionRouter are provided per-route.
 */
async function setup({ router, registry, api, metadata }) {
    registry.registerNavLink({
        label: 'Places',
        path: ''
    });

    registry.registerSettingsTab({
        id: 'places',
        label: 'Places',
        component: PlacesSettings,
        icon: metadata?.icon
    });

    const createRouteWrapper = window.gv_core?.createRouteWrapper;
    const wrap = (component) => createRouteWrapper ? createRouteWrapper(component, { api, router }) : component;

    router.addRoute({
        path: '',
        component: wrap(PlacesView),
        name: 'places-list',
        meta: { title: 'Places' },
    });

    router.addRoute({
        path: '/new',
        component: wrap(PlaceEditView),
        name: 'place-new',
        meta: { title: 'New Place' },
    });

    router.addRoute({
        path: '/edit/:id',
        component: wrap(PlaceEditView),
        name: 'place-edit',
        meta: { title: 'Edit Place' },
    });
}

export default setup;
