import { computed, reactive, ref, watch, type Ref } from 'vue';
import type { ImportFeatureItem } from '@/assets/js/types/import-types';
import { useImportProcessData, type RawImportPagePayload } from '@/composables/useImportProcessData';
import { useImportFeatureEditing } from '@/composables/useImportFeatureEditing';
import { useBulkOperations } from '@/composables/useBulkOperations';
import {
    cloneSkipIntent,
    emptySkipIntent,
    skipIntentEqual,
    type SkipIntentWire,
} from '@/contracts/duplicates';
import {
    importableCount,
    isBlockedVerdict,
    isFeatureSkipped,
    toggleSkipIntent,
} from '@/composables/import/duplicateSession';
import { getImportJobStatus } from '@/api/services/importApi';
import { toastApiError } from '@/utils/apiError';

const PAGE_REQUEST_TIMEOUT_MS = 15000;
const IMPORT_COMPLETION_TIMEOUT_MS = 90000;
const JOB_POLL_INTERVAL_MS = 400;
const JOB_POLL_TIMEOUT_MS = 60000;

export interface ImportProcessSessionOptions {
    importId: Ref<string | number | null>;
    requestPage: (page: number, pageSize: number, hideDuplicates: boolean) => void;
    sendStatus: (type: string, data?: Record<string, unknown>) => void;
    onUnparsableFile: (message: string) => void;
    isImported: Ref<boolean>;
}

function jobIsTerminal(status: string | undefined): boolean {
    return status === 'completed' || status === 'failed' || status === 'canceled' || status === 'cancelled';
}

async function pollJobUntilTerminal(jobId: string): Promise<string | undefined> {
    const started = Date.now();
    while (Date.now() - started < JOB_POLL_TIMEOUT_MS) {
        const data = (await getImportJobStatus(jobId)) as { job_status?: { status?: string } };
        const status = data.job_status?.status;
        if (jobIsTerminal(status)) {
            return status;
        }
        await new Promise((resolve) => setTimeout(resolve, JOB_POLL_INTERVAL_MS));
    }
    return undefined;
}

