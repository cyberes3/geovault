import LiveTrackView from './LiveTrackView.vue';
import LiveTrackSettings from './LiveTrackSettings.vue';
import WorldShareView from './WorldShareView.vue';
import './assets/main.css';

/**
 * Live Track extension setup. Registers nav "Live", routes (main view + world share), and settings tab.
 * Uses platform createRouteWrapper so extensionApi is provided per-route (avoids overwriting app-level provide).
 */
async function setup({ app, router, registry, api, toast, metadata }) {
  registry.registerNavLink({
    label: 'Tracker',
    path: ''
  });

  registry.registerSettingsTab({
    id: 'live-track',
    label: 'Live Tracker',
    component: LiveTrackSettings,
    icon: metadata?.icon
  });

  const createRouteWrapper = window.gv_core?.createRouteWrapper;
  router.addRoute({
    path: '',
    component: createRouteWrapper ? createRouteWrapper(LiveTrackView, { api }) : LiveTrackView
  });
  router.addRoute({
    path: 'share',
    component: WorldShareView
  });
}

export default setup;
