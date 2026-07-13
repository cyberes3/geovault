import CaltopoSettings from './CaltopoSettings.vue'

async function setup({ router, mainRouter, registry, api, metadata }) {
    const createRouteWrapper = window.gv_core?.createRouteWrapper

    registry.registerSettingsTab({
        id: 'caltopo-extension',
        label: 'CalTopo Integration',
        component: createRouteWrapper
            ? createRouteWrapper(CaltopoSettings, { api, router, mainRouter })
            : CaltopoSettings,
        icon: metadata.icon
    })
}

export default setup
