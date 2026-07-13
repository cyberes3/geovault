import { fileURLToPath } from 'node:url'
import { createExtensionViteConfig } from '../../../../../frontend/vite.extension-shared.mjs'

export default createExtensionViteConfig({
    extensionDir: fileURLToPath(new URL('.', import.meta.url)),
    name: 'ExifGeotaggerExtension'
})
