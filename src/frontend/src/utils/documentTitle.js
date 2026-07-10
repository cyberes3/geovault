import { isRef, onMounted, unref, watch } from 'vue';

export function setGeoVaultPageTitle(label) {
    document.title = `GeoVault | ${label}`;
}

function resolveTitleSource(titleSource) {
    if (typeof titleSource === 'function') {
        return titleSource();
    }
    return unref(titleSource);
}

/**
 * Keep document.title in sync with a reactive title while the component is mounted.
 * @param {import('vue').MaybeRefOrGetter<string>} titleSource
 */
export function useDocumentTitle(titleSource) {
    const applyTitle = () => {
        const title = resolveTitleSource(titleSource);
        if (title) {
            setGeoVaultPageTitle(title);
        }
    };

    if (isRef(titleSource) || typeof titleSource === 'function') {
        watch(titleSource, applyTitle, { immediate: true });
    } else {
        onMounted(applyTitle);
    }
}
