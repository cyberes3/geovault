import CaltopoSettings from './CaltopoSettings.vue'

async function setup({app, router, mainRouter, store, registry, api, metadata}) {
    app.provide('caltopoExtensionApi', api)
    app.provide('caltopoExtensionRouter', router)
    app.provide('caltopoExtensionMainRouter', mainRouter)

    registry.registerSettingsTab({
        id: 'caltopo-extension',
        label: 'CalTopo Integration',
        component: CaltopoSettings,
        icon: metadata.icon
    })
}

export default setup
