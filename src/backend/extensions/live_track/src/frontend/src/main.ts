import type { Component } from 'vue';
import type { ExtensionSetupContext } from './types/extension-setup';
import LiveTrackView from './LiveTrackView.vue';
import LiveTrackSettings from './LiveTrackSettings.vue';
import WorldShareView from './WorldShareView.vue';
import './assets/main.css';

/**
 * Live Track extension setup. Registers nav "Live", routes (main view + world share), and settings tab.
 * Every route/settings-tab component is wrapped with `createRouteWrapper` so it gets a scoped
 * `extensionApi`/`platformState` inject, an error boundary, and CSS scoping (see routeWrapper.ts).
 */
async function setup({ router, registry, api, platformState, metadata }: ExtensionSetupContext): Promise<void> {
  registry.registerNavLink({
    label: 'Tracker',
    path: ''
  });

  const createRouteWrapper = window.gv_core.createRouteWrapper;
  const wrap = (component: Component): Component => createRouteWrapper(component, { api, router, platformState }) as Component;

  registry.registerSettingsTab({
    id: 'live-track',
    label: 'Live Tracker',
    component: wrap(LiveTrackSettings),
    icon: metadata.icon
  });

  router.addRoute({
    path: '',
    name: 'live-track',
    meta: { title: 'Tracker' },
    component: wrap(LiveTrackView)
  });
  router.addRoute({
    path: 'share',
    name: 'live-track-share',
    meta: { title: 'Shared' },
    component: wrap(WorldShareView)
  });
}

export default setup;
