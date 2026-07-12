import type { Module } from 'vuex';
import { ImportTableItem } from '../../types/import-types';

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

export interface ImportQueueState {
    importTable: ImportTableItem[];
    importHistory: ImportHistoryItem[];
    importHistoryLoaded: boolean;
    importHistoryPagination: ImportHistoryPagination;
}

/** Live import queue + import history, kept up to date by REST fetches and the import WebSocket modules. */
export const importQueueModule: Module<ImportQueueState, any> = {
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
    }),
    getters: {
        importTable: (state) => state.importTable,
        importHistory: (state) => state.importHistory,
        importHistoryLoaded: (state) => state.importHistoryLoaded,
        importHistoryPagination: (state) => state.importHistoryPagination,
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
        SET_IMPORT_HISTORY(state, payload: ImportHistoryItem[] | { items: ImportHistoryItem[]; pagination: any }) {
            if (Array.isArray(payload)) {
                state.importHistory = payload;
                return;
            }
            state.importHistory = payload.items;
            const backendPagination = payload.pagination;
            state.importHistoryPagination = {
                page: backendPagination.page,
                pageSize: backendPagination.page_size ?? backendPagination.pageSize,
                totalPages: backendPagination.total_pages ?? backendPagination.totalPages,
                totalItems: backendPagination.total_items ?? backendPagination.totalItems,
                hasNext: backendPagination.has_next ?? backendPagination.hasNext,
                hasPrevious: backendPagination.has_previous ?? backendPagination.hasPrevious,
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
        setImportHistory({ commit }, payload: ImportHistoryItem[] | { items: ImportHistoryItem[]; pagination: ImportHistoryPagination }) {
            commit('SET_IMPORT_HISTORY', payload);
        },
        setImportHistoryPagination({ commit }, pagination: ImportHistoryPagination) {
            commit('SET_IMPORT_HISTORY_PAGINATION', pagination);
        },
        setImportHistoryPage({ commit }, page: number) {
            commit('SET_IMPORT_HISTORY_PAGE', page);
        },
        addImportHistoryItem({ commit }, payload: ImportHistoryItem | { item: ImportHistoryItem; page: number }) {
            const normalized = 'item' in payload ? payload : { item: payload as ImportHistoryItem, page: undefined };
            commit('ADD_IMPORT_HISTORY_ITEM', normalized);
        },
        setImportHistoryLoaded({ commit }, loaded: boolean) {
            commit('SET_IMPORT_HISTORY_LOADED', loaded);
        },
    },
};
