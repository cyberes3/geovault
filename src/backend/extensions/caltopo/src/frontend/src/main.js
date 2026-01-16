import CaltopoSettings from './CaltopoSettings.vue'


export async function setup({app, router, mainRouter, store, registry, api, utils, toast, metadata}) {
    console.log(`[${metadata.name}] Initializing extension v${metadata.version}`)

    app.provide('caltopoExtensionApi', api)
    app.provide('caltopoExtensionToast', toast)
    app.provide('caltopoExtensionRouter', router)
    app.provide('caltopoExtensionMainRouter', mainRouter)

    registry.registerSettingsTab({
        id: 'caltopo-extension',
        label: 'CalTopo Integration',
        component: CaltopoSettings
    })
}
