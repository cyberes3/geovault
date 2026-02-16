<template>
  <div class="space-y-6">
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
          <PlusIcon class="h-5 w-5 mr-2"/>
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

    <!-- List + Map row: fixed height on desktop so list scrolls internally -->
    <!-- Mobile: Map top, List bottom, no fixed height so page scrolls -->
    <div class="flex flex-col-reverse sm:flex-row gap-3 min-h-0 sm:h-[60vh] sm:min-h-[400px]">
      <!-- List panel (50% width, card style) -->
      <div
          class="w-full sm:w-1/2 min-w-0 min-h-0 flex-1 flex flex-col bg-white rounded-lg shadow-sm border border-gray-200 sm:overflow-hidden relative">
        <!-- Loading overlay: grey out and disable list while refreshing -->
        <div
            v-if="loading"
            class="absolute inset-0 z-10 flex flex-col items-center justify-center bg-white/50 pointer-events-auto cursor-wait rounded-lg"
            aria-busy="true"
            aria-live="polite"
        >
          <div class="inline-flex bg-white rounded-lg shadow-lg border border-gray-200 px-4 py-3">
            <Loader size="sm" layout="inline" :show-message="true" message="Loading places..."/>
          </div>
        </div>
        <!-- Search + Sort (inline) -->
        <div class="p-4 border-b border-gray-200 flex flex-col sm:flex-row items-stretch sm:items-center gap-2">
          <div class="relative flex-1 min-w-0">
            <div class="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
              <MagnifyingGlassIcon class="h-5 w-5 text-gray-500" aria-hidden="true"/>
            </div>
            <input
                type="text"
                v-model="searchQuery"
                class="block w-full pl-10 pr-3 py-2 border border-gray-300 rounded-lg shadow-sm focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all sm:text-sm"
                placeholder="Search places..."
            />
          </div>
          <select
              id="places-sort"
              v-model="sortBy"
              class="select-custom w-full sm:w-auto min-w-0 px-3 py-2 text-sm border border-gray-300 rounded-lg shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 flex-shrink-0"
              aria-label="Sort places"
          >
            <option value="composite">Default sort</option>
            <option value="created">Last created</option>
            <option value="modified">Last modified</option>
            <option value="navigated">Last navigated to</option>
          </select>
        </div>

        <!-- List -->
        <div ref="listScrollContainer" class="flex-1 sm:overflow-y-auto p-4">
          <div v-if="filteredPlaces.length === 0 && !loading" class="text-center py-12">
            <div class="mx-auto w-12 h-12 text-gray-500 mb-4">
              <MapPinIcon class="w-12 h-12 mx-auto"/>
            </div>
            <h3 class="text-sm font-medium text-gray-900">No places found</h3>
            <p class="mt-1 text-sm text-gray-600">Get started by creating a new place.</p>
          </div>
          <div v-else class="space-y-4">
            <div
                v-for="place in filteredPlaces"
                :key="place.properties.database_id"
                :ref="(el) => setPlaceItemRef(place.properties.database_id, el)"
                :data-place-id="place.properties.database_id"
                @click="selectPlace(place, { scroll: false })"
                @mouseenter="setHoveredPlace(place.properties.database_id)"
                @mouseleave="clearHoveredPlace()"
                :class="[
                'group cursor-pointer p-3 sm:p-4 border rounded-lg transition-all',
                selectedPlace?.properties?.database_id === place.properties.database_id
                  ? 'border-blue-500 bg-blue-50'
                  : 'border-gray-200 bg-white hover:bg-gray-50'
              ]"
            >
              <!-- Mobile: stacked (Title, Buttons, Coords+date, Description). Desktop: grid row1 title|buttons, row2 desc|coords+date -->
              <div
                  class="flex flex-col gap-2 sm:grid sm:grid-cols-[1fr_auto] sm:grid-rows-[auto_auto] sm:gap-x-2 sm:gap-y-1.5 sm:items-start">
                <!-- 1. Title -->
                <span
                    class="font-bold text-gray-900 text-base truncate min-w-0 sm:min-w-0 sm:row-start-1 sm:col-start-1">
                  {{ place.properties.name || 'Unnamed Place' }}
                </span>
                <!-- 2. Buttons (always visible on mobile, hover on desktop) -->
                <div
                    :class="[
                    'flex items-center justify-center sm:justify-end gap-0.5 flex-shrink-0 transition-opacity',
                    selectedPlace?.properties?.database_id === place.properties.database_id ? 'opacity-100' : 'opacity-100 sm:opacity-0 sm:group-hover:opacity-100'
                  ]"
                    @click.stop
                    class="sm:row-start-1 sm:col-start-2"
                >
                  <button
                      type="button"
                      title="Edit"
                      class="p-1.5 rounded text-blue-600 hover:bg-blue-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
                      @click.stop="editPlace(place)"
                  >
                    <PencilSquareIcon class="w-4 h-4"/>
                  </button>
                  <button
                      type="button"
                      title="Delete"
                      class="p-1.5 rounded text-red-600 hover:bg-red-100 focus:outline-none focus:ring-2 focus:ring-red-500"
                      @click.stop="deletePlace(place)"
                  >
                    <TrashIcon class="w-4 h-4"/>
                  </button>
                  <button
                      type="button"
                      title="Description"
                      class="p-1.5 rounded text-gray-600 hover:bg-gray-200 focus:outline-none focus:ring-2 focus:ring-blue-500"
                      @click.stop="openDescriptionModal(place)"
                  >
                    <DocumentTextIcon class="w-4 h-4"/>
                  </button>
                  <button
                      type="button"
                      title="Open in Google Maps"
                      class="group/maps inline-flex p-1.5 rounded hover:bg-gray-200 focus:outline-none focus:ring-2 focus:ring-blue-500"
                      @click.stop="openInGoogleMaps(place)"
                  >
                    <span class="relative inline-block w-4 h-4">
                      <img :src="googleMapsIconUrl" alt=""
                           class="absolute inset-0 w-4 h-4 opacity-0 group-hover/maps:opacity-100 transition-none"
                           aria-hidden="true"/>
                      <img :src="googleMapsIconBwUrl" alt="Open in Google Maps"
                           class="w-4 h-4 opacity-100 group-hover/maps:opacity-0 transition-none"/>
                    </span>
                  </button>
                </div>
                <!-- 3. Coords + Date (mobile: same line centered; desktop: row 2 col 2, stacked) -->
                <div
                    class="flex flex-row flex-wrap items-center justify-center gap-2 sm:flex-col sm:items-end sm:gap-1 sm:col-start-2 sm:row-start-2">
                  <span
                      class="inline-flex items-center gap-0.5 rounded text-xs font-medium bg-gray-100 text-gray-800 px-2 py-0.5 w-fit">
                    {{ formatCoords(place.geometry.coordinates) }}
                    <button
                        type="button"
                        class="p-0.5 rounded text-gray-500 hover:text-gray-700 hover:bg-gray-200 focus:outline-none focus:ring-1 focus:ring-gray-400 disabled:pointer-events-none"
                        :title="copiedPlaceId === place.properties.database_id ? 'Copied!' : 'Copy coordinates'"
                        :disabled="copiedPlaceId === place.properties.database_id"
                        @click.stop="copyCoordinates(place)"
                    >
                      <CheckIcon v-if="copiedPlaceId === place.properties.database_id"
                                 class="w-3.5 h-3.5 text-green-600"/>
                      <ClipboardDocumentIcon v-else class="w-3.5 h-3.5"/>
                    </button>
                  </span>
                  <span v-if="place.properties.created_at"
                        class="inline-flex items-center rounded text-xs font-medium bg-gray-100 text-gray-800 px-2 py-0.5 whitespace-nowrap">
                    {{ formatCreatedDate(place.properties.created_at) }}
                  </span>
                </div>
                <!-- 4. Description (desktop: row 2 col 1) -->
                <p
                    class="text-sm text-gray-600 min-w-0 overflow-hidden sm:row-start-2 sm:col-start-1"
                    style="display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3;"
                >
                  {{ place.properties.description || 'No description' }}
                </p>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Map (50% width, card style) -->
      <div
          class="w-full sm:w-1/2 min-w-0 flex-shrink-0 relative bg-gray-100 rounded-lg border border-gray-200 overflow-hidden h-[250px] sm:h-auto">
        <div ref="mapContainer" class="absolute inset-0 touch-pan-y"></div>

        <!-- Cooperative Gesture Overlay -->
        <div
            v-if="showGestureOverlay"
            class="absolute inset-0 z-20 flex items-center justify-center bg-black/40 pointer-events-none transition-opacity duration-300"
            :class="gestureOverlayVisible ? 'opacity-100' : 'opacity-0'"
        >
          <div class="bg-black/70 text-white px-4 py-3 rounded-lg text-center font-medium shadow-lg backdrop-blur-sm">
            <p class="text-sm sm:text-base">Use two fingers to move the map</p>
          </div>
        </div>

        <div
            class="absolute z-10 bottom-4 left-4 flex flex-col bg-white border border-gray-200 rounded shadow-md overflow-hidden">
          <button
              type="button"
              class="p-2 bg-white text-gray-700 hover:bg-gray-50 transition-colors duration-200 focus:outline-none"
              title="Go to home extent"
              @click="resetMapToDefaultExtent"
          >
            <HomeIcon class="w-5 h-5"/>
          </button>
        </div>
      </div>
    </div>

    <!-- Description modal -->
    <BaseModal
        :is-open="!!descriptionModalPlace"
        :title="descriptionModalPlace?.properties?.name || 'Unnamed Place'"
        :full-screen-mobile="true"
        max-width="2xl"
        @close="closeDescriptionModal"
    >
      <div class="flex flex-col h-full">
        <div class="flex-1 p-4 sm:p-6 space-y-4">
          <template v-if="descriptionModalEditing">
            <div>
              <label for="description-edit" class="block text-sm font-medium text-gray-700 mb-1">Description</label>
              <textarea
                  id="description-edit"
                  ref="descriptionEditTextarea"
                  v-model="descriptionEditDraft"
                  class="block w-full min-h-[200px] px-3 py-2 border border-gray-300 rounded-lg shadow-sm focus:ring-2 focus:ring-blue-500 focus:border-transparent text-sm resize-none"
                  placeholder="Add a description..."
              />
            </div>
          </template>
          <template v-else>
            <div class="prose prose-sm max-w-none text-gray-700">
              <p class="whitespace-pre-wrap">
                {{ descriptionModalPlace?.properties?.description || 'No description provided for this place.' }}</p>
            </div>
          </template>
        </div>
      </div>

      <template #footer>
        <template v-if="descriptionModalEditing">
          <BaseButton type="button" variant="white" @click="cancelDescriptionEdit">
            Cancel
          </BaseButton>
          <BaseButton
              type="button"
              variant="primary"
              color="blue"
              :disabled="descriptionSaving"
              @click="saveDescriptionEdit"
          >
            {{ descriptionSaving ? 'Saving...' : 'Save Changes' }}
          </BaseButton>
        </template>
        <template v-else>
          <BaseButton type="button" variant="white" @click="closeDescriptionModal">
            Close
          </BaseButton>
          <BaseButton type="button" variant="primary" color="blue" @click="startDescriptionEdit">
            <PencilSquareIcon class="h-4 w-4 mr-1.5 inline"/>
            Edit description
          </BaseButton>
        </template>
      </template>
    </BaseModal>
  </div>
