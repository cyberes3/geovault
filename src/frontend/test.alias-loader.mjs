/**
 * Node's test runner (`node --test`) doesn't know about Vite's `@/` -> `src/` alias or Vite's
 * extensionless imports, so a `.test.mjs` file that transitively imports real app modules fails
 * to resolve outside of Vite. This hook teaches plain Node ESM resolution about both: it maps
 * `@/...` to `<repo>/src/...` and, for any relative/aliased specifier Node can't resolve as-is,
 * retries with `.ts` then `.js` appended.
 *
 * Registered via `--experimental-loader` in the `test` npm script; not used by the app itself.
 */
import { URL, pathToFileURL } from 'node:url';

const srcDir = pathToFileURL(new URL('./src/', import.meta.url).pathname).href;

async function resolveWithExtensionGuessing(specifier, context, nextResolve) {
    for (const ext of ['', '.ts', '.js']) {
        try {
            return await nextResolve(specifier + ext, context);
        } catch (err) {
            if (ext === '.js') throw err;
        }
    }
}

export async function resolve(specifier, context, nextResolve) {
    if (specifier.startsWith('@/')) {
        return resolveWithExtensionGuessing(new URL(specifier.slice(2), srcDir).href, context, nextResolve);
    }
    if (specifier.startsWith('./') || specifier.startsWith('../')) {
        return resolveWithExtensionGuessing(specifier, context, nextResolve);
    }
    return nextResolve(specifier, context);
}
