import type { ExtensionSetupContext } from '@geovault/extension-sdk'
import CaltopoSettings from './CaltopoSettings.vue'

async function setup({ router, mainRouter, registry, api, metadata }: ExtensionSetupContext): Promise<void> {
    registry.registerSettingsTab({
        id: 'caltopo-extension',
        label: 'CalTopo Integration',
        component: window.gv_core.createRouteWrapper(CaltopoSettings, { api, router, mainRouter }),
        icon: metadata.icon
    })
}

export default setup
