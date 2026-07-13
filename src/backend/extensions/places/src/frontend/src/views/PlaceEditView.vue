<template>
  <div class="flex flex-col flex-1 min-h-0 h-full w-full overflow-y-auto sm:overflow-hidden bg-gray-50">
    <div class="h-[42vh] min-h-[240px] max-h-[440px] sm:h-auto sm:max-h-none sm:min-h-[320px] sm:flex-1 sm:min-h-0 relative border-b border-gray-300">
      <div ref="mapContainer" class="absolute inset-0 z-0 h-full w-full min-h-0 bg-gray-100 touch-pan-y"></div>

      <div
          v-if="loadingEdit"
          class="absolute inset-0 z-20 flex flex-col items-center justify-center bg-gray-500/40 pointer-events-auto cursor-wait"
          aria-busy="true"
          aria-live="polite"
      >
        <div class="inline-flex bg-white rounded-lg shadow-lg border border-gray-200 px-4 py-3">
          <Loader size="sm" layout="inline" :show-message="true" message="Loading..."/>
        </div>
      </div>

      <PlaceLocationSearch
          v-model:search-query="searchQuery"
          :search-results="searchResults"
          :is-searching="isSearching"
          :show-results="showResults"
          :search-timeout="searchTimeout"
          :disabled="loadingEdit"
          @input="handleSearchInput()"
          @search="performSearch()"
          @select-result="selectSearchResult"
      />

      <PlacesMapControls
          @open-layer-picker="showLayerPickerModal = true"
          @reset-viewport="resetMapViewport"
      />
    </div>

    <div
        class="flex flex-col bg-white border-t border-gray-300 shadow-[0_-2px_10px_rgba(0,0,0,0.05)] sm:flex-shrink-0 sm:z-20"
        :class="{ 'opacity-60': loadingEdit }"
        :aria-busy="loadingEdit"
    >
      <div>
        <PlaceForm
          :name="name"
          :description="description"
          :coordinates-input="coordinatesInput"
          :coordinate-error="coordinateError"
          :is-geocoding="isGeocoding"
          :is-getting-location="isGettingLocation"
          :saving="saving"
          :loading="loadingEdit"
          :is-edit="!!editId"
          @update:name="name = $event"
          @update:description="description = $event"
          @coordinates-input="onCoordinatesInput"
          @validate-coordinates="validateCoordinatesField"
          @use-location="useCurrentLocation"
          @save="savePlace"
          @cancel="goToList"
        />
      </div>
    </div>

    <PlacesLayerPickerModal
        :is-open="showLayerPickerModal"
        select-id="place-edit-map-layer"
        v-model:selected-base-source-id="selectedBaseSourceId"
        :base-source-options="baseSourceOptions"
        @close="showLayerPickerModal = false"
        @update:selected-base-source-id="applyBaseSourceSelection"
    />

  </div>
</template>

<script setup>
import { computed, inject, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave, useRoute } from 'vue-router';
import Loader from 'platform/components/parts/Loader.vue';
import PlaceForm from '@/components/PlaceForm.vue';
import PlaceLocationSearch from '@/components/PlaceLocationSearch.vue';
import PlacesLayerPickerModal from '@/components/PlacesLayerPickerModal.vue';
import PlacesMapControls from '@/components/PlacesMapControls.vue';
import { useGeocodingSearch } from '@/composables/useGeocodingSearch.js';
import { usePlaceForm } from '@/composables/usePlaceForm.js';
import { usePlacesApi } from '@/composables/usePlacesApi.js';
import { createPlacesMap } from '@/utils/placesMaplibre.js';
import { ensureUserSettingsLoaded, getDefaultMapSourceId } from '@/utils/placesMapSettings.js';

const PLACE_EDIT_SOURCE_ID = 'gv_places_overlay_edit_source';
const PLACE_EDIT_LAYER_ID = 'gv_places_overlay_edit_layer';
const INITIAL_CENTER = [0, 0];
const INITIAL_ZOOM = 2;

const route = useRoute();
const router = inject('extensionRouter');
const toast = window.gv_core?.GeoVault?.toast ?? { success: () => {}, error: () => {} };
const useDocumentTitle = window.gv_core.useDocumentTitle;

const { getPlace, createPlace, updatePlace } = usePlacesApi();
const {
  name,
  description,
  coordinatesInput,
  coordinateError,
  isGeocoding,
  isDirty,
  setCoords,
  validateCoordinates,
  onCoordinatesInput: handleCoordinatesInput,
  resetForm,
  loadFromFeature,
  buildPayload,
  captureSnapshot,
  getMarkerCoordinates,
} = usePlaceForm();

