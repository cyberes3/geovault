import { fileURLToPath } from 'url'
import { dirname } from 'path'
import { createSharedEslintConfig } from './eslint.shared-config.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))

// Core uses the same rule set extensions extend (see eslint.shared-config.mjs) so the whole
// platform lints consistently.
export default createSharedEslintConfig({ tsconfigRootDir: __dirname })