</template>

<script>
import {computed, inject, nextTick, onActivated, onBeforeUnmount, onMounted, ref, shallowRef, watch} from 'vue';
import {
  CheckIcon,
  ClipboardDocumentIcon,
  DocumentTextIcon,
  HomeIcon,
  MagnifyingGlassIcon,
  MapPinIcon,
  PencilSquareIcon,
  PlusIcon,
  TrashIcon,
  XMarkIcon
} from '@heroicons/vue/24/outline';
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
    const api = inject('extensionApi');
    const placesRouter = inject('extensionRouter');
    const toast = window.gv_core.GeoVault.toast;

    const sortBy = ref('composite');
    const places = ref([]);
    const loading = ref(true);
    const searchQuery = ref('');
    const selectedPlace = ref(null);
    const hoveredPlaceId = ref(null);
    const mapContainer = ref(null);
    const listScrollContainer = ref(null);
    const placeItemRefs = {};
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

    // Cooperative gestures state
    const showGestureOverlay = ref(false);
    const gestureOverlayVisible = ref(false);
    let gestureOverlayTimeout = null;
    const isMobile = computed(() => {
      return window.innerWidth < 1024;
    });

    // Search includes both name and description (client-side filter)
    const filteredPlaces = computed(() => {
      if (!searchQuery.value) return places.value;
      const lower = searchQuery.value.toLowerCase();
      return places.value.filter(p => {
        const name = p.properties?.name;
        const desc = p.properties?.description;
        return (name != null && String(name).toLowerCase().includes(lower)) ||
            (desc != null && String(desc).toLowerCase().includes(lower));
      });
    });

    const fetchPlaces = async () => {
      loading.value = true;
      try {
        const sort = sortBy.value;
        const res = await api.get('/features/', {
          params: {sort},
          headers: {'Cache-Control': 'no-cache', Pragma: 'no-cache'}
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

    // Force map re-render when selection or hover state changes
    watch([selectedPlace, hoveredPlaceId], () => {
      if (vectorLayer.value) {
        vectorLayer.value.changed();
      }
    });

    const initMap = () => {
      if (!window.gv_core.ol) return;

      vectorSource.value = new window.gv_core.ol.source.Vector();

      const defaultStyle = new window.gv_core.ol.style.Style({
        image: new window.gv_core.ol.style.Circle({
          radius: 7,
          fill: new window.gv_core.ol.style.Fill({color: '#2563EB'}),
          stroke: new window.gv_core.ol.style.Stroke({color: 'white', width: 2})
        })
      });
      const hoveredStyle = new window.gv_core.ol.style.Style({
        image: new window.gv_core.ol.style.Circle({
          radius: 9,
          fill: new window.gv_core.ol.style.Fill({color: '#FBBF24'}),
          stroke: new window.gv_core.ol.style.Stroke({color: '#000', width: 2})
        }),
        zIndex: 100
      });

      const layer = new window.gv_core.ol.layer.Vector({
        source: vectorSource.value,
        style: (feature) => {
          const id = feature.get('database_id');
          const isSelected = selectedPlace.value?.properties?.database_id === id;
          const isHovered = hoveredPlaceId.value != null && id === hoveredPlaceId.value;
          return (isSelected || isHovered) ? hoveredStyle : defaultStyle;
        }
      });
      vectorLayer.value = layer;

      map.value = new window.gv_core.ol.Map({
        target: mapContainer.value,
        controls: [],
        interactions: window.gv_core.ol.interaction.defaults({dragPan: false}).extend([
          new window.gv_core.ol.interaction.DragPan({
            condition: function (e) {
              // Return TRUE if it's a mouse, or if it's a multi-touch (2+ fingers)
              return e.originalEvent.pointerType === 'mouse' || (e.originalEvent.pointerType === 'touch' && isMultiTouchGesture);
            }
          })
        ]),
        layers: [
          new window.gv_core.ol.layer.Tile({
            source: new window.gv_core.ol.source.OSM({attributions: []})
          }),
          layer
        ],
        view: new window.gv_core.ol.View({
          center: window.gv_core.ol.proj.fromLonLat([0, 0]),
          zoom: 2
        })
      });

      // Add gesture handling for overlay
      const container = mapContainer.value;
      let isMultiTouchGesture = false;

      const handleTouchStart = (e) => {
        if (e.touches.length >= 2) {
          isMultiTouchGesture = true;
          hideGestureMessage();
        } else if (e.touches.length === 1) {
          isMultiTouchGesture = false;
        }
      };

      const handleTouchMove = (e) => {
        if (e.touches && e.touches.length >= 2) {
          isMultiTouchGesture = true;
          hideGestureMessage();
        } else if (e.touches && e.touches.length === 1 && !isMultiTouchGesture) {
          // Only show message if we haven't seen 2+ fingers in this touch sequence
          showGestureMessage();
        }
      };

      container.addEventListener('touchstart', handleTouchStart, {passive: true});
      container.addEventListener('touchmove', handleTouchMove, {passive: true});

      // Helper for mobile toast to scroll down
      window._placesScrollTo = (id) => {
        const place = places.value.find(p => p.properties.database_id === id);
        if (!place) return;

        // Clear any active toasts when scrolling
        toast?.clearAll?.();

        // On mobile, we DO want to scroll and zoom when they click the toast link
        selectPlace(place, {scroll: true, zoom: true});

        if (isMobile.value) {
          nextTick(() => {
            const el = listScrollContainer.value?.querySelector(`[data-place-id="${String(id)}"]`);
            if (el) {
              el.scrollIntoView({behavior: 'smooth', block: 'center'});
            }
          });
        }
      };

      // Return cleanup function for watcher/hooks if needed, or just cleanup in onBeforeUnmount
      onBeforeUnmount(() => {
        container.removeEventListener('touchstart', handleTouchStart);
        container.removeEventListener('touchmove', handleTouchMove);
        delete window._placesScrollTo;
      });

      // Click handler
      map.value.on('click', (e) => {
        const feature = map.value.forEachFeatureAtPixel(e.pixel, feature => feature, {hitTolerance: 10});
        if (feature) {
          const placeId = feature.get('database_id');
          const place = places.value.find(p => p.properties.database_id === placeId);
          if (place) {
            // Clear existing toasts before showing a new one
            toast?.clearAll?.();

            // Scroll list on desktop, but stay on map on mobile (using toast)
            // Zoom is always disabled for map-clicks
            selectPlace(place, {scroll: !isMobile.value, zoom: false});

            // On mobile, show a toast with a link to scroll to the list item
            if (isMobile.value) {
              toast?.info('', {
                html: `
                                <div class="flex items-center justify-between gap-3 min-w-[200px]">
                                    <span class="font-medium truncate">${place.properties.name || 'Selected Place'}</span>
                                    <button 
                                        onclick="window._placesScrollTo(${place.properties.database_id})"
                                        class="flex-shrink-0 text-blue-600 font-bold hover:underline"
                                    >
                                        Scroll to item
                                    </button>
                                </div>
                            `,
                duration: 4000
              });
            }
          }
        } else {
          // Determine coords for new place?
          // For now, maybe just deselect
          selectedPlace.value = null;
        }
      });

      // Clear only hover when user pans/zooms; keep selection so the highlighted point stays visible
      map.value.on('moveend', () => {
        if (programmaticMapMove.value) {
          programmaticMapMove.value = false;
          return;
        }
        hoveredPlaceId.value = null;
      });

      // Pointer cursor when hovering over a point
      map.value.on('pointermove', (e) => {
        const hit = map.value.hasFeatureAtPixel(e.pixel);
        mapContainer.value.style.cursor = hit ? 'pointer' : '';
      });
    };

    const updateMapFeatures = () => {
      if (!vectorSource.value || !window.gv_core.ol) return;

      vectorSource.value.clear();

      if (places.value.length === 0) return;

      const features = places.value.map(place => {
        const coords = place.geometry.coordinates; // [lon, lat, elev]
        const feature = new window.gv_core.ol.Feature({
          geometry: new window.gv_core.ol.geom.Point(window.gv_core.ol.proj.fromLonLat([coords[0], coords[1]])),
          database_id: place.properties.database_id
        });
        return feature;
      });

      vectorSource.value.addFeatures(features);

      // Fit view to extent
      if (features.length > 0) {
        const extent = vectorSource.value.getExtent();
        map.value.getView().fit(extent, {padding: [100, 100, 100, 100], maxZoom: 15});
      }
    };

    const resetMapToDefaultExtent = () => {
      if (!map.value || !window.gv_core.ol) return;
      selectedPlace.value = null;
      hoveredPlaceId.value = null;
      const view = map.value.getView();
      if (places.value.length > 0 && vectorSource.value) {
        const extent = vectorSource.value.getExtent();
        view.fit(extent, {
          padding: [100, 100, 100, 100],
          maxZoom: 15,
          duration: 500
        });
        view.animate({rotation: 0, duration: 500});
      } else {
        view.animate({
          center: window.gv_core.ol.proj.fromLonLat([0, 0]),
          zoom: 2,
          duration: 500,
          rotation: 0
        });
      }
    };

    const setPlaceItemRef = (id, el) => {
      if (el) placeItemRefs[id] = el;
      else delete placeItemRefs[id];
    };

    const scrollListToPlace = (place) => {
      if (!place) return;
      const id = place.properties.database_id;
      const filtered = filteredPlaces.value.some(p => p.properties.database_id === id);
      if (!filtered) searchQuery.value = '';
      const runScroll = () => {
        const container = listScrollContainer.value;
        const el = container?.querySelector(`[data-place-id="${String(id)}"]`) ?? placeItemRefs[id];
        if (el) el.scrollIntoView({block: 'center', behavior: 'smooth'});
      };
      nextTick(() => nextTick(runScroll));
    };

    const selectPlace = (place, options = {scroll: true, zoom: true}) => {
      selectedPlace.value = place;

      // Force an immediate style update so the marker turns yellow before animation
      if (vectorLayer.value) {
        vectorLayer.value.changed();
      }

      const scroll = options.scroll ?? true;
      const zoom = options.zoom ?? true;

      if (scroll) {
        scrollListToPlace(place);
      }

      if (zoom && map.value && place.geometry.coordinates) {
        programmaticMapMove.value = true;
        const coords = window.gv_core.ol.proj.fromLonLat([place.geometry.coordinates[0], place.geometry.coordinates[1]]);

        // Tiny delay to ensure the browser has a chance to render the new marker style
        // before the heavy animation loop begins.
        setTimeout(() => {
          map.value.getView().animate({
            center: coords,
            zoom: 12,
            duration: 1000
          });
        }, 50);
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

    const copyToClipboard = async (text) => {
      // Try modern API first if in secure context
      if (navigator.clipboard && navigator.clipboard.writeText && window.isSecureContext) {
        try {
          await navigator.clipboard.writeText(text);
          return true;
        } catch (err) {
          console.error('Modern clipboard API failed, trying fallback', err);
        }
      }

      // Fallback for non-secure context or if modern API fails
      return fallbackCopy(text);
    };

    const fallbackCopy = (text) => {
      try {
        const textArea = document.createElement("textarea");
        textArea.value = text;

        // Mobile compatibility settings
        textArea.readOnly = false;
        textArea.contentEditable = "true";

        // Ensure textarea is technically visible but not seen by user
        textArea.style.position = "absolute";
        textArea.style.left = "-9999px";
        textArea.style.top = (window.pageYOffset || document.documentElement.scrollTop) + "px";
        textArea.style.opacity = "0";
        textArea.style.height = "1px";
        textArea.style.width = "1px";

        document.body.appendChild(textArea);

        // Selection logic for mobile and desktop
        textArea.focus();
        textArea.select();
        // Critical for iOS and some Android browsers
        textArea.setSelectionRange(0, 99999);

        const successful = document.execCommand('copy');
        document.body.removeChild(textArea);
        return successful;
      } catch (err) {
        console.error('Fallback copy failed', err);
        return false;
      }
    };

    const copyCoordinates = async (place) => {
      const coords = place?.geometry?.coordinates;
      const text = formatCoords(coords);
      if (!text) return;
      if (copiedPlaceIdTimeout) clearTimeout(copiedPlaceIdTimeout);
      try {
        const success = await copyToClipboard(text);
        if (!success) throw new Error('Copy failed');

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
        return Number.isNaN(d.getTime()) ? '' : d.toLocaleDateString(undefined, {
          year: 'numeric',
          month: 'short',
          day: 'numeric'
        });
      } catch {
        return '';
      }
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
      const lat = place.geometry.coordinates[1];
      const lon = place.geometry.coordinates[0];
      const name = encodeURIComponent(place.properties.name || 'Place');
      const url = googleMapsUrl(place);

      api.post(`/features/${place.properties.database_id}/navigate/`).catch(() => {
      });

      if (isMobile.value) {
        const userAgent = navigator.userAgent || navigator.vendor || window.opera;
        const isAndroid = /android/i.test(userAgent);
        const isIOS = /iPad|iPhone|iPod/.test(userAgent) && !window.MSStream;

        if (isAndroid) {
          // geo: intent for Android (allows user to choose app)
          window.location.href = `geo:${lat},${lon}?q=${lat},${lon}(${name})`;
        } else if (isIOS) {
          // maps: scheme for iOS (Apple Maps or system default)
          window.location.href = `maps://?ll=${lat},${lon}&q=${name}`;
        } else {
          window.location.href = url;
        }
      } else {
        window.open(url, '_blank');
      }
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

    const showGestureMessage = () => {
      if (gestureOverlayVisible.value) return; // Already showing

      showGestureOverlay.value = true;
      // Small delay to allow v-if render, then fade in
      requestAnimationFrame(() => {
        gestureOverlayVisible.value = true;
      });

      if (gestureOverlayTimeout) clearTimeout(gestureOverlayTimeout);
      gestureOverlayTimeout = setTimeout(() => {
        hideGestureMessage();
      }, 2000);
    };

    const hideGestureMessage = () => {
      if (!gestureOverlayVisible.value) return;

      gestureOverlayVisible.value = false;
      if (gestureOverlayTimeout) {
        clearTimeout(gestureOverlayTimeout);
        gestureOverlayTimeout = null;
      }

      // Wait for fade out to finish before removing from DOM
      setTimeout(() => {
        if (!gestureOverlayVisible.value) showGestureOverlay.value = false;
      }, 300);
    };

    const onDescriptionModalKeydown = (e) => {
      if (e.key === 'Escape') {
        if (descriptionModalEditing.value) {
          e.stopPropagation();
          e.preventDefault();
        } else {
          closeDescriptionModal();
        }
      }
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
      setPlaceItemRef,
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
      resetMapToDefaultExtent,
      formatCoords,
      copyCoordinates,
      formatCreatedDate,
      googleMapsUrl,
      googleMapsIconUrl,
      googleMapsIconBwUrl,

      showGestureOverlay,
      gestureOverlayVisible
    };
  }
}
</script>
