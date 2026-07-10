import { ref } from 'vue';
import {
  getGeocodingResultCoordinates,
  getGeocodingResultLabel,
  searchGeocoding,
} from 'platform/utils/geocodingSearch.js';

/**
 * Shared geocoding search with debounce, abort, and stale-request protection.
 */
export function useGeocodingSearch() {
  const searchQuery = ref('');
  const searchResults = ref([]);
  const showResults = ref(false);
  const isSearching = ref(false);
  const searchTimeout = ref(null);
  const currentSearchQuery = ref('');
  const abortController = ref(null);

  function clearSearch() {
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

  function handleSearchInput(debounceMs = 300) {
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

  async function performSearch(query = searchQuery.value.trim()) {
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
      if (error?.name === 'AbortError') {
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
   * @returns {Promise<{ ok: boolean, lon?: number, lat?: number, label?: string, error?: string }>}
   */
  async function geocodeAddress(query) {
    const trimmed = String(query || '').trim();
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
        return { ok: false, error: result.error || 'Geocoding failed' };
      }
      const first = result.features[0];
      const coords = first ? getGeocodingResultCoordinates(first) : null;
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
      if (error?.name === 'AbortError') {
        return { ok: false, error: 'Geocoding cancelled' };
      }
      return { ok: false, error: error?.message || 'Geocoding failed' };
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
