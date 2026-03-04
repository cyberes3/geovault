import LiveTrackView from './LiveTrackView.vue';
import './assets/main.css';

/**
 * Live Track extension setup. Registers nav "Live", single route.
 * Uses platform createRouteWrapper so extensionApi is provided per-route (avoids overwriting app-level provide).
 */
async function setup({ app, router, registry, api, toast, metadata }) {
  registry.registerNavLink({
    label: 'Live',
    path: ''
  });

  const createRouteWrapper = window.gv_core?.createRouteWrapper;
  router.addRoute({
    path: '',
    component: createRouteWrapper ? createRouteWrapper(LiveTrackView, { api }) : LiveTrackView
  });
}

export default setup;
