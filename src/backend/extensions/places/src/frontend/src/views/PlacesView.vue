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
          :selected-place-id="selectedPlace?.properties.database_id ?? null"
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

<script setup lang="ts">
import { computed, inject, nextTick, onActivated, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import PlaceDescriptionModal from '@/components/PlaceDescriptionModal.vue';
import PlaceListPanel from '@/components/PlaceListPanel.vue';
import PlacesLayerPickerModal from '@/components/PlacesLayerPickerModal.vue';
import PlacesMapPanel from '@/components/PlacesMapPanel.vue';
import PlacesMobileSelectionBar from '@/components/PlacesMobileSelectionBar.vue';
import PlacesPageHeader from '@/components/PlacesPageHeader.vue';
import { useBreakpoint } from '@/composables/useBreakpoint';
import { usePlacesApi } from '@/composables/usePlacesApi';
import { usePlacesMap } from '@/composables/usePlacesMap';
import { copyToClipboard } from '@/utils/clipboard';
import { filterPlaces, formatCoords, googleMapsUrl } from '@/utils/placeFormatters';
import { buildPlacePayload } from '@/utils/placePayload';
import type { TileSourceSelectOption } from '@/utils/placesBasemap';
import type { RouterLike } from '@/types/extension-setup';
import type { PlatformStateBridge } from '@/types/platform-state';
import type { PlaceFeature } from '@/types/places';
import type { MaplibreMapMouseEvent } from '@/types/maplibre';

const PLACE_SOURCE_ID = 'gv_places_overlay_list_source';
const PLACE_LAYER_ID = 'gv_places_overlay_list_layer';

const placesRouter = inject('extensionRouter') as RouterLike;
const platformState = inject('platformState') as PlatformStateBridge;
const toast = window.gv_core.GeoVault.toast;
const { listPlaces, updatePlace, deletePlace: deletePlaceApi, recordNavigation } = usePlacesApi();
const { isMobile } = useBreakpoint();
const {
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
const places = ref<PlaceFeature[]>([]);
const loading = ref(true);
const searchQuery = ref('');
const selectedPlace = ref<PlaceFeature | null>(null);
const hoveredPlaceId = ref<number | null>(null);
const mobileSelectionPlace = ref<PlaceFeature | null>(null);
const copiedPlaceId = ref<number | null>(null);
const showLayerPickerModal = ref(false);
const baseSourceOptions = ref<TileSourceSelectOption[]>([]);
const selectedBaseSourceId = ref('osm');

const descriptionModalPlace = ref<PlaceFeature | null>(null);
const descriptionModalEditing = ref(false);
const descriptionEditDraft = ref('');
const descriptionSaving = ref(false);
interface DescriptionModalExposed {
  descriptionTextarea: HTMLTextAreaElement | null;
}

interface ListPanelExposed {
  listScrollContainer: HTMLElement | null;
}

interface MapPanelExposed {
  mapContainer: HTMLElement | null;
}

const descriptionModalRef = ref<DescriptionModalExposed | null>(null);
const listPanelRef = ref<ListPanelExposed | null>(null);
const mapPanelRef = ref<MapPanelExposed | null>(null);

let copiedPlaceIdTimeout: ReturnType<typeof setTimeout> | null = null;

const filteredPlaces = computed((): PlaceFeature[] => filterPlaces(places.value, searchQuery.value));

async function fetchPlaces(): Promise<void> {
  loading.value = true;
  try {
    places.value = await listPlaces(sortBy.value);
    updateMapFeatures(
      places.value,
      selectedPlace.value?.properties.database_id ?? null,
      hoveredPlaceId.value,
      { fit: true },
    );
  } catch (error) {
    console.error('Failed to load places', error);
    toast.error('Failed to load places');
  } finally {
    loading.value = false;
  }
}

async function setupMap(): Promise<void> {
  const controller = await initMap(
    mapPanelRef.value?.mapContainer,
    places.value,
    selectedPlace.value?.properties.database_id ?? null,
    hoveredPlaceId.value,
  );
  if (!controller) {
    return;
  }

  baseSourceOptions.value = controller.getBaseSourceOptions();
  selectedBaseSourceId.value = controller.getCurrentBaseSourceId();
  await applyDefaultBasemapFromUserSettings(
    places.value,
    selectedPlace.value?.properties.database_id ?? null,
    hoveredPlaceId.value,
  );

  controller.map.on('click', (event: MaplibreMapMouseEvent) => {
    const feature = mapController.value?.queryFirstPointAt(event.point);
    const databaseId = feature?.properties.database_id;
    if (databaseId) {
      const placeId = Number(databaseId);
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

  controller.map.on('moveend', () => {
    if (programmaticMapMove.value) {
      programmaticMapMove.value = false;
      return;
    }
    hoveredPlaceId.value = null;
  });

  controller.map.on('pointermove', (event: MaplibreMapMouseEvent) => {
    const hit = controller.map.queryRenderedFeatures(event.point, { layers: [PLACE_LAYER_ID] }).length > 0;
    if (mapPanelRef.value?.mapContainer) {
      mapPanelRef.value.mapContainer.style.cursor = hit ? 'pointer' : '';
    }
  });
}

function setHoveredPlace(id: number | null): void {
  hoveredPlaceId.value = id;
  updateMapFeatures(
    places.value,
    selectedPlace.value?.properties.database_id ?? null,
    hoveredPlaceId.value,
  );
}

function scrollListToPlace(place: PlaceFeature | null): void {
  if (!place) {
    return;
  }
  const id = place.properties.database_id;
  if (!filteredPlaces.value.some((item) => item.properties.database_id === id)) {
    searchQuery.value = '';
  }
  void nextTick(() => {
    void nextTick(() => {
      const container = listPanelRef.value?.listScrollContainer;
      const element = container?.querySelector(`[data-place-id="${String(id)}"]`);
      if (element) {
        element.scrollIntoView({ block: 'center', behavior: 'smooth' });
      }
    });
  });
}

interface SelectPlaceOptions {
  scroll?: boolean;
  zoom?: boolean;
}

function selectPlace(place: PlaceFeature, options: SelectPlaceOptions = { scroll: true, zoom: true }): void {
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

function scrollToMobileSelection(): void {
  if (!mobileSelectionPlace.value) {
    return;
  }
  selectPlace(mobileSelectionPlace.value, { scroll: true, zoom: true });
  scrollListToPlace(mobileSelectionPlace.value);
  mobileSelectionPlace.value = null;
}

function onPlaceRowTouchEnd(place: PlaceFeature, event: TouchEvent): void {
  if ((event.target as HTMLElement).closest('button')) {
    return;
  }
  event.preventDefault();
  selectPlace(place, { scroll: false, zoom: false });
  mobileSelectionPlace.value = null;
}

function goToNewPlace(): void {
  void placesRouter.navigate('/new');
}

function editPlace(place: PlaceFeature): void {
  void placesRouter.navigate(`/edit/${place.properties.database_id}`);
}

async function deletePlace(place: PlaceFeature): Promise<void> {
  if (!confirm(`Are you sure you want to delete "${place.properties.name ?? ''}"?`)) {
    return;
  }
  try {
    await deletePlaceApi(place.properties.database_id);
    selectedPlace.value = null;
    mobileSelectionPlace.value = null;
    await fetchPlaces();
  } catch (error) {
    console.error(error);
    toast.error('Failed to delete place');
  }
}

async function copyCoordinates(place: PlaceFeature): Promise<void> {
  const text = formatCoords(place.geometry.coordinates);
  if (!text) {
    return;
  }
  if (copiedPlaceIdTimeout) {
    clearTimeout(copiedPlaceIdTimeout);
  }
  const success = await copyToClipboard(text);
  if (!success) {
    toast.error('Failed to copy');
    return;
  }
  copiedPlaceId.value = place.properties.database_id;
  copiedPlaceIdTimeout = setTimeout(() => {
    copiedPlaceId.value = null;
    copiedPlaceIdTimeout = null;
  }, 1000);
  toast.success('Coordinates copied');
}

function openDescriptionModal(place: PlaceFeature): void {
  descriptionModalPlace.value = place;
  descriptionModalEditing.value = false;
  descriptionEditDraft.value = '';
}

function closeDescriptionModal(): void {
  if (descriptionSaving.value) {
    return;
  }
  descriptionModalPlace.value = null;
  descriptionModalEditing.value = false;
  descriptionEditDraft.value = '';
}

function startDescriptionEdit(): void {
  descriptionEditDraft.value = descriptionModalPlace.value?.properties.description ?? '';
  descriptionModalEditing.value = true;
  void nextTick(() => {
    descriptionModalRef.value?.descriptionTextarea?.focus();
  });
}

function cancelDescriptionEdit(): void {
  descriptionModalEditing.value = false;
  descriptionEditDraft.value = '';
}

async function saveDescriptionEdit(): Promise<void> {
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
    const updated: PlaceFeature = {
      ...fromApi,
      properties: {
        ...fromApi.properties,
        ...(existing.properties.created_at != null && { created_at: existing.properties.created_at }),
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
      selectedPlace.value?.properties.database_id ?? null,
      hoveredPlaceId.value,
    );
  } catch (error) {
    console.error(error);
    toast.error('Failed to update description');
  } finally {
    descriptionSaving.value = false;
  }
}

function openInGoogleMaps(place: PlaceFeature): void {
  const url = googleMapsUrl(place);
  void recordNavigation(place.properties.database_id).catch(() => {
    /* best-effort */
  });
  if (isMobile.value) {
    window.location.href = url;
  } else {
    window.open(url, '_blank');
  }
}

async function applyBaseSourceSelection(nextSourceId: string): Promise<void> {
  selectedBaseSourceId.value = await setBaseSource(
    nextSourceId,
    places.value,
    selectedPlace.value?.properties.database_id ?? null,
    hoveredPlaceId.value,
  );
}

function resetViewport(): void {
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
    selectedPlace.value?.properties.database_id ?? null,
    hoveredPlaceId.value,
  );
});

watch(
  () => platformState.userSettings.value,
  (userSettings) => {
    if (!userSettings) {
      return;
    }
    void applyDefaultBasemapFromUserSettings(
      places.value,
      selectedPlace.value?.properties.database_id ?? null,
      hoveredPlaceId.value,
    ).then((nextId) => {
      if (nextId) {
        selectedBaseSourceId.value = nextId;
      }
    });
  },
  { deep: true, immediate: true },
);

onMounted(() => {
  void (async () => {
    await fetchPlaces();
    try {
      await setupMap();
    } catch (error) {
      console.error('Failed to initialize map', error);
      toast.error('Failed to load map');
    }
  })();
});

onActivated(() => {
  void fetchPlaces();
  void applyDefaultBasemapFromUserSettings(
    places.value,
    selectedPlace.value?.properties.database_id ?? null,
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
