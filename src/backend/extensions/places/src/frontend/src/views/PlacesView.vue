<template>
  <div class="space-y-6 px-4 sm:px-6 lg:px-8 pt-6">
    <!-- Page Header (matches Collections page) -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 sm:p-6">
      <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 mb-4">
        <div>
          <h1 class="text-xl sm:text-2xl font-bold text-gray-900 mb-1 sm:mb-2">Places</h1>
        </div>
        <BaseButton
          @click="goToNewPlace"
          class="w-full sm:w-auto"
          variant="primary"
          color="blue"
          size="md"
          title="Add a new place"
        >
          <PlusIcon class="h-5 w-5 mr-2" />
          Add Place
        </BaseButton>
      </div>

      <!-- Explanatory Text -->
      <div class="mt-2 sm:mt-4 p-4 bg-blue-50 border border-blue-200 rounded-lg">
        <p class="text-sm text-gray-700">
          Places are saved location bookmarks to help you remember important spots.
        </p>
      </div>
    </div>

    <!-- List + Map row: fixed height so list scrolls instead of growing the page -->
    <div class="flex gap-3 min-h-0 shrink-0" style="height: 60vh; min-height: 400px;">
      <!-- List panel (50% width, card style) -->
      <div class="w-1/2 min-w-0 min-h-0 flex flex-col bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden relative">
        <!-- Loading overlay: grey out and disable list while refreshing -->
        <div
          v-if="loading"
          class="absolute inset-0 z-10 flex flex-col items-center justify-center bg-white/50 pointer-events-auto cursor-wait rounded-lg"
          aria-busy="true"
          aria-live="polite"
        >
          <div class="inline-flex bg-white rounded-lg shadow-lg border border-gray-200 px-4 py-3">
            <Loader size="sm" layout="inline" :show-message="true" message="Loading places..." />
          </div>
        </div>
        <!-- Search + Sort -->
        <div class="p-4 border-b border-gray-200 space-y-3">
          <div class="relative">
            <div class="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
              <MagnifyingGlassIcon class="h-5 w-5 text-gray-500" aria-hidden="true" />
            </div>
            <input
              type="text"
              v-model="searchQuery"
              class="block w-full pl-10 pr-3 py-2 border border-gray-300 rounded-lg shadow-sm focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all sm:text-sm"
              placeholder="Search places..."
            />
          </div>
          <div class="flex items-center gap-2">
            <label for="places-sort" class="text-xs font-medium text-gray-600 whitespace-nowrap">Sort by</label>
            <select
              id="places-sort"
              v-model="sortBy"
              class="select-custom w-auto min-w-0 px-3 py-1.5 text-sm border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
            >
              <option value="composite">Default</option>
              <option value="created">Last created</option>
              <option value="modified">Last modified</option>
              <option value="navigated">Last navigated to</option>
            </select>
          </div>
        </div>

        <!-- List -->
        <div class="flex-1 overflow-y-auto p-4">
          <div v-if="filteredPlaces.length === 0 && !loading" class="text-center py-12">
            <div class="mx-auto w-12 h-12 text-gray-500 mb-4">
              <MapPinIcon class="w-12 h-12 mx-auto" />
            </div>
            <h3 class="text-sm font-medium text-gray-900">No places found</h3>
            <p class="mt-1 text-sm text-gray-600">Get started by creating a new place.</p>
          </div>
          <div v-else class="space-y-4">
            <div
              v-for="place in filteredPlaces"
              :key="place.properties.database_id"
              @click="selectPlace(place)"
              @mouseenter="setHoveredPlace(place.properties.database_id)"
              @mouseleave="clearHoveredPlace()"
              :class="[
                'group cursor-pointer p-3 sm:p-4 border rounded-lg transition-all',
                selectedPlace?.properties?.database_id === place.properties.database_id
                  ? 'border-blue-500 bg-blue-50'
                  : 'border-gray-200 bg-white hover:bg-gray-50'
              ]"
            >
              <!-- Row 1: title + icon actions inline -->
              <div class="flex items-center gap-1.5 min-w-0">
                <span class="font-bold text-gray-900 text-base truncate min-w-0 flex-1">
                  {{ place.properties.name || 'Unnamed Place' }}
                </span>
                <div
                  :class="[
                    'flex items-center gap-0.5 flex-shrink-0 transition-opacity',
                    selectedPlace?.properties?.database_id === place.properties.database_id ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'
                  ]"
                  @click.stop
                >
                  <button
                    type="button"
                    title="Edit"
                    class="p-1.5 rounded text-blue-600 hover:bg-blue-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
                    @click.stop="editPlace(place)"
                  >
                    <PencilSquareIcon class="w-4 h-4" />
                  </button>
                  <button
                    type="button"
                    title="Delete"
                    class="p-1.5 rounded text-red-600 hover:bg-red-100 focus:outline-none focus:ring-2 focus:ring-red-500"
                    @click.stop="deletePlace(place)"
                  >
                    <TrashIcon class="w-4 h-4" />
                  </button>
                  <button
                    type="button"
                    title="Description"
                    class="p-1.5 rounded text-gray-600 hover:bg-gray-200 focus:outline-none focus:ring-2 focus:ring-blue-500"
                    @click.stop="openDescriptionModal(place)"
                  >
                    <DocumentTextIcon class="w-4 h-4" />
                  </button>
                  <button
                    type="button"
                    title="Open in Google Maps"
                    class="group/maps inline-flex p-1.5 rounded hover:bg-gray-200 focus:outline-none focus:ring-2 focus:ring-blue-500"
                    @click.stop="openInGoogleMaps(place)"
                  >
                    <span class="relative inline-block w-4 h-4">
                      <img :src="googleMapsIconUrl" alt="" class="absolute inset-0 w-4 h-4 opacity-0 group-hover/maps:opacity-100 transition-none" aria-hidden="true" />
                      <img :src="googleMapsIconBwUrl" alt="Open in Google Maps" class="w-4 h-4 opacity-100 group-hover/maps:opacity-0 transition-none" />
                    </span>
                  </button>
                </div>
              </div>
              <!-- Row 2: two columns - left: description, right: coords + date -->
              <div class="grid grid-cols-[1fr_auto] gap-2 mt-1.5 items-start">
                <p
                  class="text-sm text-gray-600 min-w-0 overflow-hidden"
                  style="display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3;"
                >
                  {{ place.properties.description || 'No description' }}
                </p>
                <div class="flex flex-col items-end gap-0.5 flex-shrink-0">
                  <span class="inline-flex items-center gap-0.5 rounded text-xs font-medium bg-gray-100 text-gray-800 px-2 py-0.5">
                    {{ formatCoords(place.geometry.coordinates) }}
                    <button
                      type="button"
                      class="p-0.5 rounded text-gray-500 hover:text-gray-700 hover:bg-gray-200 focus:outline-none focus:ring-1 focus:ring-gray-400 disabled:pointer-events-none"
                      :title="copiedPlaceId === place.properties.database_id ? 'Copied!' : 'Copy coordinates'"
                      :disabled="copiedPlaceId === place.properties.database_id"
                      @click.stop="copyCoordinates(place)"
                    >
                      <CheckIcon v-if="copiedPlaceId === place.properties.database_id" class="w-3.5 h-3.5 text-green-600" />
                      <ClipboardDocumentIcon v-else class="w-3.5 h-3.5" />
                    </button>
                  </span>
                  <p v-if="place.properties.created_at" class="text-xs text-gray-600 whitespace-nowrap">
                    {{ formatCreatedDate(place.properties.created_at) }}
                  </p>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Map (50% width, card style) -->
      <div class="w-1/2 min-w-0 relative bg-gray-100 rounded-lg border border-gray-200 overflow-hidden">
        <div ref="mapContainer" class="absolute inset-0"></div>
        <div class="absolute z-10 bottom-4 left-4 flex flex-col bg-white border border-gray-200 rounded shadow-md overflow-hidden">
          <button
            type="button"
            class="p-2 bg-white text-gray-700 hover:bg-gray-50 transition-colors duration-200 focus:outline-none"
            title="Go to home extent"
            @click="resetMapToDefaultExtent"
          >
            <HomeIcon class="w-5 h-5" />
          </button>
        </div>
      </div>
    </div>

    <!-- Description modal -->
    <div
      v-if="descriptionModalPlace"
      class="fixed inset-0 z-50 flex items-center justify-center p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="description-modal-title"
    >
      <div class="absolute inset-0 bg-black/50" @click="handleDescriptionModalBackdropClick"></div>
      <div
        class="relative bg-white rounded-lg shadow-xl flex flex-col w-[50%] h-[75%] min-w-[280px] min-h-[200px]"
        @click.stop
      >
        <div class="flex items-center justify-between px-4 py-3 border-b border-gray-200 flex-shrink-0">
          <h2 id="description-modal-title" class="text-lg font-medium text-gray-900 truncate pr-2">
            {{ descriptionModalPlace.properties.name || 'Unnamed Place' }}
          </h2>
          <button
            v-if="!descriptionModalEditing"
            type="button"
            class="p-1.5 text-gray-400 hover:text-gray-600 focus:outline-none rounded"
            title="Close"
            @click="closeDescriptionModal"
          >
            <XMarkIcon class="h-5 w-5" />
          </button>
          <span v-else class="w-9"></span>
        </div>
        <div class="flex-1 overflow-y-auto px-4 py-3 min-h-0 flex flex-col">
          <template v-if="descriptionModalEditing">
            <label for="description-edit" class="block text-sm font-medium text-gray-700 mb-1 flex-shrink-0">Description</label>
            <textarea
              id="description-edit"
              ref="descriptionEditTextarea"
              v-model="descriptionEditDraft"
              class="block w-full flex-1 min-h-[120px] px-3 py-2 border border-gray-300 rounded-lg shadow-sm focus:ring-2 focus:ring-blue-500 focus:border-transparent text-sm resize-none"
              placeholder="Add a description..."
            />
          </template>
          <template v-else>
            <p class="text-sm text-gray-700 whitespace-pre-wrap">{{ descriptionModalPlace.properties.description || 'No description' }}</p>
          </template>
        </div>
        <div class="flex items-center justify-end gap-2 px-4 py-3 border-t border-gray-200 flex-shrink-0">
          <template v-if="descriptionModalEditing">
            <BaseButton type="button" variant="white" size="sm" @click="cancelDescriptionEdit">
              Cancel
            </BaseButton>
            <BaseButton type="button" variant="primary" color="blue" size="sm" :disabled="descriptionSaving" @click="saveDescriptionEdit">
              {{ descriptionSaving ? 'Saving...' : 'Save' }}
            </BaseButton>
          </template>
          <template v-else>
            <BaseButton type="button" variant="white" size="sm" @click="closeDescriptionModal">
              Close
            </BaseButton>
            <BaseButton type="button" variant="primary" color="blue" size="sm" @click="startDescriptionEdit">
              <PencilSquareIcon class="h-4 w-4 mr-1.5 inline" />
              Edit description
            </BaseButton>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, onMounted, onActivated, onBeforeUnmount, inject, computed, shallowRef, watch, nextTick } from 'vue';
