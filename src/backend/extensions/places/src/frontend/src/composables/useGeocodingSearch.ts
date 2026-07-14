import { ref, type Ref } from 'vue';
import type { GeocodingResult } from '@/types/gv-core';

const { getGeocodingResultCoordinates, getGeocodingResultLabel, searchGeocoding } = window.gv_core.GeoVault.utils;

export interface GeocodeAddressResult {
  ok: boolean;
  lon?: number;
  lat?: number;
  label?: string;
  error?: string;
}

export interface UseGeocodingSearchReturn {
  searchQuery: Ref<string>;
  searchResults: Ref<GeocodingResult[]>;
  showResults: Ref<boolean>;
  isSearching: Ref<boolean>;
  searchTimeout: Ref<ReturnType<typeof setTimeout> | null>;
  currentSearchQuery: Ref<string>;
  handleSearchInput: (debounceMs?: number) => void;
  performSearch: (query?: string) => Promise<void>;
  clearSearch: () => void;
  geocodeAddress: (query: string) => Promise<GeocodeAddressResult>;
  getGeocodingResultCoordinates: (result: GeocodingResult) => { lon: number; lat: number } | null;
  getGeocodingResultLabel: (result: GeocodingResult) => string;
}

/**
 * Shared geocoding search with debounce, abort, and stale-request protection.
 */
export function useGeocodingSearch(): UseGeocodingSearchReturn {
  const searchQuery = ref('');
  const searchResults: Ref<GeocodingResult[]> = ref([]);
  const showResults = ref(false);
  const isSearching = ref(false);
  const searchTimeout: Ref<ReturnType<typeof setTimeout> | null> = ref(null);
  const currentSearchQuery = ref('');
  const abortController: Ref<AbortController | null> = ref(null);

  function clearSearch(): void {
    searchQuery.value = '';
    searchResults.value = [];
    showResults.value = false;
    currentSearchQuery.value = '';
    isSearching.value = false;
    if (searchTimeout.value) {
      clearTimeout(searchTimeout.value);
      searchTimeout.value = null;
    }
    if (abortController.value) {
      abortController.value.abort();
      abortController.value = null;
    }
  }

  function handleSearchInput(debounceMs = 300): void {
    if (searchTimeout.value) {
      clearTimeout(searchTimeout.value);
    }
    if (!searchQuery.value.trim()) {
      clearSearch();
      return;
    }
    showResults.value = true;
    isSearching.value = true;
    searchTimeout.value = setTimeout(() => {
      searchTimeout.value = null;
      void performSearch();
    }, debounceMs);
  }

  async function performSearch(query: string = searchQuery.value.trim()): Promise<void> {
    if (!query) {
      clearSearch();
      return;
    }
    showResults.value = true;
    currentSearchQuery.value = query;
    isSearching.value = true;

    if (abortController.value) {
      abortController.value.abort();
    }
    abortController.value = new AbortController();

    try {
      const result = await searchGeocoding(query, { signal: abortController.value.signal });
      if (currentSearchQuery.value !== query) {
        return;
      }
      searchResults.value = result.ok ? result.features : [];
    } catch (error) {
      if (error instanceof Error && error.name === 'AbortError') {
        return;
      }
      if (currentSearchQuery.value === query) {
        searchResults.value = [];
      }
    } finally {
      if (currentSearchQuery.value === query) {
        isSearching.value = false;
      }
    }
  }

  /**
   * Geocode a single address string (no dropdown UI).
   */
  async function geocodeAddress(query: string): Promise<GeocodeAddressResult> {
    const trimmed = query.trim();
    if (!trimmed) {
      return { ok: false, error: 'Address is required' };
    }

    if (abortController.value) {
      abortController.value.abort();
    }
    abortController.value = new AbortController();

    try {
      const result = await searchGeocoding(trimmed, { signal: abortController.value.signal });
      if (!result.ok) {
        return { ok: false, error: result.error ?? 'Geocoding failed' };
      }
      if (result.features.length === 0) {
        return { ok: false, error: 'Address not found' };
      }
      const first = result.features[0];
      const coords = getGeocodingResultCoordinates(first);
      if (!coords) {
        return { ok: false, error: 'Address not found' };
      }
      return {
        ok: true,
        lon: coords.lon,
        lat: coords.lat,
        label: getGeocodingResultLabel(first),
      };
    } catch (error) {
      if (error instanceof Error && error.name === 'AbortError') {
        return { ok: false, error: 'Geocoding cancelled' };
      }
      return { ok: false, error: error instanceof Error ? error.message : 'Geocoding failed' };
    }
  }

  return {
    searchQuery,
    searchResults,
    showResults,
    isSearching,
    searchTimeout,
    currentSearchQuery,
    handleSearchInput,
    performSearch,
    clearSearch,
    geocodeAddress,
    getGeocodingResultCoordinates,
    getGeocodingResultLabel,
  };
}
