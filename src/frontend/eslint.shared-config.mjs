/**
 * Shared ESLint flat-config factory used by both core and every extension frontend, so the whole
 * platform lints with the same rules instead of each extension having no lint config at all (the
 * previous state). An extension's own `eslint.config.js` should just be:
 *
 *   import { fileURLToPath } from 'url'
 *   import { dirname } from 'path'
 *   import { createSharedEslintConfig } from '../../../../../frontend/eslint.shared-config.mjs'
 *
 *   export default createSharedEslintConfig({ tsconfigRootDir: dirname(fileURLToPath(import.meta.url)) })
 */
import js from '@eslint/js'
import vue from 'eslint-plugin-vue'
import typescript from '@typescript-eslint/eslint-plugin'
import typescriptParser from '@typescript-eslint/parser'

const sharedGlobals = {
  console: 'readonly',
  process: 'readonly',
  window: 'readonly',
  document: 'readonly',
  navigator: 'readonly',
  localStorage: 'readonly',
  sessionStorage: 'readonly',
  fetch: 'readonly',
  performance: 'readonly',
  setInterval: 'readonly',
  clearInterval: 'readonly',
  setTimeout: 'readonly',
  clearTimeout: 'readonly',
  requestAnimationFrame: 'readonly',
  cancelAnimationFrame: 'readonly',
  requestIdleCallback: 'readonly',
  cancelIdleCallback: 'readonly',
  alert: 'readonly',
  confirm: 'readonly',
  WebSocket: 'readonly',
  CloseEvent: 'readonly',
  MessageEvent: 'readonly',
  Image: 'readonly',
  __SW_VERSION__: 'readonly' // injected by vite.config.js `define`
}

const sharedRules = {
  '@typescript-eslint/no-floating-promises': 'error',
  '@typescript-eslint/no-misused-promises': 'error',
  '@typescript-eslint/no-unnecessary-condition': 'error',
  '@typescript-eslint/no-unnecessary-type-assertion': 'error',
  // `ignorePrimitives.string` because for `string | null | undefined` values (API responses,
  // free-text fields), `||` is usually what's actually wanted: treat an empty string the same
  // as missing, e.g. `track.color || DEFAULT_COLOR` should ALSO fall back for `color: ''`, not
  // just `null`/`undefined`. Bare `??` is still preferred for non-string types (0 and false are
  // often meaningfully different from "unset").
  '@typescript-eslint/prefer-nullish-coalescing': ['error', { ignorePrimitives: { string: true } }],
  '@typescript-eslint/prefer-optional-chain': 'error',
  '@typescript-eslint/no-non-null-assertion': 'error',
  '@typescript-eslint/no-unsafe-assignment': 'error',
  '@typescript-eslint/no-unsafe-call': 'error',
  '@typescript-eslint/no-unsafe-member-access': 'error',
  '@typescript-eslint/no-unsafe-return': 'error',
  '@typescript-eslint/restrict-plus-operands': 'error',
  '@typescript-eslint/restrict-template-expressions': 'error',
  '@typescript-eslint/unbound-method': 'error',
  '@typescript-eslint/no-base-to-string': 'error',
  '@typescript-eslint/no-confusing-void-expression': 'error',
  '@typescript-eslint/no-meaningless-void-operator': 'error',
  '@typescript-eslint/no-misused-new': 'error',
  '@typescript-eslint/no-redundant-type-constituents': 'error',
  '@typescript-eslint/no-unnecessary-boolean-literal-compare': 'error',
  '@typescript-eslint/no-unnecessary-type-arguments': 'error',
  '@typescript-eslint/prefer-includes': 'error',
  '@typescript-eslint/prefer-string-starts-ends-with': 'error',
  '@typescript-eslint/require-array-sort-compare': 'error',
  '@typescript-eslint/switch-exhaustiveness-check': 'error',
  '@typescript-eslint/no-unused-vars': 'error',
  '@typescript-eslint/no-explicit-any': 'warn',
  '@typescript-eslint/ban-ts-comment': 'warn',
  'vue/max-attributes-per-line': 'off',
  'vue/html-indent': 'off',
  'vue/html-self-closing': 'off',
  'vue/singleline-html-element-content-newline': 'off',
  'vue/html-closing-bracket-newline': 'off',
  'vue/html-closing-bracket-spacing': 'off',
  'vue/first-attribute-linebreak': 'off',
  'vue/attributes-order': 'off',
  'vue/order-in-components': 'off',
  'vue/require-prop-types': 'off',
  'vue/no-unused-components': 'off',
  'vue/multi-word-component-names': 'off',
  'no-console': 'off',
  'no-debugger': 'warn',
  'no-unused-vars': 'off',
  'prefer-const': 'error',
  'no-var': 'error'
}

/**
 * @param {{ tsconfigRootDir: string, project?: string }} options
 */
export function createSharedEslintConfig({ tsconfigRootDir, project = './tsconfig.json' }) {
  return [
    js.configs.recommended,
    ...vue.configs['flat/recommended'],
    {
      files: ['**/*.{js,ts}'],
      languageOptions: {
        parser: typescriptParser,
        parserOptions: { project, tsconfigRootDir, ecmaVersion: 2021, sourceType: 'module' },
        globals: sharedGlobals
      },
      plugins: { '@typescript-eslint': typescript },
      rules: sharedRules
    },
    {
      files: ['**/*.vue'],
      languageOptions: {
        parser: vue.parser,
        parserOptions: {
          parser: typescriptParser,
          project,
          tsconfigRootDir,
          extraFileExtensions: ['.vue'],
          ecmaVersion: 2021,
          sourceType: 'module'
        },
        globals: sharedGlobals
      },
      plugins: { '@typescript-eslint': typescript },
      rules: { ...sharedRules, 'vue/no-unused-vars': 'off' }
    },
    {
      ignores: ['dist/', 'node_modules/', '*.config.js', '*.config.ts']
    }
  ]
}
