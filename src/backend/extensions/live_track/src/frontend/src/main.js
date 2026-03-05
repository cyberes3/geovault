import LiveTrackView from './LiveTrackView.vue';
import LiveTrackSettings from './LiveTrackSettings.vue';
import './assets/main.css';

/**
 * Live Track extension setup. Registers nav "Live", single route, and settings tab.
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
}

export default setup;
