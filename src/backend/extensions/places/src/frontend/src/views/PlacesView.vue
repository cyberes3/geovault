<template>
  <div class="flex flex-col flex-1 min-h-0 h-full w-full overflow-y-auto sm:overflow-hidden bg-gray-50 sm:mx-4 sm:my-4 sm:gap-6">
    <PlacesPageHeader @add-place="goToNewPlace"/>

    <div class="flex flex-col-reverse sm:flex-row sm:items-stretch gap-0 sm:gap-3 sm:flex-1 sm:min-h-0 sm:overflow-hidden">
      <PlaceListPanel
          ref="listPanelRef"
          :places="filteredPlaces"
          :loading="loading"
          v-model:search-query="searchQuery"
          v-model:sort-by="sortBy"
          :selected-place-id="selectedPlace?.properties?.database_id ?? null"
          :copied-place-id="copiedPlaceId"
          @select="(place) => selectPlace(place, { scroll: false })"
          @touch-select="onPlaceRowTouchEnd"
          @hover="setHoveredPlace"
          @edit="editPlace"
          @delete="deletePlace"
          @open-description="openDescriptionModal"
          @open-maps="openInGoogleMaps"
          @copy-coordinates="copyCoordinates"
      />
      <PlacesMapPanel
          ref="mapPanelRef"
          @open-layer-picker="showLayerPickerModal = true"
          @reset-viewport="resetViewport"
      />
    </div>

    <PlacesMobileSelectionBar
        :place="mobileSelectionPlace"
        @scroll-to-place="scrollToMobileSelection"
    />

    <PlacesLayerPickerModal
        :is-open="showLayerPickerModal"
        select-id="places-map-layer"
        v-model:selected-base-source-id="selectedBaseSourceId"
        :base-source-options="baseSourceOptions"
        @close="showLayerPickerModal = false"
        @update:selected-base-source-id="applyBaseSourceSelection"
    />

    <PlaceDescriptionModal
        ref="descriptionModalRef"
        :place="descriptionModalPlace"
        :editing="descriptionModalEditing"
        v-model:draft="descriptionEditDraft"
        :saving="descriptionSaving"
        @close="closeDescriptionModal"
        @start-edit="startDescriptionEdit"
        @cancel-edit="cancelDescriptionEdit"
        @save="saveDescriptionEdit"
    />
  </div>
</template>

