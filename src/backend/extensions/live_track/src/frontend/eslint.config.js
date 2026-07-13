import { fileURLToPath } from 'url'
import { dirname } from 'path'
import { createSharedEslintConfig } from '../../../../../frontend/eslint.shared-config.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))

// Extends the same rule set core uses (see frontend/eslint.shared-config.mjs) so extensions and
// core lint consistently.
export default createSharedEslintConfig({ tsconfigRootDir: __dirname })