const {
  searchQuery,
  searchResults,
  showResults,
  isSearching,
  searchTimeout,
  handleSearchInput,
  performSearch,
  clearSearch,
  getGeocodingResultCoordinates,
} = useGeocodingSearch();

const mapContainer = ref(null);
const map = ref(null);
const mapController = ref(null);
const showLayerPickerModal = ref(false);
const baseSourceOptions = ref([]);
const selectedBaseSourceId = ref('osm');
const saving = ref(false);
const loadingEdit = ref(false);
const isGettingLocation = ref(false);

const editId = computed(() => {
  const raw = route.params.id;
  if (raw == null || raw === '') {
    return null;
  }
  const parsed = parseInt(String(raw), 10);
  return Number.isNaN(parsed) ? null : parsed;
});

const pageTitle = computed(() => (editId.value ? 'Edit Place' : 'New Place'));
useDocumentTitle(pageTitle);

function updateMarkerFromCoords(panMap = false) {
  if (!map.value || !mapController.value) {
    return;
  }
  const marker = getMarkerCoordinates();
  if (!marker) {
    mapController.value.setPointFeatures([]);
    return;
  }
  mapController.value.setPointFeatures([{
    type: 'Feature',
    geometry: { type: 'Point', coordinates: [marker.lon, marker.lat] },
    properties: { is_highlighted: 0 },
  }]);
  if (panMap) {
    map.value.easeTo({ center: [marker.lon, marker.lat], duration: 300 });
  }
}

async function validateCoordinatesField() {
  const result = await validateCoordinates();
  if (result.changed) {
    updateMarkerFromCoords(result.panMap);
  }
}

function onCoordinatesInput(value) {
  coordinatesInput.value = value;
  handleCoordinatesInput();
  void validateCoordinates({ reformatInput: false }).then((result) => {
    if (result.changed) {
      updateMarkerFromCoords(result.panMap);
    }
  });
}

async function initMap() {
  if (mapController.value) {
    mapController.value.destroy();
    mapController.value = null;
    map.value = null;
  }
  if (!mapContainer.value) {
    return;
  }

  await ensureUserSettingsLoaded();
  const controller = await createPlacesMap({
    container: mapContainer.value,
    mode: 'edit',
    sourceId: PLACE_EDIT_SOURCE_ID,
    layerId: PLACE_EDIT_LAYER_ID,
    preferredSourceId: getDefaultMapSourceId(),
    minZoom: 1,
    maxZoom: 18,
  });

  mapController.value = controller;
  map.value = controller.map;
  baseSourceOptions.value = controller.getBaseSourceOptions();
  selectedBaseSourceId.value = controller.getCurrentBaseSourceId();
  await applyDefaultBasemapFromUserSettings();
  updateMarkerFromCoords();

  map.value.on('click', (event) => {
    setCoords(event.lngLat.lat, event.lngLat.lng);
    updateMarkerFromCoords(true);
  });
}

async function applyDefaultBasemapFromUserSettings() {
  if (!mapController.value) {
    return;
  }
  const desired = getDefaultMapSourceId();
  try {
    selectedBaseSourceId.value = await mapController.value.setBaseSource(desired);
    updateMarkerFromCoords();
  } catch {
    selectedBaseSourceId.value = mapController.value.getCurrentBaseSourceId();
  }
}

async function loadPlaceForEdit(id) {
  loadingEdit.value = true;
  try {
    const feature = await getPlace(id);
    loadFromFeature(feature);
    updateMarkerFromCoords(true);
    if (map.value && feature.geometry?.coordinates?.length >= 2) {
      map.value.easeTo({
        center: [feature.geometry.coordinates[0], feature.geometry.coordinates[1]],
        zoom: 12,
        duration: 500,
      });
    }
  } catch (error) {
    console.error('Failed to load place', error);
    toast.error?.('Failed to load place.');
    router?.navigate('');
  } finally {
    loadingEdit.value = false;
  }
}

function selectSearchResult(result) {
  clearSearch();
  const coords = getGeocodingResultCoordinates(result);
  if (!coords) {
    return;
  }
  setCoords(coords.lat, coords.lon);
  updateMarkerFromCoords(true);
  if (map.value) {
    map.value.easeTo({ center: [coords.lon, coords.lat], zoom: 12, duration: 500 });
  }
}

