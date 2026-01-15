import CaltopoSettings from './CaltopoSettings.vue'

/**
 * ==============================================================================
 * CalTopo Extension Frontend Setup
 * ==============================================================================
 * This 'setup' function is the main entry point for the CalTopo extension frontend.
 */
export async function setup({ app, router, mainRouter, store, registry, api, utils, toast, metadata }) {
    console.log(`[${metadata.name}] Initializing extension v${metadata.version}`)

    // Provide extension services to child components
    app.provide('caltopoExtensionApi', api)
    app.provide('caltopoExtensionToast', toast)
    app.provide('caltopoExtensionRouter', router)
    app.provide('caltopoExtensionMainRouter', mainRouter)  // Main platform router for navigation

    // Register CalTopo settings tab
    registry.registerSettingsTab({
        id: 'caltopo-extension',
        label: 'CalTopo Integration',
        component: CaltopoSettings
    })
}