<script setup>
import { computed, inject, nextTick, onActivated, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import PlaceDescriptionModal from '@/components/PlaceDescriptionModal.vue';
import PlaceListPanel from '@/components/PlaceListPanel.vue';
import PlacesLayerPickerModal from '@/components/PlacesLayerPickerModal.vue';
import PlacesMapPanel from '@/components/PlacesMapPanel.vue';
import PlacesMobileSelectionBar from '@/components/PlacesMobileSelectionBar.vue';
import PlacesPageHeader from '@/components/PlacesPageHeader.vue';
import { useBreakpoint } from '@/composables/useBreakpoint.js';
import { usePlacesApi } from '@/composables/usePlacesApi.js';
import { usePlacesMap } from '@/composables/usePlacesMap.js';
import { copyToClipboard } from '@/utils/clipboard.js';
import { filterPlaces, formatCoords, googleMapsUrl } from '@/utils/placeFormatters.js';
import { buildPlacePayload } from '@/utils/placePayload.js';

const PLACE_SOURCE_ID = 'gv_places_overlay_list_source';
const PLACE_LAYER_ID = 'gv_places_overlay_list_layer';

const placesRouter = inject('extensionRouter');
const toast = window.gv_core.GeoVault.toast;
const { listPlaces, updatePlace, deletePlace: deletePlaceApi, recordNavigation } = usePlacesApi();
const { isMobile } = useBreakpoint();
const {
  map,
  mapController,
  programmaticMapMove,
  updateMapFeatures,
  initMap,
  applyDefaultBasemapFromUserSettings,
  setBaseSource,
  resetMapToDefaultExtent,
  focusPlace,
  destroyMap,
} = usePlacesMap({ sourceId: PLACE_SOURCE_ID, layerId: PLACE_LAYER_ID, mode: 'list' });

const sortBy = ref('composite');
const places = ref([]);
const loading = ref(true);
const searchQuery = ref('');
const selectedPlace = ref(null);
const hoveredPlaceId = ref(null);
const mobileSelectionPlace = ref(null);
const copiedPlaceId = ref(null);
const showLayerPickerModal = ref(false);
const baseSourceOptions = ref([]);
const selectedBaseSourceId = ref('osm');

const descriptionModalPlace = ref(null);
const descriptionModalEditing = ref(false);
const descriptionEditDraft = ref('');
const descriptionSaving = ref(false);
const descriptionModalRef = ref(null);
const listPanelRef = ref(null);
const mapPanelRef = ref(null);

let copiedPlaceIdTimeout = null;

const filteredPlaces = computed(() => filterPlaces(places.value, searchQuery.value));

async function fetchPlaces() {
  loading.value = true;
  try {
    places.value = await listPlaces(sortBy.value);
    updateMapFeatures(
      places.value,
      selectedPlace.value?.properties?.database_id ?? null,
      hoveredPlaceId.value,
      { fit: true },
    );
  } catch (error) {
    console.error('Failed to load places', error);
    window.gv_core?.GeoVault?.toast?.error?.('Failed to load places');
  } finally {
    loading.value = false;
  }
}

async function setupMap() {
  const controller = await initMap(
    mapPanelRef.value?.mapContainer,
    places.value,
    selectedPlace.value?.properties?.database_id ?? null,
    hoveredPlaceId.value,
  );
  if (!controller) {
    return;
  }

  baseSourceOptions.value = controller.getBaseSourceOptions();
  selectedBaseSourceId.value = controller.getCurrentBaseSourceId();
  await applyDefaultBasemapFromUserSettings(
    places.value,
    selectedPlace.value?.properties?.database_id ?? null,
    hoveredPlaceId.value,
  );

  map.value.on('click', (event) => {
    const feature = mapController.value?.queryFirstPointAt(event.point);
    if (feature?.properties?.database_id) {
      const placeId = Number(feature.properties.database_id);
      const place = places.value.find((item) => item.properties.database_id === placeId);
      if (!place) {
        return;
      }
      selectPlace(place, { scroll: !isMobile.value, zoom: false });
      mobileSelectionPlace.value = isMobile.value ? place : null;
      return;
    }
    selectedPlace.value = null;
    mobileSelectionPlace.value = null;
    updateMapFeatures(
      places.value,
      null,
      hoveredPlaceId.value,
    );
  });

  map.value.on('moveend', () => {
    if (programmaticMapMove.value) {
      programmaticMapMove.value = false;
      return;
    }
    hoveredPlaceId.value = null;
  });

  map.value.on('pointermove', (event) => {
    const hit = map.value.queryRenderedFeatures(event.point, { layers: [PLACE_LAYER_ID] }).length > 0;
    if (mapPanelRef.value?.mapContainer) {
      mapPanelRef.value.mapContainer.style.cursor = hit ? 'pointer' : '';
    }
  });
}

function setHoveredPlace(id) {
  hoveredPlaceId.value = id;
  updateMapFeatures(
    places.value,
    selectedPlace.value?.properties?.database_id ?? null,
    hoveredPlaceId.value,
  );
}

function scrollListToPlace(place) {
  if (!place) {
    return;
  }
  const id = place.properties.database_id;
  if (!filteredPlaces.value.some((item) => item.properties.database_id === id)) {
    searchQuery.value = '';
  }
  nextTick(() => {
    nextTick(() => {
      const container = listPanelRef.value?.listScrollContainer;
      const element = container?.querySelector(`[data-place-id="${String(id)}"]`);
      if (element) {
        element.scrollIntoView({ block: 'center', behavior: 'smooth' });
      }
    });
  });
}

function selectPlace(place, options = { scroll: true, zoom: true }) {
  selectedPlace.value = place;
  updateMapFeatures(
    places.value,
    place.properties.database_id,
    hoveredPlaceId.value,
  );
  if (options.scroll) {
    scrollListToPlace(place);
  }
  if (options.zoom) {
    focusPlace(place, true);
  }
}

function scrollToMobileSelection() {
  if (!mobileSelectionPlace.value) {
    return;
  }
  selectPlace(mobileSelectionPlace.value, { scroll: true, zoom: true });
  scrollListToPlace(mobileSelectionPlace.value);
  mobileSelectionPlace.value = null;
}

function onPlaceRowTouchEnd(place, event) {
  if (event.target.closest('button')) {
    return;
  }
  event.preventDefault();
  selectPlace(place, { scroll: false, zoom: false });
  mobileSelectionPlace.value = null;
}

function goToNewPlace() {
  placesRouter?.navigate('/new');
}

function editPlace(place) {
  placesRouter?.navigate(`/edit/${place.properties.database_id}`);
}

async function deletePlace(place) {
  if (!confirm(`Are you sure you want to delete "${place.properties.name}"?`)) {
    return;
  }
  try {
    await deletePlaceApi(place.properties.database_id);
    selectedPlace.value = null;
    mobileSelectionPlace.value = null;
    await fetchPlaces();
  } catch (error) {
    console.error(error);
    toast?.error?.('Failed to delete place');
  }
}

async function copyCoordinates(place) {
  const text = formatCoords(place?.geometry?.coordinates);
  if (!text) {
    return;
  }
  if (copiedPlaceIdTimeout) {
    clearTimeout(copiedPlaceIdTimeout);
  }
  const success = await copyToClipboard(text);
  if (!success) {
    toast?.error?.('Failed to copy');
    return;
  }
  copiedPlaceId.value = place.properties.database_id;
  copiedPlaceIdTimeout = setTimeout(() => {
    copiedPlaceId.value = null;
    copiedPlaceIdTimeout = null;
  }, 1000);
  toast?.success?.('Coordinates copied');
}

function openDescriptionModal(place) {
  descriptionModalPlace.value = place;
  descriptionModalEditing.value = false;
  descriptionEditDraft.value = '';
}

function closeDescriptionModal() {
  if (descriptionSaving.value) {
    return;
  }
  descriptionModalPlace.value = null;
  descriptionModalEditing.value = false;
  descriptionEditDraft.value = '';
}

function startDescriptionEdit() {
  descriptionEditDraft.value = descriptionModalPlace.value?.properties?.description ?? '';
  descriptionModalEditing.value = true;
  nextTick(() => {
    descriptionModalRef.value?.descriptionTextarea?.focus();
  });
}

function cancelDescriptionEdit() {
  descriptionModalEditing.value = false;
  descriptionEditDraft.value = '';
}

async function saveDescriptionEdit() {
  if (!descriptionModalPlace.value || descriptionSaving.value) {
    return;
  }
  const id = descriptionModalPlace.value.properties.database_id;
  const updatedFeature = buildPlacePayload(descriptionModalPlace.value, {
    description: descriptionEditDraft.value,
  });
  descriptionSaving.value = true;
  try {
    const fromApi = await updatePlace(id, updatedFeature);
    const existing = descriptionModalPlace.value;
    const updated = {
      ...fromApi,
      properties: {
        ...fromApi.properties,
        ...(existing?.properties?.created_at != null && { created_at: existing.properties.created_at }),
      },
    };
    const index = places.value.findIndex((item) => item.properties.database_id === id);
    if (index !== -1) {
      places.value[index] = updated;
    }
    descriptionModalPlace.value = updated;
    descriptionModalEditing.value = false;
    updateMapFeatures(
      places.value,
      selectedPlace.value?.properties?.database_id ?? null,
      hoveredPlaceId.value,
    );
  } catch (error) {
    console.error(error);
    toast?.error?.('Failed to update description');
  } finally {
    descriptionSaving.value = false;
  }
}

function openInGoogleMaps(place) {
  const url = googleMapsUrl(place);
  recordNavigation(place.properties.database_id).catch(() => {});
  if (isMobile.value) {
    window.location.href = url;
  } else {
    window.open(url, '_blank');
  }
}

async function applyBaseSourceSelection(nextSourceId) {
  selectedBaseSourceId.value = await setBaseSource(
    nextSourceId,
    places.value,
    selectedPlace.value?.properties?.database_id ?? null,
    hoveredPlaceId.value,
  );
}

function resetViewport() {
  selectedPlace.value = null;
  hoveredPlaceId.value = null;
  mobileSelectionPlace.value = null;
  resetMapToDefaultExtent(
    places.value,
    null,
    null,
  );
}

watch(sortBy, () => {
  void fetchPlaces();
});

watch([selectedPlace, hoveredPlaceId], () => {
  updateMapFeatures(
    places.value,
    selectedPlace.value?.properties?.database_id ?? null,
    hoveredPlaceId.value,
  );
});

watch(
  () => window.gv_core?.store?.state?.userSettings,
  (userSettings) => {
    if (!userSettings) {
      return;
    }
    void applyDefaultBasemapFromUserSettings(
      places.value,
      selectedPlace.value?.properties?.database_id ?? null,
      hoveredPlaceId.value,
    ).then((nextId) => {
      if (nextId) {
        selectedBaseSourceId.value = nextId;
      }
    });
  },
  { deep: true, immediate: true },
);

onMounted(async () => {
  await fetchPlaces();
  await setupMap();
});

onActivated(() => {
  void fetchPlaces();
  void applyDefaultBasemapFromUserSettings(
    places.value,
    selectedPlace.value?.properties?.database_id ?? null,
    hoveredPlaceId.value,
  );
});

onBeforeUnmount(() => {
  destroyMap();
  if (copiedPlaceIdTimeout) {
    clearTimeout(copiedPlaceIdTimeout);
  }
});
</script>
