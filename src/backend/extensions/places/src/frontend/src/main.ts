import type { Component } from 'vue';
import type { ExtensionSetupContext } from './types/extension-setup';
import PlacesView from './views/PlacesView.vue';
import PlaceEditView from './views/PlaceEditView.vue';
import PlacesSettings from './PlacesSettings.vue';

/**
 * Uses platform createRouteWrapper so extensionApi/extensionRouter are provided per-route.
 */
async function setup({ router, registry, api, platformState, metadata }: ExtensionSetupContext): Promise<void> {
    registry.registerNavLink({
        label: 'Places',
        path: ''
    });

    const createRouteWrapper = window.gv_core.createRouteWrapper;
    const wrap = (component: Component): Component => createRouteWrapper(component, { api, router, platformState });

    registry.registerSettingsTab({
        id: 'places',
        label: 'Places',
        component: wrap(PlacesSettings),
        icon: metadata.icon
    });

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
