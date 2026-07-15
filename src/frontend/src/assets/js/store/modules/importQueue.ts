import type { Module } from 'vuex';
import type { ImportTableItem } from '../../types/import-types';
import { realtimeSocket } from '../../websocket/realtimeSocket';
import type { RootState } from '../rootState';

export interface ImportHistoryItem {
    id: number;
    original_filename: string;
    timestamp: string;
}

export interface ImportHistoryPagination {
    page: number;
    pageSize: number;
    totalPages: number;
    totalItems: number;
    hasNext: boolean;
    hasPrevious: boolean;
}

/** Shape of the `pagination` object as sent by `GET /api/item/import/history`. */
export interface BackendImportHistoryPagination {
    page: number;
    page_size: number;
    total_items: number;
    total_pages: number;
    has_next: boolean;
    has_previous: boolean;
}

export interface BulkJobFailedItem {
    filename: string;
    error: string;
}

/** A one-shot message for the component layer to surface (e.g. via `window.alert`) and clear. */
export interface BulkJobOutcome {
    type: 'success' | 'error';
    message: string;
}

export interface ImportQueueState {
    importTable: ImportTableItem[];
    importHistory: ImportHistoryItem[];
    importHistoryLoaded: boolean;
    importHistoryPagination: ImportHistoryPagination;
    isBulkImporting: boolean;
    bulkImportJobId: string | null;
    bulkImportingItemIds: number[];
    lastBulkImportOutcome: BulkJobOutcome | null;
    isBulkDeleting: boolean;
    bulkDeleteJobId: string | null;
    bulkDeletingItemIds: number[];
    lastBulkDeleteOutcome: BulkJobOutcome | null;
}

function addUnique(ids: number[], toAdd: number[]): number[] {
    const set = new Set(ids);
    toAdd.forEach((id) => set.add(id));
    return Array.from(set);
}

function removeIds(ids: number[], toRemove: number[]): number[] {
    const remove = new Set(toRemove);
    return ids.filter((id) => !remove.has(id));
}

