import { isRef, onActivated, onDeactivated, onMounted, onUnmounted, unref, watch, type MaybeRefOrGetter, type WatchStopHandle } from 'vue';

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

    let stopWatch: WatchStopHandle | null = null;

    const start = () => {
        if (stopWatch) return;
        if (isRef(titleSource) || typeof titleSource === 'function') {
            stopWatch = watch(titleSource, applyTitle, { immediate: true });
        } else {
            applyTitle();
        }
    };

    const stop = () => {
        stopWatch?.();
        stopWatch = null;
    };

    onMounted(start);
    onActivated(start);
    onDeactivated(stop);
    onUnmounted(stop);
}
