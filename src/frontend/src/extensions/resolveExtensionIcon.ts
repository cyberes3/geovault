/**
 * Resolves an extension's manifest `icon` value (a heroicon name, an inline `<svg>` string, or a
 * path to a static `.svg` file) into a Vue component usable in nav links / tool entries.
 */
import { defineComponent, h, markRaw, onBeforeUnmount, onMounted, onUpdated, ref, type Component } from 'vue';
import * as HeroiconsOutline from '@heroicons/vue/24/outline';
import * as HeroiconsSolid from '@heroicons/vue/24/solid';
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

export async function resolveExtensionIcon(icon: string | null | undefined, kebabName: string): Promise<Component | null> {
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

    if (!icon.includes('/')) {
        const heroicons = HeroiconsOutline as Partial<Record<string, Component>>;
        const heroiconsSolid = HeroiconsSolid as Partial<Record<string, Component>>;
        const match = heroicons[icon] ?? heroiconsSolid[icon];
        if (match) return markRaw(match);
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