/** Live import queue + import history, kept up to date by REST fetches and the import WebSocket modules. */
export const importQueueModule: Module<ImportQueueState, RootState> = {
    namespaced: true,
    state: (): ImportQueueState => ({
        importTable: [],
        importHistory: [],
        importHistoryLoaded: false,
        importHistoryPagination: {
            page: 1,
            pageSize: 10,
            totalPages: 0,
            totalItems: 0,
            hasNext: false,
            hasPrevious: false,
        },
        isBulkImporting: false,
        bulkImportJobId: null,
        bulkImportingItemIds: [],
        lastBulkImportOutcome: null,
        isBulkDeleting: false,
        bulkDeleteJobId: null,
        bulkDeletingItemIds: [],
        lastBulkDeleteOutcome: null,
    }),
    getters: {
        importTable: (state) => state.importTable,
        importHistory: (state) => state.importHistory,
        importHistoryLoaded: (state) => state.importHistoryLoaded,
        importHistoryPagination: (state) => state.importHistoryPagination,
        isBulkImporting: (state) => state.isBulkImporting,
        bulkImportingItemIds: (state) => state.bulkImportingItemIds,
        lastBulkImportOutcome: (state) => state.lastBulkImportOutcome,
        isBulkDeleting: (state) => state.isBulkDeleting,
        bulkDeletingItemIds: (state) => state.bulkDeletingItemIds,
        lastBulkDeleteOutcome: (state) => state.lastBulkDeleteOutcome,
    },
    mutations: {
        SET_IMPORT_TABLE(state, importTable: ImportTableItem[]) {
            state.importTable = importTable;
        },
        ADD_IMPORT_TABLE_ITEM(state, item: ImportTableItem) {
            const exists = state.importTable.some((existing) => existing.id === item.id);
            if (!exists) {
                state.importTable.unshift(item);
            }
        },
        REMOVE_IMPORT_TABLE_ITEM(state, itemId: number) {
            const index = state.importTable.findIndex((item) => item.id === itemId);
            if (index > -1) {
                state.importTable.splice(index, 1);
            }
        },
        REMOVE_IMPORT_TABLE_ITEMS(state, itemIds: number[]) {
            state.importTable = state.importTable.filter((item) => !itemIds.includes(item.id));
        },
        UPDATE_IMPORT_TABLE_ITEM(state, { id, updates }: { id: number; updates: Partial<ImportTableItem> }) {
            const index = state.importTable.findIndex((item) => item.id === id);
            if (index > -1) {
                state.importTable[index] = { ...state.importTable[index], ...updates };
            }
        },
        SET_IMPORT_HISTORY(
            state,
            payload: ImportHistoryItem[] | { items: ImportHistoryItem[]; pagination: BackendImportHistoryPagination },
        ) {
            if (Array.isArray(payload)) {
                state.importHistory = payload;
                return;
            }
            state.importHistory = payload.items;
            const backendPagination = payload.pagination;
            state.importHistoryPagination = {
                page: backendPagination.page,
                pageSize: backendPagination.page_size,
                totalPages: backendPagination.total_pages,
                totalItems: backendPagination.total_items,
                hasNext: backendPagination.has_next,
                hasPrevious: backendPagination.has_previous,
            };
        },
        SET_IMPORT_HISTORY_PAGINATION(state, pagination: ImportHistoryPagination) {
            state.importHistoryPagination = pagination;
        },
        SET_IMPORT_HISTORY_PAGE(state, page: number) {
            state.importHistoryPagination.page = page;
        },
        ADD_IMPORT_HISTORY_ITEM(state, { item, page }: { item: ImportHistoryItem; page?: number }) {
            const exists = state.importHistory.some((existing) => existing.id === item.id);
            if (exists) {
                return;
            }
            state.importHistoryPagination.totalItems += 1;
            state.importHistoryPagination.totalPages = Math.ceil(
                state.importHistoryPagination.totalItems / state.importHistoryPagination.pageSize,
            );
            state.importHistoryPagination.hasNext = state.importHistoryPagination.page < state.importHistoryPagination.totalPages;

            // New items always belong on page 1; other pages are refreshed via REST when visited.
            if (page === undefined || page === 1) {
                state.importHistory.unshift(item);
            }
        },
        SET_IMPORT_HISTORY_LOADED(state, loaded: boolean) {
            state.importHistoryLoaded = loaded;
        },
        SET_BULK_IMPORTING(state, importing: boolean) {
            state.isBulkImporting = importing;
        },
        SET_BULK_IMPORT_JOB_ID(state, jobId: string | null) {
            state.bulkImportJobId = jobId;
        },
        ADD_BULK_IMPORTING_ITEMS(state, itemIds: number[]) {
            state.bulkImportingItemIds = addUnique(state.bulkImportingItemIds, itemIds);
        },
        REMOVE_BULK_IMPORTING_ITEMS(state, itemIds: number[]) {
            state.bulkImportingItemIds = removeIds(state.bulkImportingItemIds, itemIds);
        },
        SET_LAST_BULK_IMPORT_OUTCOME(state, outcome: BulkJobOutcome | null) {
            state.lastBulkImportOutcome = outcome;
        },
        SET_BULK_DELETING(state, deleting: boolean) {
            state.isBulkDeleting = deleting;
        },
        SET_BULK_DELETE_JOB_ID(state, jobId: string | null) {
            state.bulkDeleteJobId = jobId;
        },
        ADD_BULK_DELETING_ITEMS(state, itemIds: number[]) {
            state.bulkDeletingItemIds = addUnique(state.bulkDeletingItemIds, itemIds);
        },
        REMOVE_BULK_DELETING_ITEMS(state, itemIds: number[]) {
            state.bulkDeletingItemIds = removeIds(state.bulkDeletingItemIds, itemIds);
        },
        SET_LAST_BULK_DELETE_OUTCOME(state, outcome: BulkJobOutcome | null) {
            state.lastBulkDeleteOutcome = outcome;
        },
    },
    actions: {
        setImportTable({ commit }, importTable: ImportTableItem[]) {
            commit('SET_IMPORT_TABLE', importTable);
        },
        addImportTableItem({ commit }, item: ImportTableItem) {
            commit('ADD_IMPORT_TABLE_ITEM', item);
        },
        removeImportTableItem({ commit }, itemId: number) {
            commit('REMOVE_IMPORT_TABLE_ITEM', itemId);
        },
        removeImportTableItems({ commit }, itemIds: number[]) {
            commit('REMOVE_IMPORT_TABLE_ITEMS', itemIds);
        },
        updateImportTableItem({ commit }, payload: { id: number; updates: Partial<ImportTableItem> }) {
            commit('UPDATE_IMPORT_TABLE_ITEM', payload);
        },
        /** Ask the `import_queue` WebSocket module to resend the full queue (e.g. manual refresh). */
        requestQueueRefresh() {
            realtimeSocket.requestRefresh('import_queue');
        },
        setImportHistory(
            { commit },
            payload: ImportHistoryItem[] | { items: ImportHistoryItem[]; pagination: BackendImportHistoryPagination },
        ) {
            commit('SET_IMPORT_HISTORY', payload);
        },
        setImportHistoryPagination({ commit }, pagination: ImportHistoryPagination) {
            commit('SET_IMPORT_HISTORY_PAGINATION', pagination);
        },
        setImportHistoryPage({ commit }, page: number) {
            commit('SET_IMPORT_HISTORY_PAGE', page);
        },
        addImportHistoryItem({ commit }, payload: ImportHistoryItem | { item: ImportHistoryItem; page: number }) {
            const normalized = 'item' in payload ? payload : { item: payload, page: undefined };
            commit('ADD_IMPORT_HISTORY_ITEM', normalized);
        },
        setImportHistoryLoaded({ commit }, loaded: boolean) {
            commit('SET_IMPORT_HISTORY_LOADED', loaded);
        },

        /** Kick off a bulk import job and optimistically mark the items as importing. */
        startBulkImport({ commit }, payload: { itemIds: number[]; importCustomIcons?: boolean }) {
            commit('SET_BULK_IMPORTING', true);
            commit('ADD_BULK_IMPORTING_ITEMS', payload.itemIds);
            realtimeSocket.send('bulk_import_job', 'start_bulk_import', {
                item_ids: payload.itemIds,
                import_custom_icons: payload.importCustomIcons ?? true,
            });
        },
        bulkImportJobStarted({ commit }, data: { job_id: string }) {
            commit('SET_BULK_IMPORT_JOB_ID', data.job_id);
        },
        bulkImportStatusUpdated({ commit }, data: { current_item_id?: number }) {
            if (data.current_item_id !== undefined) {
                commit('ADD_BULK_IMPORTING_ITEMS', [data.current_item_id]);
            }
        },
        bulkImportCompleted({ commit, state }, data: {
            job_id?: string;
            item_ids?: number[];
            failed_count?: number;
            failed_items?: BulkJobFailedItem[];
        }) {
            // Ignore completion events for a job we're no longer tracking (already superseded).
            if (data.job_id && state.bulkImportJobId && data.job_id !== state.bulkImportJobId) {
                return;
            }

            commit('SET_BULK_IMPORTING', false);
            commit('SET_BULK_IMPORT_JOB_ID', null);
            commit('REMOVE_BULK_IMPORTING_ITEMS', data.item_ids ?? []);

            if (data.failed_count && data.failed_count > 0 && data.failed_items?.length) {
                const details = data.failed_items.map((item) => `  • ${item.filename}: ${item.error}`).join('\n');
                commit('SET_LAST_BULK_IMPORT_OUTCOME', {
                    type: 'error',
                    message: `Bulk import completed with ${data.failed_count} failure(s):\n\n${details}`,
                });
            }
        },
        bulkImportFailed({ commit }, data: { item_ids?: number[]; error_message?: string }) {
            commit('SET_BULK_IMPORTING', false);
            commit('SET_BULK_IMPORT_JOB_ID', null);
            commit('REMOVE_BULK_IMPORTING_ITEMS', data.item_ids ?? []);
            commit('SET_LAST_BULK_IMPORT_OUTCOME', {
                type: 'error',
                message: data.error_message || 'An error occurred while importing items. Some items may not have been imported.',
            });
        },
        clearLastBulkImportOutcome({ commit }) {
            commit('SET_LAST_BULK_IMPORT_OUTCOME', null);
        },

        /** Kick off a bulk delete job and optimistically mark the items as deleting. */
        startBulkDelete({ commit }, payload: { itemIds: number[] }) {
            commit('SET_BULK_DELETING', true);
            commit('ADD_BULK_DELETING_ITEMS', payload.itemIds);
            realtimeSocket.send('bulk_delete_job', 'start_bulk_delete', { item_ids: payload.itemIds });
        },
        bulkDeleteJobStarted({ commit }, data: { job_id: string }) {
            commit('SET_BULK_DELETE_JOB_ID', data.job_id);
        },
        bulkDeleteStatusUpdated({ commit }, data: { current_item_id?: number }) {
            if (data.current_item_id !== undefined) {
                commit('ADD_BULK_DELETING_ITEMS', [data.current_item_id]);
            }
        },
        bulkDeleteCompleted({ commit, state }, data: { job_id?: string; item_ids?: number[] }) {
            if (data.job_id && state.bulkDeleteJobId && data.job_id !== state.bulkDeleteJobId) {
                return;
            }

            commit('SET_BULK_DELETING', false);
            commit('SET_BULK_DELETE_JOB_ID', null);
            commit('REMOVE_BULK_DELETING_ITEMS', data.item_ids ?? []);
        },
        bulkDeleteFailed({ commit }, data: { item_ids?: number[]; error_message?: string }) {
            commit('SET_BULK_DELETING', false);
            commit('SET_BULK_DELETE_JOB_ID', null);
            commit('REMOVE_BULK_DELETING_ITEMS', data.item_ids ?? []);
            commit('SET_LAST_BULK_DELETE_OUTCOME', {
                type: 'error',
                message: data.error_message || 'An error occurred while deleting items. Some items may not have been deleted.',
            });
        },
        clearLastBulkDeleteOutcome({ commit }) {
            commit('SET_LAST_BULK_DELETE_OUTCOME', null);
        },
    },
};
