import type { Component } from 'vue';
import type { ExtensionSetupContext } from '@geovault/extension-sdk';
import ExamplePage from './ExamplePage.vue';
import ExampleSettings from './ExampleSettings.vue';
import './assets/main.css';

async function setup({ router, registry, api, platformState, metadata }: ExtensionSetupContext): Promise<void> {
    const createRouteWrapper = window.gv_core.createRouteWrapper;
    const wrap = (component: Component, routeName: string): Component =>
        createRouteWrapper(component, { api, router, platformState, routeName });

    registry.registerNavLink({
        label: 'Example Ext',
        path: '/page'
    });

    registry.registerSettingsTab({
        id: 'settings',
        label: 'Example Extension',
        component: wrap(ExampleSettings, 'settings'),
        icon: metadata.icon
    });

    router.addRoute({
        path: '/page',
        name: 'example-extension-page',
        meta: { title: 'Example Extension' },
        component: wrap(ExamplePage, 'example-extension-page')
    });
}

export default setup;