async function useCurrentLocation() {
  if (isGettingLocation.value) {
    return;
  }
  isGettingLocation.value = true;
  try {
    const geolocationManager = window.gv_core.geolocationManager;
    const permission = await geolocationManager.checkPermission();
    if (permission === 'denied') {
      toast.error('Location permission denied. Please enable it in your browser settings.');
      return;
    }
    const coords = await geolocationManager.getCurrentPosition();
    setCoords(coords.latitude, coords.longitude);
    updateMarkerFromCoords(true);
    if (map.value) {
      map.value.easeTo({
        center: [coords.longitude, coords.latitude],
        zoom: 14,
        duration: 500,
      });
    }
  } catch (error) {
    console.error('Geolocation error:', error);
    if (error.code === 1) {
      toast.error('Location permission denied.');
    } else {
      toast.error('Failed to get your location.');
    }
  } finally {
    isGettingLocation.value = false;
  }
}

async function savePlace() {
  if (saving.value || !name.value.trim() || !coordinatesInput.value.trim()) {
    return;
  }
  const payload = buildPayload();
  if (!payload) {
    coordinateError.value = 'Invalid coordinates. Use the parse button for addresses.';
    return;
  }
  saving.value = true;
  try {
    if (editId.value) {
      await updatePlace(editId.value, payload);
      toast.success('Place updated.');
    } else {
      await createPlace(payload);
      toast.success('Place created.');
    }
    captureSnapshot();
    router?.navigate('');
  } catch (error) {
    console.error('Failed to save place', error);
    toast.error?.('Failed to save place.');
  } finally {
    saving.value = false;
  }
}

function goToList() {
  router?.navigate('');
}

function resetMapViewport() {
  if (!map.value) {
    return;
  }
  const marker = getMarkerCoordinates();
  if (marker) {
    map.value.easeTo({ center: [marker.lon, marker.lat], zoom: 12, bearing: 0, duration: 0 });
    return;
  }
  map.value.easeTo({ center: INITIAL_CENTER, zoom: INITIAL_ZOOM, bearing: 0, duration: 0 });
}

async function applyBaseSourceSelection(nextSourceId) {
  if (!mapController.value) {
    return;
  }
  try {
    selectedBaseSourceId.value = await mapController.value.setBaseSource(nextSourceId);
    updateMarkerFromCoords();
  } catch {
    selectedBaseSourceId.value = mapController.value.getCurrentBaseSourceId();
  }
}

function resetFormAndMap() {
  resetForm();
  clearSearch();
  updateMarkerFromCoords();
  if (map.value) {
    map.value.easeTo({ center: INITIAL_CENTER, zoom: INITIAL_ZOOM, bearing: 0, duration: 0 });
  }
}

function handleBeforeUnload(event) {
  if (isDirty.value) {
    event.preventDefault();
    event.returnValue = '';
  }
}

onBeforeRouteLeave((_to, _from, next) => {
  if (!isDirty.value) {
    next();
    return;
  }
  if (confirm('You have unsaved changes. Leave anyway?')) {
    next();
  } else {
    next(false);
  }
});

watch(
  () => window.gv_core?.store?.getters?.['userSettings/userSettings'],
  (userSettings) => {
    if (!userSettings) {
      return;
    }
    void applyDefaultBasemapFromUserSettings();
  },
  { deep: true, immediate: true },
);

watch(editId, (newId) => {
  if (!map.value) {
    return;
  }
  if (newId != null) {
    void loadPlaceForEdit(newId);
  } else {
    resetFormAndMap();
  }
});

onMounted(async () => {
  window.addEventListener('beforeunload', handleBeforeUnload);
  const editing = editId.value != null;
  if (editing) {
    loadingEdit.value = true;
  }
  try {
    await initMap();
    if (editing) {
      await loadPlaceForEdit(editId.value);
    } else {
      resetFormAndMap();
    }
  } catch (error) {
    console.error('Failed to initialize place edit page', error);
    loadingEdit.value = false;
    if (editing) {
      toast.error?.('Failed to load place.');
      router?.navigate('');
    }
  }
});

onActivated(() => {
  void applyDefaultBasemapFromUserSettings();
});

onDeactivated(() => {
  resetFormAndMap();
});

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload);
  if (mapController.value) {
    mapController.value.destroy();
    mapController.value = null;
    map.value = null;
  }
});
</script>