export function useImportProcessSession(options: ImportProcessSessionOptions) {
    const { importId, requestPage, sendStatus, onUnparsableFile, isImported } = options;

    const skipIntent = ref<SkipIntentWire>(emptySkipIntent());
    const savedSkipIntent = ref<SkipIntentWire>(emptySkipIntent());
    const skipIntentDirty = computed(() => !skipIntentEqual(skipIntent.value, savedSkipIntent.value));

    const lockButtons = ref(false);
    const saveStatus = ref<'success' | 'error' | null>(null);
    let saveStatusTimeout: ReturnType<typeof setTimeout> | null = null;
    let pageRequestTimeout: ReturnType<typeof setTimeout> | null = null;
    let importCompletionTimeout: ReturnType<typeof setTimeout> | null = null;

    const loading = reactive({
        saving: false,
        importing: false,
        recheckingDuplicates: false,
        redirecting: false,
    });

    const waitingForImportCompletion = ref(false);
    const activeImportJobId = ref<string | null>(null);

    const importData = useImportProcessData({
        importId,
        requestPage: (page, pageSize, hideDuplicates) => {
            clearTimeout(pageRequestTimeout ?? undefined);
            pageRequestTimeout = setTimeout(() => {
                importData.loadingPage.value = false;
                importData.msg.value = 'Timed out waiting for the feature page. Reconnect or try again.';
            }, PAGE_REQUEST_TIMEOUT_MS);
            requestPage(page, pageSize, hideDuplicates);
        },
        onUnparsableFile,
    });

    const featureEditing = useImportFeatureEditing({
        importId,
        itemsForUser: importData.itemsForUser,
        originalItems: importData.originalItems,
        pagination: importData.pagination,
        editCache: importData.editCache,
    });

    const bulkOps = useBulkOperations(importId);

    const importableCountValue = computed(() =>
        importableCount(importData.draftCount.value, importData.duplicateCounts.value, skipIntent.value),
    );

    function applyServerSkipIntent(serverIntent: SkipIntentWire): void {
        if (skipIntentDirty.value) {
            return;
        }
        skipIntent.value = cloneSkipIntent(serverIntent);
        savedSkipIntent.value = cloneSkipIntent(serverIntent);
    }

    function handlePageData(data: RawImportPagePayload): void {
        if (pageRequestTimeout) {
            clearTimeout(pageRequestTimeout);
            pageRequestTimeout = null;
        }
        importData.handlePageData(data);
        applyServerSkipIntent(importData.serverSkipIntent.value);
    }

    function hasUnsavedChanges(): boolean {
        return featureEditing.hasFeatureChanges() || bulkOps.hasBulkOperationsChanged.value || skipIntentDirty.value;
    }

    function featureHash(item: ImportFeatureItem | null | undefined, index: number): string {
        return importData.getFeatureId(item, index);
    }

    function isItemSkipped(item: ImportFeatureItem | null | undefined, index: number): boolean {
        return isFeatureSkipped(featureHash(item, index), item?.duplicate_verdict, skipIntent.value);
    }

    function isItemHashDuplicate(item: ImportFeatureItem | null | undefined): boolean {
        return isBlockedVerdict(item?.duplicate_verdict);
    }

    function isItemDisabled(item: ImportFeatureItem | null | undefined, index: number): boolean {
        return isImported.value || isItemHashDuplicate(item) || isItemSkipped(item, index) || loading.importing;
    }

    function toggleSkipItem(index: number): void {
        const item = importData.itemsForUser.value[index];
        if (!item || isItemHashDuplicate(item)) {
            return;
        }
        skipIntent.value = toggleSkipIntent(skipIntent.value, featureHash(item, index), item.duplicate_verdict);
    }

    async function saveChangesInternal(): Promise<{ changedCount: number }> {
        importData.cacheCurrentPageChanges();
        const changedFeatures = featureEditing.getChangedFeatures();
        const bulkOpsChanged = bulkOps.hasBulkOperationsChanged.value;

        if (bulkOpsChanged) {
            await bulkOps.saveBulkOperations(bulkOps.originalBulkOperations.value);
        }

        if (skipIntentDirty.value) {
            await featureEditing.saveSkipState(skipIntent.value);
            savedSkipIntent.value = cloneSkipIntent(skipIntent.value);
        }

        if (changedFeatures.length === 0 && !bulkOpsChanged) {
            return { changedCount: 0 };
        }

        const result = await featureEditing.saveFeatures(changedFeatures);
        return { changedCount: result.updatedCount };
    }

    function clearSaveStatusTimer(): void {
        if (saveStatusTimeout) {
            clearTimeout(saveStatusTimeout);
            saveStatusTimeout = null;
        }
    }

    async function saveChanges(): Promise<void> {
        lockButtons.value = true;
        loading.saving = true;
        clearSaveStatusTimer();
        saveStatus.value = null;

        try {
            await saveChangesInternal();
            saveStatus.value = 'success';
            saveStatusTimeout = setTimeout(() => {
                saveStatus.value = null;
                saveStatusTimeout = null;
            }, 2000);
        } catch (error) {
            saveStatus.value = 'error';
            toastApiError(error, 'Error saving changes');
        } finally {
            lockButtons.value = false;
            loading.saving = false;
        }
    }

    function startImportCompletionWatch(jobId?: string): void {
        if (importCompletionTimeout) {
            clearTimeout(importCompletionTimeout);
        }
        activeImportJobId.value = jobId ?? null;
        importCompletionTimeout = setTimeout(() => {
            if (!waitingForImportCompletion.value) {
                return;
            }
            if (activeImportJobId.value) {
                void pollJobUntilTerminal(activeImportJobId.value).then((status) => {
                    if (!waitingForImportCompletion.value) {
                        return;
                    }
                    if (status === 'failed' || status === 'canceled' || status === 'cancelled') {
                        waitingForImportCompletion.value = false;
                        lockButtons.value = false;
                        loading.importing = false;
                        toastApiError(new Error('Import did not complete'), 'Import failed or was canceled');
                    }
                });
            }
        }, IMPORT_COMPLETION_TIMEOUT_MS);
    }

    function clearImportCompletionWatch(): void {
        if (importCompletionTimeout) {
            clearTimeout(importCompletionTimeout);
            importCompletionTimeout = null;
        }
        waitingForImportCompletion.value = false;
        activeImportJobId.value = null;
    }

    async function performImport(importCustomIcons: boolean): Promise<void> {
        lockButtons.value = true;
        loading.importing = true;

        try {
            await saveChangesInternal();
        } catch (saveError) {
            toastApiError(saveError, 'Error saving changes before import');
            lockButtons.value = false;
            loading.importing = false;
            return;
        }

        try {
            waitingForImportCompletion.value = true;
            const accepted = await featureEditing.requestImport(importCustomIcons, skipIntent.value);
            startImportCompletionWatch(accepted.job_id);
        } catch (error) {
            toastApiError(error, 'Error performing import');
            clearImportCompletionWatch();
            lockButtons.value = false;
            loading.importing = false;
        }
    }

    async function recheckDuplicates(): Promise<void> {
        lockButtons.value = true;
        loading.recheckingDuplicates = true;
        try {
            const accepted = await featureEditing.requestRecheckDuplicates(importData.pagination.currentPage);
            if (accepted.job_id) {
                const status = await pollJobUntilTerminal(accepted.job_id);
                if (status === 'failed' || status === 'canceled' || status === 'cancelled') {
                    toastApiError(new Error('Recheck failed'), 'Error rechecking duplicates');
                }
            }
            sendStatus('refresh');
        } catch (error) {
            toastApiError(error, 'Error rechecking duplicates');
        } finally {
            lockButtons.value = false;
            loading.recheckingDuplicates = false;
        }
    }

    async function setHideDuplicates(value: boolean): Promise<void> {
        importData.hideDuplicates.value = value;
        await importData.loadPage(1);
    }

    function rerequestCurrentPage(): void {
        requestPage(importData.pagination.currentPage, importData.pagination.pageSize, importData.hideDuplicates.value);
    }

    function reset(): void {
        if (pageRequestTimeout) {
            clearTimeout(pageRequestTimeout);
            pageRequestTimeout = null;
        }
        clearSaveStatusTimer();
        clearImportCompletionWatch();
        skipIntent.value = emptySkipIntent();
        savedSkipIntent.value = emptySkipIntent();
        lockButtons.value = false;
        saveStatus.value = null;
        loading.saving = false;
        loading.importing = false;
        loading.recheckingDuplicates = false;
        loading.redirecting = false;
        importData.reset();
        bulkOps.reset();
    }

    watch(importData.serverSkipIntent, (next) => {
        applyServerSkipIntent(next);
    });

    return {
        importData,
        featureEditing,
        bulkOps,
        skipIntent,
        skipIntentDirty,
        lockButtons,
        saveStatus,
        loading,
        waitingForImportCompletion,
        importableCount: importableCountValue,
        hasUnsavedChanges,
        handlePageData,
        saveChanges,
        performImport,
        recheckDuplicates,
        setHideDuplicates,
        rerequestCurrentPage,
        toggleSkipItem,
        isItemSkipped,
        isItemHashDuplicate,
        isItemDisabled,
        clearImportCompletionWatch,
        reset,
    };
}

export type ImportProcessSession = ReturnType<typeof useImportProcessSession>;