import { MapPinIcon, PlusIcon, MagnifyingGlassIcon, PencilSquareIcon, TrashIcon, XMarkIcon, DocumentTextIcon, HomeIcon, ClipboardDocumentIcon, CheckIcon } from '@heroicons/vue/24/outline';
import googleMapsIconUrl from '@/assets/google-maps-icon.svg';
import googleMapsIconBwUrl from '@/assets/google-maps-icon-bw.svg';

export default {
  components: {
    MapPinIcon,
    PlusIcon,
    MagnifyingGlassIcon,
    PencilSquareIcon,
    TrashIcon,
    XMarkIcon,
    DocumentTextIcon,
    HomeIcon,
    ClipboardDocumentIcon,
    CheckIcon
  },
  setup() {
    const api = inject('placesExtensionApi');
    const placesRouter = inject('placesExtensionRouter');
    const toast = inject('toast');

    const sortBy = ref('composite');
    const places = ref([]);
    const loading = ref(true);
    const searchQuery = ref('');
    const selectedPlace = ref(null);
    const hoveredPlaceId = ref(null);
    const mapContainer = ref(null);
    const map = shallowRef(null);
    const vectorSource = shallowRef(null);
    const vectorLayer = shallowRef(null);
    const programmaticMapMove = ref(false);

    const descriptionModalPlace = ref(null);
    const descriptionModalEditing = ref(false);
    const descriptionEditDraft = ref('');
    const descriptionSaving = ref(false);
    const descriptionEditTextarea = ref(null);
    const copiedPlaceId = ref(null);
    let copiedPlaceIdTimeout = null;

    const filteredPlaces = computed(() => {
      if (!searchQuery.value) return places.value;
      const lower = searchQuery.value.toLowerCase();
      return places.value.filter(p => 
        (p.properties.name && p.properties.name.toLowerCase().includes(lower)) ||
        (p.properties.description && p.properties.description.toLowerCase().includes(lower))
      );
    });

    const fetchPlaces = async () => {
      loading.value = true;
      try {
        const sort = sortBy.value;
        const res = await api.get('/features/', {
          params: { sort },
          headers: { 'Cache-Control': 'no-cache', Pragma: 'no-cache' }
        });
        places.value = res.data.features || [];
        updateMapFeatures();
      } catch (err) {
        console.error("Failed to load places", err);
      } finally {
        loading.value = false;
      }
    };

    // Refetch when sort dropdown changes (watcher ensures we use updated sortBy)
    watch(sortBy, () => fetchPlaces());

    const initMap = () => {
        if (!window.ol) return;
        
        vectorSource.value = new window.ol.source.Vector();

        const defaultStyle = new window.ol.style.Style({
            image: new window.ol.style.Circle({
                radius: 7,
                fill: new window.ol.style.Fill({ color: '#2563EB' }),
                stroke: new window.ol.style.Stroke({ color: 'white', width: 2 })
            })
        });
        const hoveredStyle = new window.ol.style.Style({
            image: new window.ol.style.Circle({
                radius: 7,
                fill: new window.ol.style.Fill({ color: '#FBBF24' }),
                stroke: new window.ol.style.Stroke({ color: '#000', width: 2 })
            })
        });

        const layer = new window.ol.layer.Vector({
            source: vectorSource.value,
            style: (feature) => {
                const id = feature.get('database_id');
                return hoveredPlaceId.value != null && id === hoveredPlaceId.value ? hoveredStyle : defaultStyle;
            }
        });
        vectorLayer.value = layer;

        map.value = new window.ol.Map({
            target: mapContainer.value,
            controls: [],
            layers: [
                new window.ol.layer.Tile({
                    source: new window.ol.source.OSM({ attributions: [] })
                }),
                layer
            ],
            view: new window.ol.View({
                center: window.ol.proj.fromLonLat([0, 0]),
                zoom: 2
            })
        });

        // Click handler
        map.value.on('click', (e) => {
            const feature = map.value.forEachFeatureAtPixel(e.pixel, feature => feature);
            if (feature) {
                const placeId = feature.get('database_id');
                const place = places.value.find(p => p.properties.database_id === placeId);
                if (place) {
                    selectPlace(place);
                }
            } else {
                // Determine coords for new place?
                // For now, maybe just deselect
                selectedPlace.value = null;
            }
        });

        // Clear selection when user pans/zooms (not when we zoom to a selected place)
        map.value.on('moveend', () => {
            if (programmaticMapMove.value) {
                programmaticMapMove.value = false;
                return;
            }
            selectedPlace.value = null;
            hoveredPlaceId.value = null;
        });
    };

    const updateMapFeatures = () => {
        if (!vectorSource.value || !window.ol) return;
        
        vectorSource.value.clear();
        
        if (places.value.length === 0) return;

        const features = places.value.map(place => {
            const coords = place.geometry.coordinates; // [lon, lat, elev]
            const feature = new window.ol.Feature({
                geometry: new window.ol.geom.Point(window.ol.proj.fromLonLat([coords[0], coords[1]])),
                database_id: place.properties.database_id
            });
            return feature;
        });
        
        vectorSource.value.addFeatures(features);
        
        // Fit view to extent
        if (features.length > 0) {
           const extent = vectorSource.value.getExtent();
           map.value.getView().fit(extent, { padding: [50, 50, 50, 50], maxZoom: 15 });
        }
    };

    const resetMapToDefaultExtent = () => {
        if (!map.value || !window.ol) return;
        selectedPlace.value = null;
        hoveredPlaceId.value = null;
        const view = map.value.getView();
        if (places.value.length > 0 && vectorSource.value) {
            const extent = vectorSource.value.getExtent();
            view.fit(extent, { padding: [50, 50, 50, 50], maxZoom: 15, duration: 500 });
        } else {
            view.animate({
                center: window.ol.proj.fromLonLat([0, 0]),
                zoom: 2,
                duration: 500
            });
        }
    };

    const selectPlace = (place) => {
        selectedPlace.value = place;
        if (map.value && place.geometry.coordinates) {
             programmaticMapMove.value = true;
             const coords = window.ol.proj.fromLonLat([place.geometry.coordinates[0], place.geometry.coordinates[1]]);
             map.value.getView().animate({
                 center: coords,
                 zoom: 12,
                 duration: 1000
             });
        }
    };

    const editPlace = (place) => {
        if (placesRouter) placesRouter.navigate('/new?edit=' + place.properties.database_id);
    };

    const deletePlace = async (place) => {
        if (!confirm(`Are you sure you want to delete "${place.properties.name}"?`)) return;
        try {
             await api.delete(`/features/${place.properties.database_id}/`);
             selectedPlace.value = null;
             fetchPlaces();
        } catch (err) {
            console.error(err);
        }
    };

    const formatCoords = (coords) => {
        if (!coords || coords.length < 2) return '';
        return `${coords[1].toFixed(4)}, ${coords[0].toFixed(4)}`;
    };

    const copyCoordinates = async (place) => {
        const coords = place?.geometry?.coordinates;
        const text = formatCoords(coords);
        if (!text) return;
        if (copiedPlaceIdTimeout) clearTimeout(copiedPlaceIdTimeout);
        try {
            await navigator.clipboard.writeText(text);
            copiedPlaceId.value = place.properties.database_id;
            copiedPlaceIdTimeout = setTimeout(() => {
                copiedPlaceId.value = null;
                copiedPlaceIdTimeout = null;
            }, 1000);
            toast?.success?.('Coordinates copied');
        } catch {
            toast?.error?.('Failed to copy');
        }
    };

    const formatCreatedDate = (isoString) => {
        if (!isoString) return '';
        try {
            const d = new Date(isoString);
            return Number.isNaN(d.getTime()) ? '' : d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
        } catch {
            return '';
        }
    };

    const truncate = (str, n) => {
        return (str && str.length > n) ? str.substr(0, n-1) + '...' : str;
    };

    const openDescriptionModal = (place) => {
        descriptionModalPlace.value = place;
        descriptionModalEditing.value = false;
        descriptionEditDraft.value = '';
    };

    const closeDescriptionModal = () => {
        if (descriptionSaving.value) return;
        descriptionModalPlace.value = null;
        descriptionModalEditing.value = false;
        descriptionEditDraft.value = '';
    };

    const handleDescriptionModalEscape = () => {
        if (!descriptionModalEditing.value) closeDescriptionModal();
    };

    const handleDescriptionModalBackdropClick = () => {
        if (!descriptionModalEditing.value) closeDescriptionModal();
    };

    const startDescriptionEdit = () => {
        descriptionEditDraft.value = descriptionModalPlace.value?.properties?.description ?? '';
        descriptionModalEditing.value = true;
        nextTick(() => {
            descriptionEditTextarea.value?.focus();
        });
    };

    const cancelDescriptionEdit = () => {
        descriptionModalEditing.value = false;
        descriptionEditDraft.value = '';
    };

    const saveDescriptionEdit = async () => {
        if (!descriptionModalPlace.value || descriptionSaving.value) return;
        const id = descriptionModalPlace.value.properties.database_id;
        const updatedFeature = {
            ...descriptionModalPlace.value,
            properties: {
                ...descriptionModalPlace.value.properties,
                description: descriptionEditDraft.value || null
            }
        };
        descriptionSaving.value = true;
        try {
            const res = await api.put(`/features/${id}/`, updatedFeature);
            const updated = res.data;
            const idx = places.value.findIndex(p => p.properties.database_id === id);
            if (idx !== -1) places.value[idx] = updated;
            descriptionModalPlace.value = updated;
            descriptionModalEditing.value = false;
            toast?.success?.('Description updated');
        } catch (err) {
            console.error(err);
            toast?.error?.('Failed to update description');
        } finally {
            descriptionSaving.value = false;
        }
    };
    
    const googleMapsUrl = (place) => {
        const lat = place.geometry.coordinates[1];
        const lon = place.geometry.coordinates[0];
        return `https://www.google.com/maps/search/?api=1&query=${lat},${lon}`;
    };

    const openInGoogleMaps = (place) => {
        const url = googleMapsUrl(place);
        api.post(`/features/${place.properties.database_id}/navigate/`).catch(() => {});
        window.open(url, '_blank');
    };

    const goToNewPlace = () => {
        if (placesRouter) placesRouter.navigate('/new');
    };

    const setHoveredPlace = (id) => {
        hoveredPlaceId.value = id;
    };
    const clearHoveredPlace = () => {
        hoveredPlaceId.value = null;
    };

    watch(hoveredPlaceId, () => {
        if (vectorLayer.value) vectorLayer.value.changed();
    });

    const onDescriptionModalKeydown = (e) => {
        if (e.key === 'Escape') handleDescriptionModalEscape();
    };

    watch(descriptionModalPlace, (isOpen) => {
        if (isOpen) {
            document.addEventListener('keydown', onDescriptionModalKeydown);
        } else {
            document.removeEventListener('keydown', onDescriptionModalKeydown);
        }
    });

    onMounted(() => {
        initMap();
    });

    onBeforeUnmount(() => {
        if (descriptionModalPlace.value) {
            document.removeEventListener('keydown', onDescriptionModalKeydown);
        }
    });

    onActivated(() => {
        fetchPlaces();
    });

    return {
      places,
      loading,
      searchQuery,
      sortBy,
      filteredPlaces,
      selectedPlace,
      mapContainer,

      descriptionModalPlace,
      descriptionModalEditing,
      descriptionEditDraft,
      descriptionSaving,
      descriptionEditTextarea,
      copiedPlaceId,

      goToNewPlace,
      setHoveredPlace,
      clearHoveredPlace,
      selectPlace,
      editPlace,
      deletePlace,
      openInGoogleMaps,
      openDescriptionModal,
      closeDescriptionModal,
      startDescriptionEdit,
      cancelDescriptionEdit,
      saveDescriptionEdit,
      handleDescriptionModalEscape,
      handleDescriptionModalBackdropClick,
      resetMapToDefaultExtent,
      formatCoords,
      copyCoordinates,
      formatCreatedDate,
      truncate,
      googleMapsUrl,
      googleMapsIconUrl,
      googleMapsIconBwUrl
    };
  }
}
</script>
