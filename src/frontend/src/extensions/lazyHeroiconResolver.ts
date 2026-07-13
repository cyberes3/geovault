import type { Component } from 'vue';
import { createHeroiconResolver } from './resolveExtensionIcon';

/**
 * Builds the real heroicon-by-name resolver, deferred behind a dynamic import instead of being
 * constructed directly in `main.js`.
 *
 * `import.meta.glob(...)` expands to a literal map of one entry per matched file (~300 outline
 * icons) directly inside whichever module calls it - if that call lived in `main.js` itself, all
 * ~300 import specifiers + tiny loader functions would be inlined into the app's eager entry
 * chunk, even though the map is only ever needed the first time some extension's manifest `icon:`
 * name is actually looked up. Splitting it into its own module that's only reached via dynamic
 * `import()` keeps that map out of the critical boot path entirely - see
 * `utils/map/maplibre/lazyMaplibreGl.js`/`utils/map/openlayers/lazyOl.js` for the same pattern
 * applied to those libraries.
 */
let resolver: ((name: string) => Promise<Component>) | null = null;

export function resolveHeroiconByName(name: string): Promise<Component> {
    resolver ??= createHeroiconResolver(
        import.meta.glob<Component>(['/node_modules/@heroicons/vue/24/outline/*.js', '!**/index.js'], { import: 'default' })
    );
    return resolver(name);
}
