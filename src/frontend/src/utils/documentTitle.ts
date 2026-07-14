import { isRef, onMounted, unref, watch, type MaybeRefOrGetter } from 'vue';

export function setGeoVaultPageTitle(label: string): void {
    document.title = `GeoVault | ${label}`;
}

function resolveTitleSource(titleSource: MaybeRefOrGetter<string>): string {
    if (typeof titleSource === 'function') {
        return titleSource();
    }
    return unref(titleSource);
}

/**
 * Keep document.title in sync with a reactive title while the component is mounted.
 */
export function useDocumentTitle(titleSource: MaybeRefOrGetter<string>): void {
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
