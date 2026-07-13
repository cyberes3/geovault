/**
 * Resolves an extension's manifest `icon` value (a heroicon name, an inline `<svg>` string, or a
 * path to a static `.svg` file) into a Vue component usable in nav links / tool entries.
 */
import { defineComponent, h, markRaw, onBeforeUnmount, onMounted, onUpdated, ref, type Component } from 'vue';
import { httpClient } from '@/api/httpClient';

/**
 * Builds a Vue component that renders a raw SVG string via `innerHTML`, restyled so every path
 * uses `currentColor` (matching how Heroicons behave) unless a path explicitly opts out with
 * `fill="none"` / `stroke="none"`.
 */
function createSvgIconComponent(svgString: string): Component {
    const withXmlns = svgString.includes('xmlns=')
        ? svgString
        : svgString.replace('<svg', '<svg xmlns="http://www.w3.org/2000/svg"');

    return markRaw(defineComponent({
        props: { class: { type: String, default: '' } },
        setup(props) {
            const rootEl = ref<HTMLElement | null>(null);

            function applySvg() {
                const el = rootEl.value;
                if (!el) return;

                let svgWithClass = withXmlns;
                if (props.class) {
                    svgWithClass = svgWithClass.includes('class=')
                        ? svgWithClass.replace(/class=["'][^"']*["']/, `class="${props.class}"`)
                        : svgWithClass.replace('<svg', `<svg class="${props.class}"`);
                }
                el.innerHTML = svgWithClass;

                const svgElement = el.querySelector('svg');
                if (!svgElement) return;
                const styleId = svgElement.getAttribute('data-icon-style-id')
                    ?? `svg-icon-style-${Date.now()}-${Math.random()}`;
                svgElement.setAttribute('data-icon-style-id', styleId);

                if (!document.getElementById(styleId)) {
                    const style = document.createElement('style');
                    style.id = styleId;
                    style.textContent = `
                        svg[data-icon-style-id="${styleId}"] * {
                            fill: currentColor !important;
                            stroke: currentColor !important;
                        }
                        svg[data-icon-style-id="${styleId}"] *[fill="none"] {
                            fill: none !important;
                        }
                        svg[data-icon-style-id="${styleId}"] *[stroke="none"] {
                            stroke: none !important;
                        }
                    `;
                    document.head.appendChild(style);
                }
            }

            onMounted(applySvg);
            onUpdated(applySvg);
            onBeforeUnmount(() => {
                const styleId = rootEl.value?.querySelector('svg')?.getAttribute('data-icon-style-id');
                if (styleId) {
                    document.getElementById(styleId)?.remove();
                }
            });

            return () => h('div', { class: 'inline-flex items-center', ref: rootEl });
        }
    }));
}

export type IconGlobMap = Record<string, () => Promise<Component>>;

/**
 * Builds a heroicon name -> Component resolver from a Vite `import.meta.glob()` map of the
 * outline icon set (every extension manifest's `icon = "..."` value today is an outline name -
 * see `manifest.py` across `src/backend/extensions/*`). Rejects for an unrecognized name instead
 * of silently resolving to null/undefined, so a typo'd or removed icon name shows up as a loud
 * `console.error` (via `extensionLoader.ts`'s `Promise.allSettled` around icon resolution) rather
 * than a silently missing icon nobody notices. Caches per-name promises - including rejections -
 * so repeated lookups for the same name share one fetch/one failure instead of re-resolving or
 * re-throwing every time.
 *
 * Takes the glob map as plain data rather than calling `import.meta.glob()` itself so this stays
 * importable under plain `node --test` (which never runs through Vite's transform) - only `main.js`
 * needs the literal `import.meta.glob(...)` call, since that's the one place Vite can statically
 * detect and rewrite it.
 */
export function createHeroiconResolver(outlineIcons: IconGlobMap) {
    const cache = new Map<string, Promise<Component>>();

    return function resolveHeroiconByName(name: string): Promise<Component> {
        const cached = cache.get(name);
        if (cached) return cached;

        const key = Object.keys(outlineIcons).find((path) => path.endsWith(`/${name}.js`));
        const promise = key
            ? outlineIcons[key]().then(markRaw)
            : Promise.reject(new Error(`Unknown heroicon: "${name}"`));

        cache.set(name, promise);
        return promise;
    };
}

function defaultResolveHeroicon(name: string): Promise<Component> {
    if (typeof window === 'undefined') {
        return Promise.reject(new Error(`Cannot resolve heroicon "${name}": window.gv_core is unavailable`));
    }
    return window.gv_core.resolveHeroiconByName(name);
}

export async function resolveExtensionIcon(
    icon: string | null | undefined,
    kebabName: string,
    resolveHeroicon: (name: string) => Promise<Component> = defaultResolveHeroicon
): Promise<Component | null> {
    if (!icon || typeof icon !== 'string') {
        return null;
    }
    const trimmedIcon = icon.trim();

    if (trimmedIcon.startsWith('<svg')) {
        try {
            return createSvgIconComponent(trimmedIcon);
        } catch (err) {
            console.error(`Failed to parse inline SVG icon for extension ${kebabName}:`, err);
            return null;
        }
    }

    // A bare name with no slash and no `.svg` extension is the only shape that's ever a heroicon
    // name (e.g. caltopo's manifest `icon = "icon.svg"` must skip straight to the file-fetch
    // block below, not get treated as an unrecognized heroicon name).
    if (!icon.includes('/') && !icon.endsWith('.svg')) {
        // Intentionally not caught here - an unknown heroicon name should fail loudly, and
        // `extensionLoader.ts`'s `Promise.allSettled` around icon resolution is what turns that
        // into a logged error + a null icon instead of a crashed extension load.
        return await resolveHeroicon(icon);
    }

    if (icon.endsWith('.svg') || icon.includes('/')) {
        try {
            const iconUrl = icon.startsWith('/') ? icon : `/extensions/static/${kebabName}/${icon}`;
            const response = await httpClient.get<string>(iconUrl, { responseType: 'text' });
            return createSvgIconComponent(response.data);
        } catch (err) {
            console.error(`Failed to load SVG file icon ${icon} for extension ${kebabName}:`, err);
            return null;
        }
    }

    return null;
}
